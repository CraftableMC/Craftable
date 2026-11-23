package org.craftablemc.craftable.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.workstation.FurnaceWorkstation;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 真实服务端 tick 的持久托管/暂停/重试/退款反例，不代证跨文件崩溃原子性。
 * Real server-tick escrow/pause/retry/refund counterexamples, not cross-file crash-atomicity evidence. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FurnaceJobsGameTests {
    private static final String EMPTY = "bastion/mobs/empty", DATA_ID = "craftable_furnace_jobs";

    private FurnaceJobsGameTests() {}

    @GameTest(batch = "furnace_jobs_persistence", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void savedOrderPausesForNativeUseAndDeliversOverstackedOutputOnce(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        try {
            helper.getLevel().setBlockAndUpdate(position, Blocks.FURNACE.defaultBlockState());
            helper.assertTrue(FurnaceWorkstation.ticking(helper.getLevel(), position), "test furnace is not actually tick-eligible");
            fill(player);
            var work = List.of(new FurnaceJobs.Work(position, WorkstationCapability.FURNACE, 3));
            helper.assertTrue(FurnaceJobs.start(player, work, List.of(new ItemStack(Items.RAW_IRON)),
                    List.of(new ItemStack(Items.SNOWBALL, 64)), List.of(), List.of()), "funded order rejected");
            helper.assertTrue(!FurnaceJobs.canStart(player, work), "one owner could publish two orders");
            var furnace = (Container) helper.getLevel().getBlockEntity(position);
            furnace.setItem(0, new ItemStack(Items.COBBLESTONE));
            player.closeContainer();
            // SavedData round-trip replaces only the fixture's state, while the real BE marker remains persisted.
            // 存档往返只替换夹具状态；真实炉实体上的绑定标记保持不变。
            var serialized = serialized(player);
            helper.assertValueEqual(serialized.getList("Jobs", Tag.TAG_COMPOUND).getCompound(0)
                    .getList("Delivery", Tag.TAG_COMPOUND).size(), 4, "max-16 output was not split into legal stacks");
            reload(player, serialized);
            helper.runAtTickTime(5, () -> check(player, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 0, "native busy furnace advanced attached order");
                helper.assertValueEqual(furnace.getItem(0).getCount(), 1, "attached order changed vanilla input slot");
                furnace.setItem(0, ItemStack.EMPTY);
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            }));
            helper.runAtTickTime(12, () -> check(player, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 3, "eligible ticks did not complete order");
                helper.assertValueEqual(player.getInventory().countItem(Items.SNOWBALL), 0, "full inventory leaked output");
                helper.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
                for (int slot = 0; slot < 4; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
            }));
            helper.runAtTickTime(15, () -> check(player, () -> {
                helper.assertTrue(job(player) != null, "creative owner received a ready order");
                helper.assertValueEqual(player.getInventory().countItem(Items.SNOWBALL), 0, "mode gate leaked delivery");
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            }));
            helper.runAtTickTime(17, () -> check(player, () -> {
                helper.assertTrue(job(player) == null, "successful delivery retained replayable job");
                helper.assertValueEqual(player.getInventory().countItem(Items.SNOWBALL), 64, "deferred output quantity");
                helper.assertValueEqual(player.getInventory().countItem(Items.RAW_IRON), 0, "finished order was refunded after furnace destruction");
            }));
            helper.runAtTickTime(22, () -> {
                try {
                    helper.assertValueEqual(player.getInventory().countItem(Items.SNOWBALL), 64, "completed order delivered twice");
                    helper.succeed();
                } finally { cleanup(player); }
            });
        } catch (RuntimeException | AssertionError exception) { cleanup(player); throw exception; }
        catch (Exception exception) { cleanup(player); throw new AssertionError(exception); }
    }

    @GameTest(batch = "furnace_jobs_replacement", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void replacedSameKindFurnaceRefundsEscrowWithoutProductsOrReplay(GameTestHelper helper) {
        var furnace = Blocks.FURNACE.defaultBlockState();
        verifyReplacementRefund(helper, furnace, furnace, WorkstationCapability.FURNACE,
                List.of(new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL)), new ItemStack(Items.IRON_INGOT, 3));
    }

    @GameTest(batch = "campfire_jobs_pause", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void extinguishedCampfireOrderContinuesAfterRelightingAndClearingNativeFood(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var level = helper.getLevel();
        var lit = Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);
        try {
            level.setBlockAndUpdate(pos, lit);
            var campfire = (CampfireBlockEntity) level.getBlockEntity(pos);
            var work = List.of(new FurnaceJobs.Work(pos, WorkstationCapability.CAMPFIRE, 4));
            helper.assertTrue(FurnaceJobs.start(player, work, List.of(new ItemStack(Items.BEEF)),
                    List.of(new ItemStack(Items.COOKED_BEEF)), List.of(), List.of()), "lit campfire order rejected");
            level.setBlockAndUpdate(pos, lit.setValue(CampfireBlock.LIT, false));
            reload(player, serialized(player));
            helper.runAtTickTime(5, () -> check(player, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 0, "unlit campfire advanced order");
                helper.assertValueEqual(player.getInventory().countItem(Items.BEEF), 0, "extinguished campfire refunded order");
                level.setBlockAndUpdate(pos, lit);
                helper.assertTrue(campfire.placeFood(null, new ItemStack(Items.PORKCHOP), 40), "native slot was reserved by order");
            }));
            helper.runAtTickTime(10, () -> check(player, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 0, "native campfire food failed to pause order");
                helper.assertTrue(campfire.getItems().getFirst().is(Items.PORKCHOP), "order modified campfire food");
                campfire.clearContent();
            }));
            helper.runAtTickTime(20, () -> {
                try {
                    helper.assertTrue(job(player) == null, "relit campfire did not settle the order");
                    helper.assertValueEqual(player.getInventory().countItem(Items.COOKED_BEEF), 1, "campfire output count");
                    helper.assertValueEqual(player.getInventory().countItem(Items.BEEF), 0, "campfire also refunded paid food");
                    helper.succeed();
                } catch (Exception exception) { throw new AssertionError(exception); }
                finally { cleanup(player); }
            });
        } catch (RuntimeException | AssertionError exception) { cleanup(player); throw exception; }
        catch (Exception exception) { cleanup(player); throw new AssertionError(exception); }
    }

    @GameTest(batch = "campfire_jobs_replacement", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void replacingCampfireWithSoulCampfireRefundsTheOriginalOrder(GameTestHelper helper) {
        verifyReplacementRefund(helper, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true),
                Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true),
                WorkstationCapability.CAMPFIRE, List.of(new ItemStack(Items.BEEF, 3)), new ItemStack(Items.COOKED_BEEF, 3));
    }

    @GameTest(batch = "brewing_jobs", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void brewingOrderFollowsNativeTickAndRetainsManualFuel(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var level = helper.getLevel();
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        try {
            level.setBlockAndUpdate(position, Blocks.BREWING_STAND.defaultBlockState());
            var stand = (BrewingStandBlockEntity) level.getBlockEntity(position);
            var nativeState = stand.saveCustomOnly(level.registryAccess());
            nativeState.putByte("Fuel", (byte) 9);
            stand.loadCustomOnly(nativeState, level.registryAccess());

            var water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
            var awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
            var wart = new ItemStack(Items.NETHER_WART);
            var fuel = new ItemStack(Items.BLAZE_POWDER);
            // 这里只用短计时验证原生 tick 驱动和存档，配方时长属于酿造模型。
            // Short work isolates native ticking and persistence. The brewing model owns recipe duration.
            var work = List.of(new FurnaceJobs.Work(position, WorkstationCapability.BREWING_STAND, 4));
            var completed = List.of(new FurnaceJobs.CompletedStep(id("brewing_fixture"), 1, List.of(water, wart, fuel), awkward));
            helper.assertTrue(FurnaceJobs.start(player, work, List.of(water, wart, fuel), List.of(awkward), List.of(), completed),
                    "brewing order rejected with independent manual fuel remaining");
            stand.setItem(3, new ItemStack(Items.REDSTONE));
            reload(player, serialized(player));

            helper.runAtTickTime(6, () -> check(player, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 0, "occupied brewing stand advanced virtual order");
                helper.assertTrue(stand.getItem(3).is(Items.REDSTONE), "virtual order changed native reagent");
                helper.assertValueEqual(player.getInventory().countItem(Items.POTION), 0, "paused order delivered a potion");
                stand.clearContent();
            }));
            helper.runAtTickTime(15, () -> {
                try {
                    helper.assertTrue(job(player) == null, "brewing work did not finish after the real block tick resumed");
                    helper.assertValueEqual(player.getInventory().countItem(Items.POTION), 1, "brewing did not deliver one potion");
                    helper.assertTrue(player.getInventory().items.stream().anyMatch(stack -> ItemStack.matches(stack, awkward)),
                            "brewing changed the potion components");
                    helper.assertTrue(stand.isEmpty(), "virtual brewing entered native slots");
                    helper.assertValueEqual(stand.saveCustomOnly(level.registryAccess()).getByte("Fuel"), (byte) 9,
                            "virtual brewing drained native fuel");
                    helper.succeed();
                } catch (Exception exception) { throw new AssertionError(exception); }
                finally { cleanup(player); }
            });
        } catch (RuntimeException | AssertionError exception) { cleanup(player); throw exception; }
        catch (Exception exception) { cleanup(player); throw new AssertionError(exception); }
    }

    private static void verifyReplacementRefund(GameTestHelper helper, BlockState original, BlockState replacement,
            WorkstationCapability capability, List<ItemStack> paid, ItemStack output) {
        var player = PlanningTestSupport.player(helper);
        var other = PlanningTestSupport.player(helper);
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        try {
            helper.getLevel().setBlockAndUpdate(position, original);
            fill(player);
            var work = List.of(new FurnaceJobs.Work(position, capability, 30));
            helper.assertTrue(FurnaceJobs.start(player, work, paid, List.of(output), List.of(), List.of()), "funded order rejected");
            helper.assertTrue(!FurnaceJobs.canStart(other, work), "reserved station accepted another owner");
            helper.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(position, replacement);
            helper.runAtTickTime(4, () -> check(player, () -> {
                helper.assertTrue((boolean) field(job(player), "refund"), "same-position replacement inherited the old order");
                helper.assertTrue(FurnaceJobs.canStart(other, work), "refund waiting for space kept station reserved");
                helper.assertValueEqual(player.getInventory().countItem(output.getItem()), 0, "destroyed workstation delivered products");
                for (int slot = 0; slot < paid.size(); slot++) {
                    helper.assertValueEqual(player.getInventory().countItem(paid.get(slot).getItem()), 0,
                            "full inventory lost refund pending state");
                    player.getInventory().setItem(slot, ItemStack.EMPTY);
                }
            }, other));
            helper.runAtTickTime(10, () -> {
                try {
                    helper.assertTrue(job(player) == null, "delivered refund retained order");
                    for (var stack : paid) helper.assertValueEqual(player.getInventory().countItem(stack.getItem()),
                            stack.getCount(), "refund quantity of " + stack.getItem());
                    helper.assertValueEqual(player.getInventory().countItem(output.getItem()), 0, "refund also delivered product");
                    helper.succeed();
                } catch (Exception exception) { throw new AssertionError(exception); }
                finally { cleanup(player); cleanup(other); }
            });
        } catch (RuntimeException | AssertionError exception) { cleanup(player); cleanup(other); throw exception; }
    }

    @GameTest(batch = "furnace_jobs_observers", templateNamespace = "minecraft", template = EMPTY, timeoutTicks = 80)
    public static void savedMixedChainNotifiesOnlyAfterDeliveryAndNeverReplays(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        var crafted = new java.util.ArrayList<net.minecraft.world.item.Item>();
        Consumer<PlayerEvent.ItemCraftedEvent> observer = event -> {
            if (event.getEntity() == player) crafted.add(event.getCrafting().getItem());
        };
        NeoForge.EVENT_BUS.addListener(PlayerEvent.ItemCraftedEvent.class, observer);
        try {
            helper.getLevel().setBlockAndUpdate(position, Blocks.FURNACE.defaultBlockState());
            fill(player);
            var steps = List.of(new FurnaceJobs.CompletedStep(id("oak_planks"), 2,
                            List.of(new ItemStack(Items.OAK_LOG), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
                            new ItemStack(Items.OAK_PLANKS, 4)),
                    new FurnaceJobs.CompletedStep(id("stick"), 2,
                            List.of(new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY, new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY),
                            new ItemStack(Items.STICK, 4)),
                    new FurnaceJobs.CompletedStep(id("iron_pickaxe"), 3,
                            List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT),
                                    ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY,
                                    ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY), new ItemStack(Items.IRON_PICKAXE)));
            // 此夹具隔离持久交付观察器，用短时长验证调度；真实配方耗时/收支由炉链用例验证。
            // Isolate persisted delivery observers with short work; furnace-chain tests validate actual recipe timing/accounting.
            helper.assertTrue(FurnaceJobs.start(player,
                    List.of(new FurnaceJobs.Work(position, WorkstationCapability.FURNACE, 3)),
                    List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL)),
                    List.of(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.STICK, 2)),
                    List.of(), steps), "mixed chain order rejected");
            reload(player, serialized(player));
            helper.runAtTickTime(8, () -> observed(player, observer, () -> {
                helper.assertValueEqual((int) field(job(player), "elapsed"), 3, "mixed order did not finish");
                helper.assertTrue(crafted.isEmpty(), "undelivered order emitted crafted events");
                for (int slot = 0; slot < 3; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
            }));
            helper.runAtTickTime(13, () -> observed(player, observer, () -> {
                helper.assertTrue(job(player) == null, "delivered mixed order retained ownership");
                helper.assertValueEqual(crafted, List.of(Items.OAK_PLANKS, Items.STICK, Items.IRON_PICKAXE),
                        "persisted ordinary step notifications were lost or reordered");
                helper.assertValueEqual(player.getInventory().countItem(Items.IRON_PICKAXE), 1, "mixed order primary output");
                helper.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM
                        .get(net.minecraft.stats.Stats.INTERACT_WITH_CRAFTING_TABLE)), 1, "mixed order workbench statistic");
            }));
            helper.runAtTickTime(18, () -> {
                try {
                    helper.assertValueEqual(crafted.size(), 3, "completed mixed order replayed crafted events");
                    helper.assertValueEqual(player.getInventory().countItem(Items.IRON_PICKAXE), 1, "mixed order replayed output");
                    helper.succeed();
                } finally { NeoForge.EVENT_BUS.unregister(observer); cleanup(player); }
            });
        } catch (RuntimeException | AssertionError exception) {
            NeoForge.EVENT_BUS.unregister(observer); cleanup(player); throw exception;
        } catch (Exception exception) {
            NeoForge.EVENT_BUS.unregister(observer); cleanup(player); throw new AssertionError(exception);
        }
    }

    private static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.withDefaultNamespace(path);
    }

    private static void fill(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
    }

    private static CompoundTag serialized(ServerPlayer player) throws Exception {
        var state = state(player);
        return state.save(new CompoundTag(), player.registryAccess());
    }

    private static void reload(ServerPlayer player, CompoundTag tag) throws Exception {
        var state = state(player);
        var load = state.getClass().getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        player.getServer().overworld().getDataStorage().set(DATA_ID, (SavedData) load.invoke(null, tag, player.registryAccess()));
    }

    private static SavedData state(ServerPlayer player) throws Exception {
        var method = FurnaceJobs.class.getDeclaredMethod("state", net.minecraft.server.MinecraftServer.class);
        method.setAccessible(true);
        return (SavedData) method.invoke(null, player.getServer());
    }

    private static Object job(ServerPlayer player) throws Exception {
        return ((Map<?, ?>) field(state(player), "jobs")).get(player.getUUID());
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private interface Checked { void run() throws Exception; }

    private static void check(ServerPlayer player, Checked action, ServerPlayer... others) {
        try { action.run(); }
        catch (Exception | AssertionError exception) {
            cleanup(player);
            for (var other : others) cleanup(other);
            throw new AssertionError(exception);
        }
    }

    private static void observed(ServerPlayer player, Consumer<?> observer, Checked action) {
        try { action.run(); }
        catch (Exception | AssertionError exception) {
            NeoForge.EVENT_BUS.unregister(observer); cleanup(player); throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void cleanup(ServerPlayer player) {
        try {
            var state = state(player);
            ((Map<UUID, ?>) field(state, "jobs")).remove(player.getUUID());
            ((Map<UUID, ?>) field(state, "notices")).remove(player.getUUID());
            state.setDirty();
        } catch (Exception exception) { throw new AssertionError(exception); }
        finally { PlanningTestSupport.remove(player); }
    }
}
