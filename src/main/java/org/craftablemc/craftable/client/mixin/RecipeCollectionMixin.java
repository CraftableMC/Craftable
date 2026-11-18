package org.craftablemc.craftable.client.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 中文：只投影原版配方集合的显示集合，不修改玩家实际解锁集合或合成资源。
 * 退出增强范围时仍让原版重算；声明式注入不能因没有普通调用点而删除。
 * English: Projects only vanilla recipe display sets, never the player's actual unlock set or crafting resources.
 * Vanilla recomputes outside the enhancement scope; declarative injection has no ordinary call-site requirement. */
@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionMixin {
    @Shadow @Final private List<RecipeHolder<?>> recipes;
    @Shadow @Final private Set<RecipeHolder<?>> known;
    @Shadow @Final private Set<RecipeHolder<?>> fitsDimensions;
    @Shadow @Final private Set<RecipeHolder<?>> craftable;

    @Inject(method = "canCraft", at = @At("HEAD"), cancellable = true)
    private void craftable$project(StackedContents contents, int width, int height, RecipeBook book, CallbackInfo ci) {
        // 中文：这里只改显示集合，不改实际解锁集合；换到其他页面后由原版正常重算。
        // These are display sets, never the player's unlocked recipe set. Vanilla
        // will recompute them normally when a different screen is active.
        if (!RecipeBookProjection.active()) {
            known.clear();
            for (var recipe : recipes) if (book.contains(recipe)) known.add(recipe);
            return;
        }
        known.clear();
        fitsDimensions.clear();
        craftable.clear();
        for (var recipe : recipes) {
            if (!RecipeBookProjection.visible(recipe)) continue;
            known.add(recipe);
            fitsDimensions.add(recipe);
            if (ClientRecipeStatusStore.display(recipe.id(), false) == CraftingStatus.CRAFTABLE) craftable.add(recipe);
        }
        ci.cancel();
    }
}
