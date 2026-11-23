package org.craftablemc.craftable.menu.mixin;

import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原版锻造升级配方的三个槽位定义。 / The three slot definitions of a vanilla smithing upgrade. */
@Mixin(SmithingTransformRecipe.class)
public interface SmithingTransformRecipeAccessor {
    @Accessor("template") Ingredient craftable$template();
    @Accessor("base") Ingredient craftable$base();
    @Accessor("addition") Ingredient craftable$addition();
}
