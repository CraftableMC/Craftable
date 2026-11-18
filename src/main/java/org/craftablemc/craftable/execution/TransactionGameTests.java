package org.craftablemc.craftable.execution;

import static org.craftablemc.craftable.execution.TransactionTestSupport.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.environment.ContainerEndpoint;
import org.craftablemc.craftable.environment.EndpointKind;
import org.craftablemc.craftable.planner.CraftPlan;

/** 在真实事件观察者和多来源失败下检查原子交付；迟到拒绝须回滚先前扣除/掉落，统计只能在成功后发布。
 * Exercises atomic delivery under real observers and multi-source failures;
 * late rejection must roll back prior extraction/drops and publish statistics only on success. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TransactionGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void joinObserverChangingDropRejectsEntireDelivery(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var drops = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getEntity() instanceof ItemEntity entity && entity.getOwner() == player) {
                drops.add(entity);
                entity.setItem(new ItemStack(Items.DIRT));
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, listener);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "changed drop accepted");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "log not restored");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "diamonds not restored");
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "primary escaped rollback");
            helper.assertTrue(!drops.isEmpty() && drops.stream().allMatch(ItemEntity::isRemoved), "changed entity leaked");
            helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE)),
                    0, "rolled-back chain counted workbench use");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            drops.forEach(ItemEntity::discard);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void completeChainPublishesOnceAndReportsAllSteps(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var events = new ArrayList<net.minecraft.world.item.Item>();
        Consumer<PlayerEvent.ItemCraftedEvent> listener = e -> {
            if (e.getEntity() == player) events.add(e.getCrafting().getItem());
        };
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent.class, listener);
        try {
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.CREATED, "chain commit");
            helper.assertTrue(source.isEmpty(), "Inputs not consumed");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "pickaxe");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 2, "surplus sticks");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "surplus planks");
            helper.assertValueEqual(events, List.of(Items.OAK_PLANKS, Items.STICK, Items.DIAMOND_PICKAXE), "step events");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void oneFreeSlotAllowsOnlyPrimaryWhenDropsAreEnabled(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var drops = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> listener = e -> {
            if (e.getEntity() instanceof ItemEntity item && item.getOwner() == player) drops.add(item);
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, listener);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            helper.assertValueEqual(MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false).failure(),
                    CraftingResultCode.NO_OUTPUT_SPACE, "strict capacity");
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(delivery.drops().size(), 2, "two surplus types");
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery), CraftingResultCode.CREATED, "drop commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "primary delivered");
            // 当前固定原版 API 的 setThrower 用 owner 表示拾取来源。
            // setThrower uses owner for pickup provenance in the pinned vanilla API.
            helper.assertValueEqual(drops.size(), 2, "published drops");
            helper.assertTrue(drops.stream().allMatch(ItemEntity::hasPickUpDelay), "Missing vanilla pickup delay");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            drops.forEach(ItemEntity::discard);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void secondDropRejectionRemovesFirstAndRestoresEveryInput(GameTestHelper helper) {
        // 第二个掉落实体才拒绝，刻意越过首次发布点；只在首个掉落前失败无法证明完整回滚。
        // Reject only the second drop, beyond the first publication point;
        // failing before any drop would not prove complete rollback.
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
        var created = new ArrayList<ItemEntity>();
        Consumer<EntityJoinLevelEvent> cancel = e -> {
            if (e.getEntity() instanceof ItemEntity item && item.getOwner() == player) {
                created.add(item);
                if (created.size() == 2) e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
        try {
            fill(player);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "rejected drop");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "restored log");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "restored diamonds");
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "Primary escaped rollback");
            helper.assertValueEqual(created.size(), 2, "second drop reached cancellation");
            helper.assertTrue(created.stream().allMatch(ItemEntity::isRemoved), "Transaction entity survived rollback");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
            created.forEach(ItemEntity::discard);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void lateExtractionMismatchRestoresEarlierChainSources(GameTestHelper helper) {
        // 来源先部分扣除再谎报空值，验证回滚恢复整个前像，而非只撤销容器声称扣掉的数量。
        // Let the source partially extract then report empty, checking rollback
        // of the full preimage rather than only the container's claimed removal.
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3)) {
            @Override public ItemStack removeItem(int slot, int count) {
                if (slot == 1) { super.removeItem(slot, 1); return ItemStack.EMPTY; }
                return super.removeItem(slot, count);
            }
        };
        try {
            var plan = plan(player, source, false);
            var snapshot = snapshot(player, source);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "extraction mismatch");
            helper.assertValueEqual(source.countItem(Items.OAK_LOG), 1, "restored first removal");
            helper.assertValueEqual(source.countItem(Items.DIAMOND), 3, "restored partially removed source");
            helper.assertTrue(player.getInventory().isEmpty(), "Output escaped failure");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void preparationPrimaryCannotBeDroppedOrPreparedTwice(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var source = new SimpleContainer(new ItemStack(Items.OAK_LOG));
        try {
            var plan = plan(player, source, true);
            var snapshot = snapshot(player, source);
            fill(player);
            helper.assertValueEqual(MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, true).failure(),
                    CraftingResultCode.NO_OUTPUT_SPACE, "partial primary capacity");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            player.getInventory().setItem(1, ItemStack.EMPTY);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                    CraftingResultCode.PARTIAL_CREATED, "preparation commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 4, "four primary sticks");
            var again = PlanningTestSupport.search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.STICK, 4));
            helper.assertTrue(again.plan().isEmpty(), "Redundant preparation");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void finalCapacityDoesNotRequireIntermediateSlots(GameTestHelper helper) {
        // 填满其余槽位，使成功依赖事务托管中间物；错误的逐步落背包模拟会无故拒绝此合法计划。
        // Fill other slots so success relies on escrowed intermediates;
        // inserting every intermediate into inventory would wrongly reject it.
        var player = PlanningTestSupport.player(helper);
        try {
            fill(player);
            player.getInventory().setItem(0, new ItemStack(Items.IRON_NUGGET, 27));
            player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
            var result = PlanningTestSupport.search(player, "iron_pickaxe", 1, false, true,
                    player.getInventory().getItem(0), player.getInventory().getItem(1));
            var plan = result.plan().orElseThrow();
            var endpoint = new ContainerEndpoint("fixture", EndpointKind.PLAYER, null, player.getInventory(), 0, 36);
            var snapshot = snapshot(player, endpoint);
            var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
            helper.assertTrue(delivery.failure() == null, "Escrow wrongly needs intermediate slots");
            helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery), CraftingResultCode.CREATED, "escrow commit");
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_PICKAXE), 1, "final output");
            helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 0, "intermediate leak");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    private static CraftPlan plan(ServerPlayer player, SimpleContainer source, boolean partial) {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < source.getContainerSize(); i++) stacks.add(source.getItem(i));
        return PlanningTestSupport.search(player, "diamond_pickaxe", 1, partial, true,
                stacks.toArray(ItemStack[]::new)).plan().orElseThrow();
    }

    private static void fill(ServerPlayer player) {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
    }
}
