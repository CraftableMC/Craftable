package org.craftablemc.craftable.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/** 在正常服务门面实测炉链的站点、真实能源和单次提交。
 * Exercise station eligibility, real fuel and one commit through the production service. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FurnaceChainGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "furnace_chain")
    public static void instantFurnaceChainUsesOneCoalAndPreservesNeededWood(GameTestHelper helper) {
        var table = new BlockPos(1, 1, 1);
        var furnace = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        try {
            helper.setBlock(table, Blocks.CRAFTING_TABLE);
            helper.setBlock(furnace, Blocks.SMOKER);
            var smelt = CraftRequest.one(ResourceLocation.withDefaultNamespace("iron_ingot_from_smelting_raw_iron"));
            inventory.setItem(0, new ItemStack(Items.RAW_IRON, 3));
            helper.assertValueEqual(CraftingService.create(player, smelt, false, budget()).code(),
                    CraftingResultCode.MISSING_WORKSTATION, "smoker accepted ordinary smelting");
            helper.setBlock(furnace, Blocks.FURNACE);
            helper.assertValueEqual(CraftingService.create(player, smelt, false, budget()).code(),
                    CraftingResultCode.MISSING_FUEL, "smelting without fuel did not explain refusal");
            helper.assertValueEqual(inventory.countItem(Items.RAW_IRON), 3, "rejected smelt debited ore");

            inventory.setItem(1, new ItemStack(Items.OAK_LOG));
            inventory.setItem(2, new ItemStack(Items.COAL));
            var pick = CraftRequest.one(ResourceLocation.withDefaultNamespace("iron_pickaxe"));
            var result = CraftingService.create(player, pick, false, budget());
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "iron pickaxe furnace chain failed");
            helper.assertValueEqual(inventory.countItem(Items.IRON_PICKAXE), 1, "pickaxe not delivered");
            helper.assertValueEqual(inventory.countItem(Items.IRON_INGOT), 0, "intermediate ingots leaked");
            helper.assertValueEqual(inventory.countItem(Items.RAW_IRON), 0, "ore was not paid");
            helper.assertValueEqual(inventory.countItem(Items.COAL), 0, "coal was not paid");
            helper.assertValueEqual((int) result.plan().steps().stream().filter(step -> step.gridSize() == 1).count(),
                    3, "route did not pay for three smelts");
            helper.assertTrue(((net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(furnace))).isEmpty(), "virtual route used native slots");

            // 高炉的燃烧时间减半：一根木棍只供 50 tick，不能支付原版 100 tick 配方。
            // Blast furnaces halve fuel duration: one stick supplies 50 ticks, below the vanilla recipe's 100 ticks.
            inventory.clearContent();
            helper.setBlock(furnace, Blocks.BLAST_FURNACE);
            var blast = CraftRequest.one(ResourceLocation.withDefaultNamespace("iron_ingot_from_blasting_raw_iron"));
            inventory.setItem(0, new ItemStack(Items.RAW_IRON));
            inventory.setItem(1, new ItemStack(Items.STICK));
            helper.assertValueEqual(CraftingService.create(player, blast, false, budget()).code(),
                    CraftingResultCode.MISSING_FUEL, "one stick paid for a full blast recipe");
            helper.assertValueEqual(inventory.countItem(Items.RAW_IRON), 1, "failed blast consumed ore");
            helper.assertValueEqual(inventory.countItem(Items.STICK), 1, "failed blast consumed fuel");
            helper.assertValueEqual(inventory.countItem(Items.IRON_INGOT), 0, "failed blast delivered an ingot");
            inventory.setItem(1, new ItemStack(Items.STICK, 2));
            helper.assertValueEqual(CraftingService.create(player, blast, false, budget()).code(),
                    CraftingResultCode.CREATED, "two sticks did not cover the blast recipe");
            helper.assertValueEqual(inventory.countItem(Items.RAW_IRON), 0, "blast did not pay one ore");
            helper.assertValueEqual(inventory.countItem(Items.STICK), 0, "blast did not pay both sticks");
            helper.assertValueEqual(inventory.countItem(Items.IRON_INGOT), 1, "blast output count differs");

            // 烟熏炉燃料的真实容器返还物也须进入同一事务，不能丢桶或多给产物。
            // A smoker's real fuel-container remainder belongs to the same transaction: no lost bucket or extra output.
            inventory.clearContent();
            helper.setBlock(furnace, Blocks.SMOKER);
            var smoke = CraftRequest.one(ResourceLocation.withDefaultNamespace("cooked_beef_from_smoking"));
            inventory.setItem(0, new ItemStack(Items.BEEF));
            inventory.setItem(1, new ItemStack(Items.LAVA_BUCKET));
            helper.assertValueEqual(CraftingService.create(player, smoke, false, budget()).code(),
                    CraftingResultCode.CREATED, "smoker recipe with returning fuel failed");
            helper.assertValueEqual(inventory.countItem(Items.BEEF), 0, "smoker did not pay the raw beef");
            helper.assertValueEqual(inventory.countItem(Items.LAVA_BUCKET), 0, "smoker did not pay its fuel");
            helper.assertValueEqual(inventory.countItem(Items.COOKED_BEEF), 1, "smoker output count differs");
            helper.assertValueEqual(inventory.countItem(Items.BUCKET), 1, "smoker did not return exactly one fuel bucket");
            helper.assertTrue(((net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(furnace))).isEmpty(), "smoker route used native slots");
        } finally {
            helper.setBlock(table, Blocks.AIR);
            helper.setBlock(furnace, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "furnace_chain")
    public static void instantCampfireCookingUsesLitStationWithoutFuel(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        var cook = CraftRequest.one(ResourceLocation.withDefaultNamespace("cooked_beef_from_campfire_cooking"));
        try {
            inventory.setItem(0, new ItemStack(Items.BEEF, 2));
            helper.setBlock(station, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            helper.assertValueEqual(CraftingService.create(player, cook, false, budget()).code(),
                    CraftingResultCode.MISSING_WORKSTATION, "Extinguished campfire admitted cooking");
            helper.assertValueEqual(inventory.countItem(Items.BEEF), 2, "Rejected cooking consumed food");

            // 有无燃料都只应扣除生肉。
            // Raw food is the sole input regardless of whether the player carries fuel.
            helper.setBlock(station, Blocks.CAMPFIRE);
            var result = CraftingService.create(player, cook, false, budget());
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "Campfire could not cook without fuel");
            helper.assertValueEqual(result.plan().steps().getFirst().inputs().size(), 1, "Campfire attached a fuel slot");
            helper.assertValueEqual(inventory.countItem(Items.BEEF), 1, "Campfire did not pay one raw beef");
            helper.assertValueEqual(inventory.countItem(Items.COOKED_BEEF), 1, "Campfire should deliver one cooked beef");

            helper.setBlock(station, Blocks.SOUL_CAMPFIRE);
            inventory.setItem(inventory.getFreeSlot(), new ItemStack(Items.COAL));
            helper.assertValueEqual(CraftingService.create(player, cook, false, budget()).code(),
                    CraftingResultCode.CREATED, "Lit soul campfire did not supply the same capability");
            helper.assertValueEqual(inventory.countItem(Items.BEEF), 0, "Soul campfire did not pay the remaining food");
            helper.assertValueEqual(inventory.countItem(Items.COOKED_BEEF), 2, "Each campfire should deliver one cooked beef");
            helper.assertValueEqual(inventory.countItem(Items.COAL), 1, "Soul campfire consumed coal");
        } finally {
            helper.setBlock(station, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "furnace_chain")
    public static void wetSpongeDryingAndWaterCollectionAreDistinctRoutes(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        try {
            helper.setBlock(station, Blocks.FURNACE);
            inventory.setItem(0, new ItemStack(Items.WET_SPONGE, 3));
            inventory.setItem(1, new ItemStack(Items.COAL, 2));
            inventory.setItem(2, new ItemStack(Items.BUCKET));
            var dry = CraftRequest.one(CraftingRecipes.DRY_SPONGE);
            var collect = CraftRequest.one(CraftingRecipes.DRY_SPONGE_WITH_WATER);
            helper.assertValueEqual(CraftingService.create(player, dry, false, budget()).code(), CraftingResultCode.CREATED,
                    "Plain drying refused wet sponge");
            helper.assertValueEqual(inventory.countItem(Items.BUCKET), 1, "Default drying consumed the optional bucket");
            helper.assertValueEqual(inventory.countItem(Items.WATER_BUCKET), 0, "Default drying selected water collection");

            helper.assertValueEqual(CraftingService.create(player, collect, false, budget()).code(), CraftingResultCode.CREATED,
                    "An empty bucket with ordinary fuel did not collect water");
            helper.assertValueEqual(inventory.countItem(Items.BUCKET), 0, "Water collection did not debit the empty bucket");
            helper.assertValueEqual(inventory.countItem(Items.WATER_BUCKET), 1, "Water collection did not deliver a bucket");
            inventory.setItem(inventory.getFreeSlot(), new ItemStack(Items.LAVA_BUCKET));
            helper.assertValueEqual(CraftingService.create(player, collect, false, budget()).code(), CraftingResultCode.CREATED,
                    "A single lava bucket did not both heat and collect water");
            helper.assertValueEqual(inventory.countItem(Items.LAVA_BUCKET), 0, "Collector did not debit the lava fuel");
            helper.assertValueEqual(inventory.countItem(Items.BUCKET), 0, "One fuel bucket returned both an empty and a water bucket");
            helper.assertValueEqual(inventory.countItem(Items.WATER_BUCKET), 2, "Both collector routes should yield one bucket");
            helper.assertValueEqual(inventory.countItem(Items.SPONGE), 3, "Drying and collecting did not deliver exactly three sponges");
            helper.assertValueEqual(inventory.countItem(Items.WET_SPONGE), 0, "Residual sponge input remained");
        } finally {
            helper.setBlock(station, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    private static SearchBudget budget() {
        return new SearchBudget(() -> 0, 8_000_000L, SearchBudget.MAX_STATES);
    }
}
