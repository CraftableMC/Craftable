package org.craftablemc.craftable.recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.craftablemc.craftable.menu.mixin.SmithingTransformRecipeAccessor;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 切石和原版装备升级的定值图样。玩家自选的纹饰和受保护装备由原版菜单处理。
 * Frozen patterns for stonecutting and vanilla equipment upgrades. Decorative trims and protected gear use the native menu. */
public final class NativeStationRecipe {
    private NativeStationRecipe() {}

    @Nullable
    public static WorkstationCapability station(Recipe<?> recipe) {
        if (recipe.getClass() == StonecutterRecipe.class) return WorkstationCapability.STONECUTTER;
        if (recipe.getClass() == SmithingTransformRecipe.class) return WorkstationCapability.SMITHING_TABLE;
        return null;
    }

    /** 槽顺序与原版 SmithingRecipeInput 一致。 / Preserve the native SmithingRecipeInput slot order. */
    @Nullable
    public static List<Ingredient> ingredients(Recipe<?> recipe) {
        if (recipe.getClass() == StonecutterRecipe.class) return List.copyOf(recipe.getIngredients());
        if (recipe.getClass() == SmithingTransformRecipe.class) {
            var slots = (SmithingTransformRecipeAccessor) recipe;
            return List.of(slots.craftable$template(), slots.craftable$base(), slots.craftable$addition());
        }
        return null;
    }

    @Nullable
    static List<CraftingRecipes.Requirement> requirements(Recipe<?> recipe) {
        var inputs = ingredients(recipe);
        if (inputs == null || inputs.isEmpty() || recipe.isIncomplete()) return null;
        var result = new ArrayList<CraftingRecipes.Requirement>(inputs.size());
        for (int slot = 0; slot < inputs.size(); slot++) {
            var ingredient = inputs.get(slot);
            if (ingredient.isEmpty() || CraftingRecipes.key(ingredient) == null) return null;
            var options = ingredient.getItems();
            if (options.length == 0 || Arrays.stream(options).anyMatch(stack ->
                    !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft"))) return null;
            result.add(new CraftingRecipes.Requirement(slot, Ingredient.of(Arrays.stream(options).map(ItemStack::copy))));
        }
        return List.copyOf(result);
    }

    /** 原版升级只迁移基材组件补丁，未修改的基材具有空补丁。
     * Native upgrades transfer the base component patch. Unmodified base equipment has an empty patch. */
    static ItemStack liveOutput(Recipe<?> recipe, List<ItemStack> inputs, ServerPlayer player) {
        if (inputs.stream().anyMatch(s -> s.getCount() != 1 || CraftingRecipes.protectedStack(s))) return ItemStack.EMPTY;
        var level = player.serverLevel();
        ItemStack output;
        if (recipe.getClass() == StonecutterRecipe.class && inputs.size() == 1) {
            var stonecutting = (StonecutterRecipe) recipe;
            var input = new SingleRecipeInput(inputs.getFirst().copy());
            output = stonecutting.matches(input, level) ? stonecutting.assemble(input, player.registryAccess()) : ItemStack.EMPTY;
        } else if (recipe.getClass() == SmithingTransformRecipe.class && inputs.size() == 3) {
            var smithing = (SmithingTransformRecipe) recipe;
            var input = new SmithingRecipeInput(inputs.get(0).copy(), inputs.get(1).copy(), inputs.get(2).copy());
            output = smithing.matches(input, level) ? smithing.assemble(input, player.registryAccess()) : ItemStack.EMPTY;
        } else return ItemStack.EMPTY;
        return output.isItemEnabled(level.enabledFeatures()) ? output : ItemStack.EMPTY;
    }
}
