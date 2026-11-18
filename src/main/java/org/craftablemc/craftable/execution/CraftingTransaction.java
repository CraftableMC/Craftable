package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.environment.ContainerEndpoint;
import org.craftablemc.craftable.environment.EndpointKind;
import org.craftablemc.craftable.environment.EnvironmentSnapshot;
import org.craftablemc.craftable.planner.CraftPlan;

/**
 * 唯一服务端整链提交器：同步提取、发布背包及可选余料实体，失败恢复本事务的前像。
 * 主线程内无正常 tick 交错；任意外部钩子对其它世界状态的副作用不在补偿承诺内。
 * Sole server chain commit: synchronously extract, publish inventory and optional surplus entities, or restore preimages.
 * No normal tick interleaves on the server thread; arbitrary external hook effects are outside the rollback guarantee.
 */
final class CraftingTransaction {
    private CraftingTransaction() {}

    static CraftingResultCode execute(ServerPlayer player, CraftPlan plan, EnvironmentSnapshot snapshot,
            MainInventoryInsertion.Delivery delivery) {
        if (!CraftingService.validContext(player)) return CraftingResultCode.INVALID_CONTEXT;
        if (!plan.hasMaterialChange()) return CraftingResultCode.MISSING_INGREDIENTS;
        // 执行前再次检查端点/槽内容，不因规划和容量已通过就删除这一最终边界。
        // Recheck endpoints/slot values before execution; successful planning and fitting do not replace this final boundary.
        List<SlotBackup> otherSlots = new ArrayList<>();
        for (var extraction : plan.extractions()) {
            var endpoint = snapshot.endpoint(extraction.endpointId()).orElse(null);
            if (endpoint == null || !endpoint.containsSlot(extraction.slot()) || !endpoint.isStillValid(player)
                    || !ItemStack.matches(endpoint.container().getItem(extraction.slot()), extraction.expected())) {
                return CraftingResultCode.ENVIRONMENT_CHANGED;
            }
            if (endpoint.kind() != EndpointKind.PLAYER) {
                otherSlots.add(new SlotBackup(endpoint, extraction.slot(), extraction.expected()));
            }
        }
        if (delivery.failure() != null) return delivery.failure();
        List<ItemStack> playerBefore = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) playerBefore.add(player.getInventory().getItem(slot).copy());
        List<ItemEntity> created = new ArrayList<>();
        CraftingResultCode failed = CraftingResultCode.ENVIRONMENT_CHANGED;
        try {
            for (var extraction : plan.extractions()) {
                var endpoint = snapshot.endpoint(extraction.endpointId()).orElseThrow();
                ItemStack removed = endpoint.container().removeItem(extraction.slot(), extraction.count());
                if (removed.getCount() != extraction.count()
                        || !ItemStack.isSameItemSameComponents(removed, extraction.expected())) {
                    throw new Rejected();
                }
            }
            MainInventoryInsertion.publish(player.getInventory(), delivery);
            for (ItemStack stack : delivery.drops()) {
                ItemEntity entity = captureVanillaDrop(player, stack);
                if (entity == null) throw new Rejected();
                created.add(entity); // 在钩子取消/抛错前登记，确保能补偿。 / Track before hooks can cancel/throw so rollback owns it.
                if (NeoForge.EVENT_BUS.post(new ItemTossEvent(entity, player)).isCanceled()
                        || !ItemStack.matches(entity.getItem(), stack)
                        || !player.serverLevel().addFreshEntity(entity)
                        || !ItemStack.matches(entity.getItem(), stack)
                        || player.serverLevel().getEntity(entity.getUUID()) != entity) {
                    throw new Rejected();
                }
            }
            // 同步区间内没有正常实体 tick/拾取交错；任意修改世界的自定义钩子不属于可补偿参与方，
            // 不能据此声称具有数据库式原子性。
            // No normal entity tick/pickup can interleave this synchronous
            // section. Custom hooks mutating arbitrary worlds are not supported
            // transaction participants; do not claim database atomicity for them.
            var expectedDrops = delivery.drops();
            for (int i = 0; i < created.size(); i++) {
                ItemEntity entity = created.get(i);
                if (entity.isRemoved() || !ItemStack.matches(entity.getItem(), expectedDrops.get(i))
                        || player.serverLevel().getEntity(entity.getUUID()) != entity) throw new Rejected();
            }
            markChanged(player, snapshot, plan);
        } catch (RuntimeException exception) {
            if (!(exception instanceof Rejected)) {
                failed = CraftingResultCode.INTERNAL_ERROR;
                Craftable.LOGGER.error("Chain commit failed before completion for {}", plan.target(), exception);
            }
            // 只删除本事务创建的实体且先删再还输入；按位置清扫可能误删其他玩家的物品。
            // Remove only this transaction's entities before restoring inputs; a positional sweep could delete others' drops.
            for (ItemEntity entity : created) entity.discard();
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, playerBefore.get(slot).copy());
            for (var backup : otherSlots) {
                backup.endpoint.container().setItem(backup.slot, backup.stack.copy());
                backup.endpoint.container().setChanged();
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return failed;
        }

