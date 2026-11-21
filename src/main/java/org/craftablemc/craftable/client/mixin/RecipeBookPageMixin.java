package org.craftablemc.craftable.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.craftablemc.craftable.client.recipebook.RecipeButtonTargetResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookPage.class)
abstract class RecipeBookPageMixin {
    @Shadow public abstract void setInvisible();

    // 增强模式接手时，原版已打开的小窗也不能继续收鼠标或盖在详情上。
    // An open vanilla popup must stop drawing and receiving clicks when enhancements take over.
    @Inject(method = "render", at = @At("HEAD"))
    private void craftable$hideVanillaPopup(CallbackInfo ci) {
        if (RecipeBookProjection.active()) setInvisible();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void craftable$clearVanillaPopupBeforeInput(CallbackInfoReturnable<Boolean> ci) {
        if (RecipeBookProjection.active()) setInvisible();
    }

    @Redirect(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeButton;isOnlyOption()Z"))
    private boolean craftable$skipVanillaAlternatives(RecipeButton button) {
        return RecipeBookProjection.active() || button.isOnlyOption();
    }

    @Redirect(method = "mouseClicked", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeButton;getRecipe()Lnet/minecraft/world/item/crafting/RecipeHolder;"))
    private RecipeHolder<?> craftable$mergedButtonTarget(RecipeButton button) {
        // 合并按钮的普通左键与该产物的颜色、C 键采用同一选择。
        // A merged button's direct left click uses the same route as its color and C.
        if (RecipeBookProjection.active() && RecipeBookProjection.isMergedCookingGroup(button.getCollection()))
            return RecipeButtonTargetResolver.preferredRecipe(button);
        return button.getRecipe();
    }
}
