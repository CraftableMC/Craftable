package org.craftablemc.craftable.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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

/** 经实际服务端搜索和提交验证切石产量、锻造槽位和受保护装备边界。
 * Exercise stonecutting yield, smithing inputs and protected gear through server planning and commit. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeStationGameTests {
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "native_station")
    public static void stonecuttingUsesItsOwnStationAndPaysOneInput(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        var cut = CraftRequest.one(ResourceLocation.withDefaultNamespace("cobblestone_slab_from_cobblestone_stonecutting"));
        try {
            inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            helper.setBlock(station, Blocks.CRAFTING_TABLE);
            helper.assertValueEqual(CraftingService.create(player, cut, false, budget()).code(),
                    CraftingResultCode.MISSING_WORKSTATION, "Crafting table admitted a stonecutting route");
            helper.assertValueEqual(inventory.countItem(Items.COBBLESTONE), 2, "Rejected cutting consumed cobblestone");

            helper.setBlock(station, Blocks.STONECUTTER);
            var result = CraftingService.create(player, cut, false, budget());
            helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "Nearby stonecutter did not cut cobblestone");
            helper.assertValueEqual(inventory.countItem(Items.COBBLESTONE), 1, "One stonecutting batch used the wrong input count");
            helper.assertValueEqual(inventory.countItem(Items.COBBLESTONE_SLAB), 2, "The stonecutter yielded the wrong number of slabs");
        } finally {
            helper.setBlock(station, Blocks.AIR);
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty", batch = "native_station")
    public static void smithingPaysThreeInputsAndLeavesPersonalizedGearIntact(GameTestHelper helper) {
        var station = new BlockPos(2, 1, 1);
        var player = PlanningTestSupport.player(helper);
        var inventory = player.getInventory();
        var smith = CraftRequest.one(ResourceLocation.withDefaultNamespace("netherite_sword_smithing"));
        try {
            helper.setBlock(station, Blocks.SMITHING_TABLE);
            var personalized = new ItemStack(Items.DIAMOND_SWORD);
            personalized.set(DataComponents.CUSTOM_NAME, Component.literal("Personalized"));
            personalized.setDamageValue(1);
            inventory.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            inventory.setItem(1, personalized.copy());
            inventory.setItem(2, new ItemStack(Items.NETHERITE_INGOT));
            // 即使原版锻造保留组件，递归搜索也不能擅自把唯一的定制装备作为原料。
            // Native smithing preserves components, but an automatic chain must still leave unique gear alone.
            var rejected = CraftingService.create(player, smith, false, budget());
            helper.assertTrue(rejected.code() != CraftingResultCode.CREATED
                    && ItemStack.matches(inventory.getItem(1), personalized), "Automatic smithing consumed protected gear");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 1, "Rejected smithing took its template");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_INGOT), 1, "Rejected smithing took its ingot");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_SWORD), 0, "Rejected smithing published a sword");

            inventory.setItem(3, new ItemStack(Items.DIAMOND_SWORD));
            helper.assertValueEqual(CraftingService.create(player, smith, false, budget()).code(),
                    CraftingResultCode.CREATED, "Unmodified diamond sword could not be upgraded");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_SWORD), 1, "Expected exactly one upgraded sword");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 0, "Upgrade did not consume the template");
            helper.assertValueEqual(inventory.countItem(Items.NETHERITE_INGOT), 0, "Upgrade did not consume the ingot");
            helper.assertValueEqual(inventory.countItem(Items.DIAMOND_SWORD), 1, "Upgrade used the wrong base count");
            helper.assertTrue(ItemStack.matches(inventory.getItem(1), personalized), "Unmodified upgrade changed the personalized sword");
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