        // 提交后的通知不能把已交付的合成改报失败，也不能触发退还。
        // Post-commit observers cannot turn delivered work into failure or trigger a refund.
        notifyCrafted(player, plan, created);
        return plan.partial() ? CraftingResultCode.PARTIAL_CREATED : CraftingResultCode.CREATED;
    }

    private static ItemEntity captureVanillaDrop(ServerPlayer player, ItemStack stack) {
        // 服务端 drop 覆写忽略实体插入的布尔结果；这里只捕获构造，保留运动/40 tick 拾取延迟，
        // 自行验证插入，并把不可逆掉落统计推迟到提交后。
        // The server override ignores addFreshEntity's boolean result. Capture
        // construction only, preserve its motion/40-tick pickup delay, and check
        // insertion ourselves. No irreversible drop stats run before commit.
        var captured = new ArrayList<ItemEntity>();
        var previous = player.captureDrops(captured);
        try {
            ItemEntity entity = player.drop(stack.copy(), false, false);
            if (entity != null) entity.setThrower(player);
            return entity;
        } finally {
            player.captureDrops(previous);
        }
    }

    private static void markChanged(ServerPlayer player, EnvironmentSnapshot snapshot, CraftPlan plan) {
        plan.extractions().stream().map(e -> e.endpointId()).distinct().forEach(id ->
                snapshot.endpoint(id).orElseThrow().container().setChanged());
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void notifyCrafted(ServerPlayer player, CraftPlan plan, List<ItemEntity> drops) {
        // 原版统计真实开工作台而非每次取结果；一条虚拟合成链即使多批/含中间物也只算一次交互。
        // 只有成功提交且实际使用 3x3 才计数，预览、回滚及纯 2x2 部分准备不计。
        // Vanilla counts opening a physical table, not each result-slot take.
        // A Craftable action is one virtual interaction, even for many batches
        // or intermediates. Count only a committed chain actually using 3x3;
        // previews, rollbacks and 2x2-only partial preparation never count.
        if (plan.steps().stream().anyMatch(step -> step.gridSize() == 3)) {
            try { player.awardStat(Stats.INTERACT_WITH_CRAFTING_TABLE); }
            catch (RuntimeException exception) {
                Craftable.LOGGER.error("Post-commit workbench statistic failed", exception);
            }
        }
        for (var step : plan.steps()) {
            // 提交后观察者也看见中间合成；一个监听器失败既不能退款，也不能阻止后续步骤通知。
            // Post-commit observers also see intermediate crafts. One failing
            // listener must neither refund the chain nor suppress later steps.
            try {
                ItemStack output = step.output();
                output.onCraftedBy(player.serverLevel(), player, output.getCount());
                EventHooks.firePlayerCraftingEvent(player, output,
                        new SimpleContainer(step.inputs().toArray(ItemStack[]::new)));
                player.triggerRecipeCrafted(step.recipe(), step.inputs());
                player.awardRecipes(Collections.singleton(step.recipe()));
            } catch (RuntimeException exception) {
                Craftable.LOGGER.error("Post-commit recipe observer failed for {}", step.recipe().id(), exception);
            }
        }
        for (ItemEntity entity : drops) {
            try {
                player.awardStat(Stats.ITEM_DROPPED.get(entity.getItem().getItem()), entity.getItem().getCount());
                player.awardStat(Stats.DROP);
            } catch (RuntimeException exception) {
                // 统计属于提交后观察者，玩家已经收到物品，不能再因此将合成改报失败。
                // Statistics are post-commit observers, never a reason to
                // report failure after the player already received the items.
                Craftable.LOGGER.error("Post-commit drop statistic failed", exception);
            }
        }
    }

    private record SlotBackup(ContainerEndpoint endpoint, int slot, ItemStack stack) {}
    private static final class Rejected extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
