package org.craftablemc.craftable.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.SearchBudget;

/** 用真实酿造台与配方验证组件、跨步燃料及瓶子返还。
 * Verify component-sensitive transitions, shared fuel and bottle remainders through a real brewing stand. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BrewingChainGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "brewing_chain")
    public static void plainWaterBrewsIntoSwiftnessWithoutUsingCustomPotions(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        var request = recipe("potion/awkward/sugar");
        var unnamedWater = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        var customAwkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        customAwkward.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this potion"));
        var wrongPotion = PotionContents.createItemStack(Items.POTION, Potions.MUNDANE);
        try {
            inventory.setItem(0, unnamedWater.copy());
            inventory.setItem(1, customAwkward.copy());
            inventory.setItem(2, wrongPotion.copy());
            inventory.setItem(3, new ItemStack(Items.NETHER_WART));
            inventory.setItem(4, new ItemStack(Items.SUGAR));

            helper.assertValueEqual(CraftingService.create(player, request, false, budget()).code(),
                    CraftingResultCode.MISSING_WORKSTATION, "Air supplied brewing capability");
            helper.setBlock(station, Blocks.BREWING_STAND);
            helper.assertTrue(CraftingService.create(player, request, false, budget()).code() != CraftingResultCode.CREATED,
                    "Brewing succeeded without fuel");
            helper.assertTrue(ItemStack.matches(inventory.getItem(0), unnamedWater)
                    && inventory.countItem(Items.NETHER_WART) == 1 && inventory.countItem(Items.SUGAR) == 1,
                    "Rejected brewing debited inputs");

            inventory.setItem(5, new ItemStack(Items.BLAZE_POWDER));
            var result = CraftingService.create(player, request, false, budget());
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "Canonical brewing chain failed");
            helper.assertValueEqual(result.plan().steps().size(), 2, "Brewing skipped the awkward potion transition");
            helper.assertTrue(ItemStack.matches(inventory.getItem(1), customAwkward)
                    && ItemStack.matches(inventory.getItem(2), wrongPotion), "Brewing consumed a custom potion or the wrong effect");
            helper.assertTrue(inventory.items.stream().anyMatch(stack -> ItemStack.matches(stack,
                    PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS))), "Brewing did not deliver the exact Swiftness potion");
            helper.assertValueEqual(inventory.countItem(Items.NETHER_WART), 0, "Intermediate brewing did not pay its reagent");
            helper.assertValueEqual(inventory.countItem(Items.SUGAR), 0, "Final brewing did not pay its reagent");
            helper.assertValueEqual(inventory.countItem(Items.BLAZE_POWDER), 0, "Two brewing steps did not share one paid fuel item");
            helper.assertTrue(((BrewingStandBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(station))).isEmpty(),
                    "Virtual brewing used a physical slot");

            // 手选中间做法仍要匹配药水效果，不能退化成只匹配药水瓶物品 ID。
            // Pinning an intermediate route must retain the exact potion effect, not just the bottle item ID.
            inventory.clearContent();
            inventory.setItem(0, unnamedWater.copy());
            inventory.setItem(1, new ItemStack(Items.NETHER_WART));
            inventory.setItem(2, new ItemStack(Items.SUGAR));
            inventory.setItem(3, new ItemStack(Items.BLAZE_POWDER));
            var awkward = recipe("potion/water/nether_wart").recipe();
            var pinned = new CraftRequest(request.recipe(), 1, false, request.allowDrops(), request.policy(),
                    java.util.Map.of("0.0", awkward));
            var pinnedResult = CraftingService.create(player, pinned, false, budget());
            helper.assertValueEqual(pinnedResult.code(), CraftingResultCode.CREATED, "A pinned exact brewing route could not supply its potion");
            helper.assertValueEqual(pinnedResult.plan().steps().getFirst().recipe().id(), awkward, "Brewing ignored the pinned producer");
        } finally {
            helper.setBlock(station, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "brewing_chain")
    public static void lingeringConversionReturnsTheDragonBreathBottle(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        var limited = player.serverLevel().getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING);
        boolean original = limited.get();
        try {
            helper.setBlock(station, Blocks.BREWING_STAND);
            inventory.setItem(0, PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING));
            inventory.setItem(1, new ItemStack(Items.DRAGON_BREATH));
            inventory.setItem(2, new ItemStack(Items.BLAZE_POWDER));
            inventory.setItem(3, new ItemStack(Items.OAK_PLANKS, 2));
            limited.set(true, player.getServer());
            // 原版酿造没有配方书解锁，但相邻的普通合成仍受世界规则约束。
            // Vanilla brewing has no book unlock, while ordinary crafting still follows the world rule.
            helper.assertValueEqual(CraftingService.create(player, CraftRequest.one(ResourceLocation.withDefaultNamespace("stick")),
                    false, budget()).code(), CraftingResultCode.RECIPE_LOCKED, "Brewing bypass lifted an ordinary recipe lock");
            var result = CraftingService.create(player, recipe("splash_potion/healing/dragon_breath"), false, budget());
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "Lingering conversion failed");
            helper.assertValueEqual(inventory.countItem(Items.SPLASH_POTION), 0, "Lingering conversion left the consumed splash potion");
            helper.assertValueEqual(inventory.countItem(Items.DRAGON_BREATH), 0, "Lingering conversion did not pay dragon breath");
            helper.assertValueEqual(inventory.countItem(Items.GLASS_BOTTLE), 1, "Reagent bottle was lost or duplicated");
            helper.assertTrue(inventory.items.stream().anyMatch(stack -> ItemStack.matches(stack,
                    PotionContents.createItemStack(Items.LINGERING_POTION, Potions.HEALING))), "Lingering conversion changed the effect");
        } finally {
            limited.set(original, player.getServer());
            helper.setBlock(station, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    private static CraftRequest recipe(String path) {
        return CraftRequest.one(ResourceLocation.fromNamespaceAndPath(Craftable.MOD_ID, "brewing/" + path));
    }

    private static SearchBudget budget() {
        return new SearchBudget(() -> 0, 8_000_000L, SearchBudget.MAX_STATES);
    }
}
