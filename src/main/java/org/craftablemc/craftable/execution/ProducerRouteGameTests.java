package org.craftablemc.craftable.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.SearchBudget;

/** 真配方反例：可行短路线不应被重复染色或造装备再回收堵住。
 * Native recipe regressions: recoloring and gear recycling must not bury a viable short route. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProducerRouteGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void woolFromStringOnlyNeedsOneDye(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var result = PlanningTestSupport.search(player, "dye_light_blue_wool", 1, false, true,
                    new ItemStack(Items.STRING, 4), new ItemStack(Items.LIGHT_BLUE_DYE),
                    new ItemStack(Items.BLACK_DYE), new ItemStack(Items.BROWN_DYE), new ItemStack(Items.BLUE_DYE),
                    new ItemStack(Items.GREEN_DYE), new ItemStack(Items.RED_DYE), new ItemStack(Items.YELLOW_DYE));
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "short wool route missed");
            var plan = result.plan().orElseThrow();
            helper.assertValueEqual(plan.steps().size(), 2, "a wool input was redyed before coloring it for the target");
            helper.assertTrue(plan.extractions().stream().allMatch(e -> e.expected().is(Items.STRING)
                    || e.expected().is(Items.LIGHT_BLUE_DYE)), "the route charged an unrelated dye");
            var shortage = PlanningTestSupport.search(player, "dye_light_blue_wool", 2, false, true,
                    new ItemStack(Items.WHITE_WOOL), new ItemStack(Items.LIGHT_BLUE_DYE, 2),
                    new ItemStack(Items.BLACK_DYE), new ItemStack(Items.BROWN_DYE), new ItemStack(Items.BLUE_DYE),
                    new ItemStack(Items.GREEN_DYE), new ItemStack(Items.RED_DYE), new ItemStack(Items.YELLOW_DYE));
            helper.assertValueEqual(shortage.code(), CraftingResultCode.MISSING_INGREDIENTS,
                    "one wool cannot satisfy two batches through a recoloring loop");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "producer_routes")
    public static void sevenOreSmeltDirectlyForLeggings(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        var table = new BlockPos(1, 1, 1);
        var furnace = new BlockPos(2, 1, 1);
        try {
            helper.setBlock(table, Blocks.CRAFTING_TABLE);
            helper.setBlock(furnace, Blocks.FURNACE);
            var inventory = player.getInventory();
            inventory.setItem(0, new ItemStack(Items.DEEPSLATE_IRON_ORE, 7));
            inventory.setItem(1, new ItemStack(Items.COAL));
            inventory.setItem(2, new ItemStack(Items.IRON_NUGGET));
            inventory.setItem(3, new ItemStack(Items.STICK, 64));
            var recipe = ResourceLocation.withDefaultNamespace("iron_leggings");
            var result = CraftingService.create(player, CraftRequest.one(recipe), false,
                    new SearchBudget(() -> 0, 8_000_000L, SearchBudget.MAX_STATES));
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "a directly funded smelting route did not finish");
            helper.assertValueEqual(result.plan().steps().size(), 8, "leggings used a recycling detour");
            helper.assertValueEqual(inventory.countItem(Items.IRON_LEGGINGS), 1, "leggings not delivered");
            helper.assertValueEqual(inventory.countItem(Items.DEEPSLATE_IRON_ORE), 0, "ore count was not charged exactly");
            helper.assertValueEqual(inventory.countItem(Items.COAL), 0, "one piece of coal should fuel the route");
            helper.assertValueEqual(inventory.countItem(Items.IRON_NUGGET), 1, "one nugget was treated as a batch of nine");
            helper.assertValueEqual(inventory.countItem(Items.STICK), 64, "the route spent fuel on needless steps");
        } finally {
            helper.setBlock(table, Blocks.AIR);
            helper.setBlock(furnace, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }
}
