package org.craftablemc.craftable.recipe;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 原版加工配方的冻结计量。炉类燃料的活值仍由服务端在扣料前重验。
 * Frozen vanilla cooking quantities. The server still checks live furnace fuel values before debiting. */
public record FurnaceRecipe(WorkstationCapability station, int ticks, float experience) {
    public static FurnaceRecipe of(Recipe<?> recipe) {
        if (recipe instanceof VanillaBrewingRecipe)
            return new FurnaceRecipe(WorkstationCapability.BREWING_STAND, VanillaBrewingRecipe.TICKS_PER_BATCH, 0);
        WorkstationCapability station;
        if (recipe.getClass() == SmeltingRecipe.class) station = WorkstationCapability.FURNACE;
        else if (recipe.getClass() == BlastingRecipe.class) station = WorkstationCapability.BLAST_FURNACE;
        else if (recipe.getClass() == SmokingRecipe.class) station = WorkstationCapability.SMOKER;
        else if (recipe.getClass() == CampfireCookingRecipe.class) station = WorkstationCapability.CAMPFIRE;
        else return null;
        var cooking = (AbstractCookingRecipe) recipe;
        int ticks = cooking.getCookingTime();
        float xp = station == WorkstationCapability.CAMPFIRE ? 0 : cooking.getExperience();
        return ticks > 0 && ticks <= 32_000 && Float.isFinite(xp) && xp >= 0 && xp <= 100
                ? new FurnaceRecipe(station, ticks, xp) : null;
    }

    /** 点燃的篝火本身提供热量。 / A lit campfire supplies its own heat. */
    public boolean requiresFuel() { return station != WorkstationCapability.CAMPFIRE; }

    int burnTicks(ItemStack stack) {
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")
                || CraftingRecipes.protectedStack(stack)) return 0;
        if (station == WorkstationCapability.BREWING_STAND)
            return stack.is(Items.BLAZE_POWDER) ? ticks * VanillaBrewingRecipe.FUEL_BATCHES : 0;
        RecipeType<?> type = switch (station) {
            case FURNACE -> RecipeType.SMELTING;
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
            default -> throw new IllegalStateException("Not a furnace");
        };
        int ticks = stack.getBurnTime(type);
        return station == WorkstationCapability.FURNACE ? ticks : ticks / 2;
    }

    /** 每代每炉型只收集一次默认组件燃料和返还物。 / Freeze default-stack fuel once per type and generation. */
    FuelTable fuels() {
        if (station == WorkstationCapability.BREWING_STAND)
            return new FuelTable(Ingredient.of(Items.BLAZE_POWDER),
                    Map.of(Items.BLAZE_POWDER, new Fuel(ticks * VanillaBrewingRecipe.FUEL_BATCHES, ItemStack.EMPTY)), java.util.List.of());
        var fuels = new HashMap<Item, Fuel>();
        for (var id : BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.FURNACE_FUELS).keySet()) {
            Item item = BuiltInRegistries.ITEM.get(id.location());
            ItemStack stack = new ItemStack(item);
            int ticks = burnTicks(stack);
            if (ticks > 0 && ticks <= 32_000) fuels.put(item, new Fuel(ticks, stack.getCraftingRemainingItem().copy()));
        }
        var returning = fuels.entrySet().stream().filter(entry -> !entry.getValue().remainder().isEmpty()).toList();
        return new FuelTable(Ingredient.of(fuels.keySet().stream().map(ItemStack::new)), Map.copyOf(fuels), returning);
    }

    record Fuel(int ticks, ItemStack remainder) {}
    record FuelTable(Ingredient options, Map<Item, Fuel> values, java.util.List<Map.Entry<Item, Fuel>> returning) {}
}
