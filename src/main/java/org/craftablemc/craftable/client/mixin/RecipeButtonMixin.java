package org.craftablemc.craftable.client.mixin;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.CraftableKeyMappings;
import org.craftablemc.craftable.client.recipebook.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 中文：客户端配方按钮的声明式安全索引及显示接入；重载/退出的一帧内仍需独立保护旧索引。
 * 颜色、符号和说明来自显示状态，不授予执行权限；所有渲染路径均恢复原版绘制颜色。
 * English: Declarative client recipe-button hooks for safe indices and presentation, retaining index protection through reload/teardown frames.
 * Tints, badges and tooltips grant no execution authority; rendering restores vanilla draw color. */
@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin extends AbstractWidget {
    @Unique private static final ResourceLocation craftable$singleBackground =
            ResourceLocation.withDefaultNamespace("recipe_book/slot_craftable");
    @Unique private static final ResourceLocation craftable$manyBackground =
            ResourceLocation.withDefaultNamespace("recipe_book/slot_many_craftable");
    @Shadow private int currentIndex;
    @Shadow private float time;
    @Shadow private List<net.minecraft.world.item.crafting.RecipeHolder<?>> getOrderedRecipes() { throw new AssertionError(); }
    protected RecipeButtonMixin(int x, int y, int width, int height, Component message) { super(x, y, width, height, message); }

    @Inject(method = "init", at = @At("TAIL"))
    private void craftable$rebindIndex(net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
            net.minecraft.client.gui.screens.recipebook.RecipeBookPage page, CallbackInfo ci) {
        if (RecipeBookProjection.active()) currentIndex = 0;
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void craftable$validRender(GuiGraphics gui, int x, int y, float delta, CallbackInfo ci) {
        // 中文：退出增强模式后旧按钮仍可能存活一帧；索引保护不能随投影开关提前消失。
        // Teardown can leave old buttons alive for one frame after the mode
        // gate disables projection. Index safety must outlive that projection.
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.cancel(); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true)
    private void craftable$validSelection(CallbackInfoReturnable<net.minecraft.world.item.crafting.RecipeHolder<?>> ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        var ordered = getOrderedRecipes();
        ci.setReturnValue(ordered.isEmpty() ? null : ordered.get(Math.floorMod(currentIndex, ordered.size())));
    }

    @Inject(method = "getTooltipText", at = @At("HEAD"), cancellable = true)
    private void craftable$validTooltip(CallbackInfoReturnable<List<Component>> ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.setReturnValue(new java.util.ArrayList<>()); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Inject(method = "updateWidgetNarration", at = @At("HEAD"), cancellable = true)
    private void craftable$validNarration(net.minecraft.client.gui.narration.NarrationElementOutput output, CallbackInfo ci) {
        if (RecipeBookProjection.component(Minecraft.getInstance().screen) == null) return;
        int size = getOrderedRecipes().size();
        if (size == 0) { ci.cancel(); return; }
        currentIndex = Math.floorMod(currentIndex, size);
    }

    @Redirect(method = "updateWidgetNarration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent craftable$narrateDetailsGesture(String key) {
        if (key.equals("narration.recipe.usage.more") && RecipeBookProjection.active()) return craftable$detailsHint();
        return Component.translatable(key);
    }

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void craftable$background(GuiGraphics gui, ResourceLocation sprite, int x, int y, int width, int height) {
        if (!RecipeBookProjection.active()) { gui.blitSprite(sprite, x, y, width, height); return; }
        // 中文：原版绘完背景才更新索引；提前解析同一后继帧，使颜色、徽标、提示与 C 目标一致。
        // Vanilla updates its index after the background blit. Resolve the
        // same upcoming frame now so tint, badge, tooltip and C agree.
        var rotatingRecipes = getOrderedRecipes();
        int size = rotatingRecipes.size();
        if (size > 0) currentIndex = Math.floorMod(net.minecraft.util.Mth.floor(time / 30.0F), size);
        var button = (RecipeButton) (Object) this;
        var visual = RecipeButtonTargetResolver.visualState(button, rotatingRecipes);
        // 中文：保留原版单/多配方外形，统一中性底图；颜色只由显式显示状态决定。
        // Keep vanilla's single/multiple-recipe shape with a neutral base; only the explicit visual state determines its color.
        boolean many = sprite.getPath().equals("recipe_book/slot_many_craftable")
                || sprite.getPath().equals("recipe_book/slot_many_uncraftable");
        gui.setColor(visual.red(), visual.green(), visual.blue(), 1F);
        try { gui.blitSprite(many ? craftable$manyBackground : craftable$singleBackground, x, y, width, height); }
        finally { gui.setColor(1F, 1F, 1F, 1F); }
    }

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void craftable$symbol(GuiGraphics gui, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!RecipeBookProjection.active()) return;
        var button = (RecipeButton) (Object) this;
        var lifecycle = RecipeButtonTargetResolver.lifecycle(button);
        var target = RecipeButtonTargetResolver.preferredRecipe(button);
        boolean limited = target != null && ClientRecipeStatusStore.displayReason(target.id())
                == org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED;
        String symbol = limited || lifecycle == ClientRecipeStatusStore.Lifecycle.UNKNOWN ? "?"
                : lifecycle == ClientRecipeStatusStore.Lifecycle.PENDING ? "~"
                : switch (RecipeButtonTargetResolver.status(button)) {
                    case CRAFTABLE -> "+"; case PARTIAL -> "~"; case BLOCKED -> "!";
                };
        // 中文：物品渲染使用正深度，仅晚画不够；徽标须高于物品且低于提示层。
        // Item rendering writes at a positive depth; a later draw alone is not
        // sufficient. Keep the badge above items but below the tooltip layer.
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 250);
        gui.drawString(Minecraft.getInstance().font, symbol, getX() + 18, getY() + 1, 0xFFFFFFFF, true);
        gui.pose().popPose();
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"))
    private void craftable$tooltip(CallbackInfoReturnable<List<Component>> ci) {
        if (!RecipeBookProjection.active()) return;
        // 中文：增强模式的路线入口在详情页，提示沿用玩家当前绑定的制作键。
        // Enhanced route choices live in details. The prompt follows the player's current craft key.
        ci.getReturnValue().removeIf(line -> line.getContents() instanceof TranslatableContents translation
                && translation.getKey().equals("gui.recipebook.moreRecipes"));
        var button = (RecipeButton) (Object) this;
        var lifecycle = RecipeButtonTargetResolver.lifecycle(button);
        var target = RecipeButtonTargetResolver.preferredRecipe(button);
        if (target == null) return;
        boolean limited = ClientRecipeStatusStore.displayReason(target.id())
                == org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED;
        if (limited) {
            ci.getReturnValue().add(Component.translatable("reason.craftable.search_budget_exceeded"));
        } else if (lifecycle != ClientRecipeStatusStore.Lifecycle.UNKNOWN) {
            var status = RecipeButtonTargetResolver.status(button);
            Component label = Component.translatable("status.craftable." + status.name().toLowerCase(java.util.Locale.ROOT));
            ci.getReturnValue().add(status == CraftingStatus.BLOCKED
                    ? Component.translatable("tooltip.craftable.blocked_reason", label, Component.translatable("reason.craftable." +
                        ClientRecipeStatusStore.displayReason(target.id()).name().toLowerCase(java.util.Locale.ROOT)))
                    : label);
        }
        if (lifecycle != ClientRecipeStatusStore.Lifecycle.KNOWN) ci.getReturnValue().add(Component.translatable("tooltip.craftable.pending"));
        if (RecipeButtonTargetResolver.visualState(button, getOrderedRecipes()) == RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE)
            ci.getReturnValue().add(Component.translatable("tooltip.craftable.other_variant_craftable"));
        ci.getReturnValue().add(Component.translatable("tooltip.craftable.create_one",
                CraftableKeyMappings.CREATE_ONE.getTranslatedKeyMessage()));
        ci.getReturnValue().add(craftable$detailsHint());
    }

    @Unique
    private static MutableComponent craftable$detailsHint() {
        return Component.translatable("tooltip.craftable.recipe_details", CraftableKeyMappings.CREATE_ONE.getTranslatedKeyMessage());
    }
}
