package org.craftablemc.craftable.client;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.craftablemc.craftable.recipe.FurnaceRecipe;

/** 只投影已知原版布局，配方数据不授予执行权限。
 * Project only known vanilla layouts. Recipe data does not grant authority to execute. */
record RecipeCardPattern(int gridSize, int sourceWidth, List<Ingredient> ingredients,
        boolean shapeless, @Nullable FurnaceRecipe cooking) {
    @Nullable
    static RecipeCardPattern of(@Nullable Recipe<?> recipe, int gridSize) {
        if (recipe == null || gridSize < 1 || gridSize > 3) return null;
        if (gridSize == 1) {
            var cooking = FurnaceRecipe.of(recipe);
            if (cooking == null) return null;
            var inputs = recipe.getIngredients();
            return inputs.size() != 1 || inputs.getFirst().isEmpty()
                    ? null : new RecipeCardPattern(1, 1, inputs, false, cooking);
        }
        if (recipe.getClass() == ShapedRecipe.class) {
            var shaped = (ShapedRecipe) recipe;
            int width = shaped.getWidth(), height = shaped.getHeight();
            var inputs = shaped.getIngredients();
            if (width < 1 || width > gridSize || height < 1 || height > gridSize || inputs.size() != width * height)
                return null;
            return new RecipeCardPattern(gridSize, width, inputs, false, null);
        }
        if (recipe.getClass() == ShapelessRecipe.class) {
            var inputs = recipe.getIngredients();
            if (!inputs.isEmpty() && inputs.size() <= gridSize * gridSize)
                return new RecipeCardPattern(gridSize, gridSize, inputs, true, null);
        }
        return null;
    }

    Ingredient ingredient(int column, int row) {
        int index = row * sourceWidth + column;
        return column < sourceWidth && index < ingredients.size() ? ingredients.get(index) : Ingredient.EMPTY;
    }
}
