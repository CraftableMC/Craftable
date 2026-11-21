package org.craftablemc.craftable.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.workstation.FurnaceWorkstation;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 实际方块与实体验证炉准入，不运行加工或借用测试邻居的工作站；每例恢复自有位置和玩家。
 * Real blocks/entities check furnace admission without processing or borrowing neighboring fixtures; each test restores its position and player. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FurnaceScannerGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final BlockPos POSITION = new BlockPos(0, 1, 0);
    private static final EnvironmentScanSettings SCAN = new EnvironmentScanSettings(1, 0, 0, false);

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void nativeFurnacesExposeOnlyTheirOwnIdleCapability(GameTestHelper helper) {
        var player = player(helper);
        var level = helper.getLevel();
        var pos = helper.absolutePos(POSITION);
        try {
            var blocks = java.util.List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
            var capabilities = java.util.List.of(WorkstationCapability.FURNACE,
                    WorkstationCapability.BLAST_FURNACE, WorkstationCapability.SMOKER);
            for (int i = 0; i < blocks.size(); i++) {
                helper.setBlock(POSITION, blocks.get(i));
                var expected = capabilities.get(i);
                helper.assertValueEqual(FurnaceWorkstation.capability(level, pos), expected, "wrong furnace type");
                helper.assertTrue(FurnaceWorkstation.ticking(level, pos), "fixture furnace does not tick");
                helper.assertTrue(FurnaceWorkstation.idle(level, pos), "empty unlit furnace is not idle");
                var snapshot = EnvironmentScanner.scan(player, SCAN, 1);
                helper.assertTrue(snapshot.workstations().stream().anyMatch(w -> pos.equals(w.position())
                        && w.capability() == expected), "authorized furnace missing from scan");
                helper.assertValueEqual(snapshot.workstations().stream().filter(w -> pos.equals(w.position())).count(),
                        1L, "one furnace exposed multiple processing types");
                helper.assertTrue(snapshot.endpoints().stream().anyMatch(e -> pos.equals(e.position())),
                        "furnace registration removed its ordinary container view");
            }
        } finally { helper.setBlock(POSITION, Blocks.AIR); remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void nativeSlotsBurnTimeAndLocksPreventFurnaceAdmission(GameTestHelper helper) {
        var player = player(helper);
        var level = helper.getLevel();
        var pos = helper.absolutePos(POSITION);
        try {
            helper.setBlock(POSITION, Blocks.FURNACE);
            var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
            for (int slot = 0; slot < 3; slot++) {
                furnace.setItem(slot, new ItemStack(Items.STONE));
                assertUnavailable(helper, player, pos, "occupied slot " + slot);
                helper.assertTrue(furnace.getItem(slot).is(Items.STONE), "scan changed native contents");
                furnace.setItem(slot, ItemStack.EMPTY);
            }
            // 即使可见 LIT 位暂未更新，残余实际燃烧时间也不属于本模组可借用的燃料。
            // Residual native burn time is unavailable even before the visual LIT bit catches up.
            var state = furnace.saveCustomOnly(level.registryAccess());
            state.putInt("BurnTime", 20);
            furnace.loadCustomOnly(state, level.registryAccess());
            assertUnavailable(helper, player, pos, "residual native burn time");
            helper.assertValueEqual(furnace.saveCustomOnly(level.registryAccess()).getInt("BurnTime"), 20,
                    "scan consumed burn time");
            state.putInt("BurnTime", 0);
            state.putString("Lock", "furnace-key");
            furnace.loadCustomOnly(state, level.registryAccess());
            helper.assertTrue(FurnaceWorkstation.idle(level, pos), "lock fixture was not idle");
            helper.assertFalse(EnvironmentScanner.scan(player, SCAN, 2).workstations().stream()
                    .anyMatch(w -> pos.equals(w.position())), "locked furnace granted a workstation");
            state.remove("Lock");
            furnace.loadCustomOnly(state, level.registryAccess());
            level.setBlock(pos, level.getBlockState(pos).setValue(AbstractFurnaceBlock.LIT, true), 3);
            assertUnavailable(helper, player, pos, "lit furnace state");
        } finally { helper.setBlock(POSITION, Blocks.AIR); remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void onlyLitIdleVanillaCampfiresExposeCookingWithoutFoodInventory(GameTestHelper helper) {
        var player = player(helper);
        var level = helper.getLevel();
        var pos = helper.absolutePos(POSITION);
        try {
            for (var block : java.util.List.of(Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE)) {
                var lit = block.defaultBlockState().setValue(CampfireBlock.LIT, true);
                helper.setBlock(POSITION, lit);
                var snapshot = EnvironmentScanner.scan(player, SCAN, 1);
                helper.assertTrue(snapshot.workstations().stream().anyMatch(w -> pos.equals(w.position())
                        && w.capability() == WorkstationCapability.CAMPFIRE), "lit campfire missing from scan");
                helper.assertFalse(snapshot.endpoints().stream().anyMatch(e -> pos.equals(e.position())),
                        "campfire food was advertised as inventory");
                helper.setBlock(POSITION, lit.setValue(CampfireBlock.LIT, false));
                assertUnavailable(helper, player, pos, "extinguished campfire");
                helper.setBlock(POSITION, lit);
                var campfire = (CampfireBlockEntity) level.getBlockEntity(pos);
                helper.assertTrue(campfire.placeFood(null, new ItemStack(Items.BEEF), 30), "failed to place native food");
                assertUnavailable(helper, player, pos, "campfire with native food");
                helper.assertTrue(campfire.getItems().getFirst().is(Items.BEEF), "scan touched native campfire food");
                campfire.clearContent();
            }
        } finally { helper.setBlock(POSITION, Blocks.AIR); remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void unavailableFurnacesDoNotLoadChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(POSITION);
        try {
            helper.setBlock(POSITION, Blocks.BARREL);
            helper.assertTrue(FurnaceWorkstation.capability(level, pos) == null, "non-furnace gained cooking capability");
            helper.assertFalse(FurnaceWorkstation.ticking(level, pos), "non-furnace ticks as a furnace");
            helper.assertFalse(FurnaceWorkstation.idle(level, pos), "non-furnace treated as idle furnace");
            helper.setBlock(POSITION, Blocks.FURNACE);
            level.removeBlockEntity(pos);
            helper.assertTrue(FurnaceWorkstation.capability(level, pos) == null, "furnace without its block entity was accepted");
            var missing = new BlockPos(20_000_000, pos.getY(), 20_000_000);
            helper.assertTrue(level.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null,
                    "unloaded fixture unexpectedly present");
            helper.assertTrue(FurnaceWorkstation.capability(level, missing) == null, "unloaded furnace accepted");
            helper.assertFalse(FurnaceWorkstation.ticking(level, missing), "unloaded furnace considered ticking");
            helper.assertFalse(FurnaceWorkstation.idle(level, missing), "unloaded furnace considered idle");
            helper.assertTrue(level.getChunkSource().getChunkNow(missing.getX() >> 4, missing.getZ() >> 4) == null,
                    "furnace check loaded a chunk");
        } finally { helper.setBlock(POSITION, Blocks.AIR); }
        helper.succeed();
    }

    private static void assertUnavailable(GameTestHelper helper, ServerPlayer player, BlockPos pos, String cause) {
        helper.assertFalse(FurnaceWorkstation.idle(helper.getLevel(), pos), cause + " counted as idle");
        helper.assertFalse(EnvironmentScanner.scan(player, SCAN, 3).workstations().stream()
                .anyMatch(w -> pos.equals(w.position())), cause + " granted a workstation");
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(POSITION);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static void remove(ServerPlayer player) {
        EnvironmentSnapshotService.remove(player.getUUID());
        player.getServer().getPlayerList().remove(player);
    }
}
