package org.craftablemc.craftable.menu;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.environment.EnvironmentSnapshotService;
import net.minecraft.resources.ResourceLocation;

/** 检查真实菜单槽位、原版取出和关闭返还；权限失效不得继续取产物，重复关闭不得复制输入。
 * Exercises real menu slots, vanilla pickup and close returns; invalid authority
 * must block result pickup, and repeated closing must not duplicate inputs. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InventoryMenuGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final BlockPos TABLE = new BlockPos(1, 1, 1);
    private InventoryMenuGameTests() {}

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        var pos = h.absolutePos(new BlockPos(0, 2, 0));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }
    private static AmbientInventoryMenu open(GameTestHelper h, ServerPlayer player) {
        h.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        var menu = new AmbientInventoryMenu(1, player.getInventory(), List.of(h.absolutePos(TABLE)));
        player.containerMenu = menu;
        return menu;
    }
    private static void cleanup(ServerPlayer player) {
        if (player.containerMenu instanceof AmbientInventoryMenu) player.doCloseContainer();
        EnvironmentSnapshotService.remove(player.getUUID());
        org.craftablemc.craftable.execution.CraftingSessions.clear(player.getUUID());
        player.getServer().getPlayerList().remove(player);
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void menuPreservesPlayerInventoryAndAllSlotMappings(GameTestHelper h) {
        var player = player(h);
        var inventory = player.getInventory();
        var originalMenu = player.inventoryMenu;
        try {
            var menu = open(h, player);
            h.assertTrue(player.getInventory() == inventory && player.inventoryMenu == originalMenu, "Player inventory identity changed");
            h.assertValueEqual(menu.slots.size(), 51, "slot count");
            h.assertValueEqual(menu.getGridWidth(), 3, "grid width");
            for (int slot = 10; slot < 46; slot++) {
                h.assertTrue(menu.getSlot(slot).container == inventory, "Main inventory was replaced");
                h.assertValueEqual(menu.getSlot(slot).getContainerSlot(), slot < 37 ? slot - 1 : slot - 37, "main slot mapping");
            }
            for (int i = 0; i < 5; i++) h.assertValueEqual(menu.getSlot(46 + i).getContainerSlot(), i < 4 ? 39 - i : 40, "equipment mapping");
            for (int i = 0; i < 5; i++) {
                var original = originalMenu.getSlot(i < 4 ? 5 + i : 45);
                h.assertValueEqual(menu.getSlot(46 + i).x, original.x, "vanilla equipment x");
                h.assertValueEqual(menu.getSlot(46 + i).y, original.y, "vanilla equipment y");
            }
            h.assertTrue(menu.getSlot(0) instanceof net.minecraft.world.inventory.ResultSlot, "ResultSlot callbacks replaced");
            for (int i = 0; i < 9; i++) {
                h.assertValueEqual(menu.getSlot(1 + i).x, 98 + i % 3 * 18, "grid x");
                h.assertValueEqual(menu.getSlot(1 + i).y, 8 + i / 3 * 18, "grid y");
            }
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void vanillaResultAndShiftClickConsumeExactlyOnePickaxe(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            menu.getSlot(1).set(new ItemStack(Items.DIAMOND));
            menu.getSlot(2).set(new ItemStack(Items.DIAMOND));
            menu.getSlot(3).set(new ItemStack(Items.DIAMOND));
            menu.getSlot(5).set(new ItemStack(Items.STICK));
            menu.getSlot(8).set(new ItemStack(Items.STICK));
            h.assertTrue(menu.getSlot(0).getItem().is(Items.DIAMOND_PICKAXE), "3x3 result not computed");
            menu.quickMoveStack(player, 0);
            h.assertValueEqual(player.getInventory().countItem(Items.DIAMOND_PICKAXE), 1, "pickaxe count");
            for (int i = 0; i < 10; i++) h.assertTrue(menu.getSlot(i).getItem().isEmpty(), "crafting slot not consumed");
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void brokenTableRejectsResultPickupAndReturnsInputs(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            menu.getSlot(1).set(new ItemStack(Items.OAK_PLANKS));
            menu.getSlot(4).set(new ItemStack(Items.OAK_PLANKS));
            h.assertTrue(menu.getSlot(0).hasItem(), "No result before table removal");
            h.setBlock(TABLE, Blocks.AIR);
            h.assertFalse(menu.stillValid(player), "Broken table remained usable");
            menu.clicked(0, 0, ClickType.PICKUP, player);
            h.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "Stale result was taken");
            h.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "Grid inputs were not returned");
            h.assertTrue(player.containerMenu == player.inventoryMenu, "Invalid menu not closed");
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 240)
    public static void directCreateStillUsesM2TransactionInsideNewMenu(GameTestHelper h) {
        var player = player(h);
        try {
            open(h, player);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        } catch (RuntimeException | Error failure) { cleanup(player); throw failure; }
        // 保留真实生产期限，但不让确定性菜单契约与同 tick 的其他重规划夹具争用；期限安全另以过期时钟检查。
        // Keep the real production deadline, but do not run a deterministic
        // menu contract amid other same-tick heavy-planning fixtures.
        // Deadline safety is tested separately with an injected expired clock.
        h.runAfterDelay(120, () -> {
            try {
                // 其他测试直接替换 RecipeManager 会绕过正常启动/重载预热；恢复该前提，不放宽真实 C 搜索期限。
                // Other GameTests replace RecipeManager directly, bypassing
                // M4.7's startup/reload index warmup. Restore that normal
                // precondition, without raising the real C search deadline.
                new org.craftablemc.craftable.recipe.CraftingRecipes(player, true);
                h.assertValueEqual(CraftingService.attempt(player, ResourceLocation.withDefaultNamespace("stick"),
                        1, -1, true, CraftRequest.PartialPolicy.EXPLICIT_SAFE, null).code(),
                        CraftingResultCode.CREATED, "C transaction result");
                h.assertValueEqual(player.getInventory().countItem(Items.STICK), 4, "C output");
                h.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 0, "C cost");
                h.succeed();
            } finally { cleanup(player); }
        });
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void equipmentShiftClickAndMainInventoryShiftClickKeepTheirMeaning(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            player.getInventory().setItem(9, new ItemStack(Items.DIAMOND_HELMET));
            menu.quickMoveStack(player, 10);
            h.assertTrue(player.getInventory().getItem(39).is(Items.DIAMOND_HELMET), "Helmet did not equip");
            menu.quickMoveStack(player, 46);
            h.assertTrue(player.getInventory().getItem(39).isEmpty(), "Helmet did not unequip");
            player.getInventory().setItem(10, new ItemStack(Items.OAK_PLANKS, 2));
            menu.quickMoveStack(player, 11);
            h.assertTrue(menu.getSlot(1).getItem().is(Items.OAK_PLANKS), "Ordinary material did not enter grid");
            h.assertTrue(player.getInventory().getItem(40).isEmpty(), "Material unexpectedly equipped in offhand");
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void distanceAndGameModeInvalidateMenu(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            h.assertTrue(menu.stillValid(player), "Initial menu invalid");
            var initialMode = player.gameMode.getGameModeForPlayer();
            player.setGameMode(initialMode == net.minecraft.world.level.GameType.CREATIVE
                    ? net.minecraft.world.level.GameType.SURVIVAL : net.minecraft.world.level.GameType.CREATIVE);
            h.assertFalse(menu.stillValid(player), "Game mode change kept old menu valid");
            player.setGameMode(initialMode);
            player.setPos(player.getX() + 64, player.getY(), player.getZ());
            h.assertFalse(menu.stillValid(player), "Distant table stayed usable");
            h.assertFalse(AmbientInventoryMenu.withinRange(BlockPos.ZERO, new BlockPos(9, 0, 0), 8, 4), "Out of bounds table");
            h.assertTrue(AmbientInventoryMenu.withinRange(BlockPos.ZERO, new BlockPos(8, 4, 8), 8, 4), "Inclusive box boundary");
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void fullInventoryCloseReturnsGridAndCursorAsDropsExactlyOnce(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            menu.getSlot(1).set(new ItemStack(Items.DIAMOND, 3));
            menu.setCarried(new ItemStack(Items.EMERALD, 2));
            player.doCloseContainer();
            menu.removed(player); // 重复关闭不得复制返还输入。 / Closing twice must not duplicate returned inputs.
            var drops = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    player.getBoundingBox().inflate(3));
            h.assertValueEqual(drops.stream().filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum(), 3, "grid drops");
            h.assertValueEqual(drops.stream().filter(e -> e.getItem().is(Items.EMERALD)).mapToInt(e -> e.getItem().getCount()).sum(), 2, "cursor drops");
            h.assertTrue(menu.getSlot(1).getItem().isEmpty() && menu.getCarried().isEmpty(), "Closed menu retained items");
        } finally { cleanup(player); }
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void deadPlayerCannotTakeResultAndGridIsReturned(GameTestHelper h) {
        var player = player(h);
        try {
            var menu = open(h, player);
            menu.getSlot(1).set(new ItemStack(Items.DIAMOND, 3));
            player.setHealth(0);
            h.assertFalse(menu.stillValid(player), "Dead player retained workstation capability");
            player.doCloseContainer();
            int count = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    player.getBoundingBox().inflate(3)).stream().filter(e -> e.getItem().is(Items.DIAMOND))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertValueEqual(count, 3, "dead player grid drops");
        } finally { cleanup(player); }
        h.succeed();
    }


    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void forbiddenModesRejectCreationAndReturnOldMenuContents(GameTestHelper h) {
        var player = player(h);
        try {
            for (var mode : List.of(net.minecraft.world.level.GameType.CREATIVE, net.minecraft.world.level.GameType.SPECTATOR)) {
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                var menu = open(h, player);
                menu.getSlot(1).set(new ItemStack(Items.DIAMOND, 3));
                menu.setCarried(new ItemStack(Items.EMERALD, 2));
                player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
                player.setGameMode(mode);
                h.assertFalse(menu.stillValid(player), "Forbidden mode retained temporary menu");
                h.assertValueEqual(CraftingService.attempt(player, ResourceLocation.withDefaultNamespace("stick"),
                        1, -1, true, CraftRequest.PartialPolicy.EXPLICIT_SAFE, null).code(),
                        CraftingResultCode.INVALID_CONTEXT, "Forged create in forbidden mode");
                menu.clicked(0, 0, ClickType.PICKUP, player);
                h.assertTrue(player.containerMenu == player.inventoryMenu, "Old menu not closed");
                h.assertValueEqual(player.getInventory().countItem(Items.DIAMOND), 3, "grid return on mode switch");
                h.assertValueEqual(player.getInventory().countItem(Items.EMERALD), 2, "cursor return on mode switch");
                h.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "Rejected create cost");
                player.getInventory().clearContent();
            }
            player.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
            var menu = open(h, player);
            h.assertTrue(menu.stillValid(player), "Adventure inventory not supported");
        } finally { cleanup(player); }
        h.succeed();
    }
}
