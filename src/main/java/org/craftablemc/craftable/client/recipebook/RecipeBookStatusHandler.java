package org.craftablemc.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;

/** 中文：客户端渲染事件只汇总 UI 需求并合并显示结果；唯一调度器拥有被动搜索。
 * 不在渲染回调中搜索或按目标发包，避免帧率变成额外预算及网络流量。
 * English: Client render events collect UI demand and merge display results only; the sole scheduler owns passive searches.
 * Rendering neither searches nor sends per-target requests, keeping frame rate from multiplying budgets or traffic. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookStatusHandler {
    private static long lastTick = Long.MIN_VALUE, displayedRevision = -1;
    private static long displayedAt = Long.MIN_VALUE;
    private static Object currentView;
    private RecipeBookStatusHandler() {}

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        var mc = Minecraft.getInstance();
        if (org.craftablemc.craftable.client.CraftingPlanOverlay.active()) return;
        var component = RecipeBookProjection.component(event.getScreen());
        if (!RecipeBookProjection.active() || component == null || mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        if (lastTick == now) return;
        lastTick = now;
        if (currentView != component) { currentView = component; displayedRevision = -1; }
        if (!component.isVisible()) { ClientBrowsePlanner.scope(List.of(), List.of(), List.of()); return; }
        if (displayedRevision != ClientRecipeStatusStore.presentationRevision()
                && (displayedRevision == -1 || now < displayedAt || now - displayedAt >= 4)) {
            // 中文：渲染前统一合并，避免网络回调在原版仍索引动画按钮旧集合时改写集合。
            // Merge results before render, never from a network callback while
            // vanilla is indexing an animated button's previous collection.
            var contents = new net.minecraft.world.entity.player.StackedContents();
            for (var collection : mc.player.getRecipeBook().getCollections())
                collection.canCraft(contents, 3, 3, mc.player.getRecipeBook());
            component.recipesUpdated();
            displayedRevision = ClientRecipeStatusStore.presentationRevision();
            displayedAt = now;
        }
        var page = ((RecipeBookComponentAccessor) component).craftable$getRecipeBookPage();
        var access = (RecipeBookPageAccessor) page;
        List<ResourceLocation> foreground = new ArrayList<>(), hovered = new ArrayList<>();
        if (access.craftable$getHoveredButton() != null && access.craftable$getHoveredButton().visible)
            RecipeButtonTargetResolver.candidates(access.craftable$getHoveredButton()).forEach(r -> hovered.add(r.id()));
        foreground.addAll(hovered);
        access.craftable$getButtons().stream().filter(b -> b.visible).forEach(b ->
                RecipeButtonTargetResolver.candidates(b).forEach(r -> foreground.add(r.id())));
        List<ResourceLocation> scope = mc.player.getRecipeBook().isFiltering(mc.player.containerMenu instanceof
                net.minecraft.world.inventory.RecipeBookMenu<?, ?> menu ? menu : mc.player.inventoryMenu)
                ? RecipeBookProjection.scope(component).stream().flatMap(c -> RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList() : foreground;
        ClientBrowsePlanner.scope(foreground, scope, hovered);
    }

    public static void afterCreate(ResourceLocation recipe) {
        ClientBrowsePlanner.afterCreate(recipe);
    }
    public static void clearRequestState() {
        RecipeBookInputHandler.clearRequestState();
        lastTick = Long.MIN_VALUE; displayedRevision = -1; currentView = null;
        ClientBrowsePlanner.invalidate();
    }
}
