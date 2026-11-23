package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ExperienceOrb;
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
import org.craftablemc.craftable.recipe.FurnaceRecipe;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.workstation.WorkstationCapability;

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
        return execute(player, plan, snapshot, delivery, true);
    }

    static CraftingResultCode execute(ServerPlayer player, CraftPlan plan, EnvironmentSnapshot snapshot,
            MainInventoryInsertion.Delivery delivery, boolean timeCompression) {
        if (!CraftingService.validContext(player)) return CraftingResultCode.INVALID_CONTEXT;
        if (!plan.hasMaterialChange()) return CraftingResultCode.MISSING_INGREDIENTS;
        boolean timed = !timeCompression && plan.hasCooking();
        var work = cookingWork(player, plan, snapshot);
        if (work == null) return CraftingResultCode.ENVIRONMENT_CHANGED;
        if (timed && !FurnaceJobs.canStart(player, work)) return CraftingResultCode.FURNACE_BUSY;
        if (timed && !deferredFitsEmpty(player, plan)) return CraftingResultCode.NO_OUTPUT_SPACE;
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
        if (!timed && delivery.failure() != null) return delivery.failure();
        List<ItemStack> playerBefore = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) playerBefore.add(player.getInventory().getItem(slot).copy());
        List<ItemEntity> created = new ArrayList<>();
        List<ItemStack> paid = timed ? new ArrayList<>() : List.of();
        CraftingResultCode failed = CraftingResultCode.ENVIRONMENT_CHANGED;
        try {
            for (var extraction : plan.extractions()) {
                var endpoint = snapshot.endpoint(extraction.endpointId()).orElseThrow();
                ItemStack removed = endpoint.container().removeItem(extraction.slot(), extraction.count());
                if (removed.getCount() != extraction.count()
                        || !ItemStack.isSameItemSameComponents(removed, extraction.expected())) {
                    throw new Rejected();
                }
                if (timed) paid.add(removed.copy());
            }
            if (timed) {
                // 订单发布是收料事务的最后一步。失败由同一前像回滚。
                // Publishing the order is the last debit step; failure restores the same preimage.
                var produced = new ArrayList<>(plan.primary());
                produced.addAll(plan.surplus());
                markChanged(player, snapshot, plan);
                var completed = plan.steps().stream().map(step -> new FurnaceJobs.CompletedStep(
                        step.recipe().id(), step.gridSize(), step.inputs(), step.output())).toList();
                if (!FurnaceJobs.start(player, work, paid, produced, cookingRewards(plan), completed)) {
                    failed = CraftingResultCode.FURNACE_BUSY;
                    throw new Rejected();
                }
                return CraftingResultCode.FURNACE_QUEUED;
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
        if (!work.isEmpty()) awardCookingExperience(player, cookingRewards(plan));
        return plan.partial() ? CraftingResultCode.PARTIAL_CREATED : CraftingResultCode.CREATED;
    }

    /** 防止不可交付的成品或退款永久占用订单。 / Reject delivery and refund payloads that cannot fit even in an empty pack. */
    private static boolean deferredFitsEmpty(ServerPlayer player, CraftPlan plan) {
        var empty = Collections.nCopies(36, ItemStack.EMPTY);
        int maximum = player.getInventory().getMaxStackSize();
        var paid = plan.extractions().stream().map(input -> input.expected().copyWithCount(input.count())).toList();
        return MainInventoryInsertion.fit(empty, maximum, plan.primary(), plan.surplus(), false).failure() == null
                && MainInventoryInsertion.fit(empty, maximum, paid, List.of(), false).failure() == null;
    }

    private static List<FurnaceJobs.Work> cookingWork(ServerPlayer player, CraftPlan plan, EnvironmentSnapshot snapshot) {
        var stations = new EnumMap<WorkstationCapability, BlockPos>(WorkstationCapability.class);
        var result = new ArrayList<FurnaceJobs.Work>();
        for (var step : plan.steps()) {
            if (step.gridSize() != 1) continue;
            var station = CraftingRecipes.processingStation(step.recipe().value());
            if (station == null) return null;
            var furnace = FurnaceRecipe.of(step.recipe().value());
            var position = stations.get(station);
            if (position == null) {
                position = snapshot.workstations().stream().filter(endpoint -> endpoint.capability() == station
                                && endpoint.isStillAvailable(player)).map(endpoint -> endpoint.position())
                        .findFirst().orElse(null);
                if (position == null) return null;
                stations.put(station, position);
            }
            if (furnace != null) result.add(new FurnaceJobs.Work(position, station, furnace.ticks()));
        }
        return result;
    }

    private static List<FurnaceJobs.Reward> cookingRewards(CraftPlan plan) {
        var rewards = new LinkedHashMap<ResourceLocation, FurnaceJobs.Reward>();
        for (var step : plan.steps()) {
            if (step.gridSize() != 1) continue;
            var cooking = FurnaceRecipe.of(step.recipe().value());
            if (cooking == null || !cooking.station().cooking() || cooking.station() == WorkstationCapability.CAMPFIRE) continue;
            float xp = cooking.experience();
            rewards.compute(step.recipe().id(), (id, old) -> new FurnaceJobs.Reward(id, old == null ? 1 : old.batches() + 1, xp));
        }
        return List.copyOf(rewards.values());
    }

    /** 交付前重新计算主背包容量。失败始终保留订单的全部物品。
     * Recheck main-inventory capacity at collection. Failure leaves the entire order available. */
    static boolean deliverDeferred(ServerPlayer player, List<ItemStack> items) {
        var inventory = player.getInventory();
        var before = MainInventoryInsertion.copyMainInventory(inventory);
        var delivery = MainInventoryInsertion.fit(before, inventory.getMaxStackSize(), items, List.of(), false);
        if (delivery.failure() != null) return false;
        try {
            MainInventoryInsertion.publish(inventory, delivery);
            inventory.setChanged();
        } catch (RuntimeException exception) {
            for (int slot = 0; slot < before.size(); slot++) inventory.setItem(slot, before.get(slot));
            inventory.setChanged();
            return false;
        }
        // 背包已经交付，网络同步异常不得令同一订单重试。 / Sync errors cannot retry an already delivered order.
        try { player.containerMenu.broadcastChanges(); }
        catch (RuntimeException exception) { Craftable.LOGGER.error("Furnace delivery sync failed", exception); }
        return true;
    }

    static void awardCookingExperience(ServerPlayer player, List<FurnaceJobs.Reward> rewards) {
        for (var reward : rewards) {
            try {
                float exact = reward.batches() * reward.xpPerBatch();
                int experience = Mth.floor(exact);
                var level = player.serverLevel();
                if (level.random.nextFloat() < exact - experience) experience++;
                ExperienceOrb.award(level, player.position(), experience);
                player.getServer().getRecipeManager().byKey(CraftingRecipes.nativeRecipeId(reward.recipe()))
                        .ifPresent(recipe -> player.awardRecipes(List.of(recipe)));
            } catch (RuntimeException exception) {
                Craftable.LOGGER.error("Post-commit furnace reward failed for {}", reward.recipe(), exception);
            }
        }
    }

    static void notifyDeferred(ServerPlayer player, List<FurnaceJobs.CompletedStep> steps) {
        if (steps.stream().anyMatch(step -> step.gridSize() == 3)) notifyWorkbench(player);
        for (var step : steps) {
            var holder = player.getServer().getRecipeManager().byKey(CraftingRecipes.nativeRecipeId(step.recipe())).orElse(null);
            notifyStep(player, step.recipe(), holder, step.output(), step.inputs());
        }
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
        if (plan.steps().stream().anyMatch(step -> step.gridSize() == 3)) notifyWorkbench(player);
        for (var step : plan.steps()) {
            notifyStep(player, step.recipe().id(), step.recipe(), step.output(), step.inputs());
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

    private static void notifyWorkbench(ServerPlayer player) {
        try { player.awardStat(Stats.INTERACT_WITH_CRAFTING_TABLE); }
        catch (RuntimeException exception) { Craftable.LOGGER.error("Post-commit workbench statistic failed", exception); }
    }

    /** 两种交付共用逐步通知，监听器失败不会重试物品交付。
     * Share step notifications across both delivery modes. An observer failure cannot replay items. */
    private static void notifyStep(ServerPlayer player, ResourceLocation id,
            net.minecraft.world.item.crafting.RecipeHolder<?> holder, ItemStack output, List<ItemStack> inputs) {
        try {
            output.onCraftedBy(player.serverLevel(), player, output.getCount());
            if (id.getNamespace().equals(Craftable.MOD_ID) && id.getPath().startsWith("brewing/")) {
                EventHooks.onPlayerBrewedPotion(player, output);
                var contents = output.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
                if (contents != null) contents.potion().ifPresent(potion ->
                        net.minecraft.advancements.CriteriaTriggers.BREWED_POTION.trigger(player, potion));
            } else if (holder != null && FurnaceRecipe.of(holder.value()) != null) {
                EventHooks.firePlayerSmeltedEvent(player, output);
            } else {
                EventHooks.firePlayerCraftingEvent(player, output, new SimpleContainer(inputs.toArray(ItemStack[]::new)));
                if (holder != null) {
                    player.triggerRecipeCrafted(holder, inputs);
                    player.awardRecipes(List.of(holder));
                }
            }
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Post-commit recipe observer failed for {}", id, exception);
        }
    }

    private record SlotBackup(ContainerEndpoint endpoint, int slot, ItemStack stack) {}
    private static final class Rejected extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
