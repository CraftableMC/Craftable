package org.craftablemc.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.ItemStack;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.api.CraftingResultCode;

/**
 * 中文：只在物品与组件完全相同的输出组内选择较优路线；不同输出保持原版当前展示选择。
 * 产量不同可视为路线差异，但按钮动画不能暗中切换船等物品的材质，显示结论不构成执行授权。
 * English: Choose a better route only among outputs with the same item and components; different outputs keep vanilla's displayed choice.
 * Yield differences may be route alternatives, but animation cannot switch materials such as boats, and display verdicts grant no authority.
 */
public final class RecipeButtonTargetResolver {
    private RecipeButtonTargetResolver() {}

    static List<RecipeHolder<?>> candidates(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = new ArrayList<>();
        candidates.addAll(collection.getDisplayRecipes(true));
        candidates.addAll(collection.getDisplayRecipes(false));
        if (candidates.isEmpty()) {
            candidates.addAll(RecipeBookProjection.candidates(collection));
        }
        return List.copyOf(candidates);
    }

    public static CraftingStatus status(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<CraftingStatus> statuses = new ArrayList<>();
        for (RecipeHolder<?> candidate : equivalentCandidates(button)) {
            statuses.add(ClientRecipeStatusStore.display(
                    candidate.id(), collection.isCraftable(candidate)));
        }
        return strongestStatus(statuses);
    }

    /**
     * 只查看本帧实际轮播的配方；筛选隐藏项、同输出替代路线和陈旧成功不能制造新的粉色提示。
     * Inspect only this frame's actual rotation; hidden recipes, equivalent routes and stale successes cannot create a pink hint.
     * 当前颜色仍可保留旧显示，刷新由既有生命周期标记说明；此方法不求解、不改状态或选择目标。
     * Existing colors may retain display history with the lifecycle badge; this method neither solves, mutates state nor selects a new target.
     */
    public static RecipeButtonVisualState visualState(RecipeButton button, List<RecipeHolder<?>> rotatingRecipes) {
        var target = preferredRecipe(button);
        if (target == null || !ClientRecipeStatusStore.hasDisplay(target.id())
                || ClientRecipeStatusStore.displayReason(target.id()) == CraftingResultCode.SEARCH_BUDGET_EXCEEDED) {
            return RecipeButtonVisualState.UNKNOWN;
        }
        var current = status(button);
        boolean otherVariantCraftable = false;
        if (current == CraftingStatus.BLOCKED
                && ClientRecipeStatusStore.lifecycle(target.id()) == ClientRecipeStatusStore.Lifecycle.KNOWN) {
            var registries = button.getCollection().registryAccess();
            var output = target.value().getResultItem(registries);
            for (var candidate : rotatingRecipes) {
                if (ClientRecipeStatusStore.hasDisplay(candidate.id())
                        && ClientRecipeStatusStore.lifecycle(candidate.id()) == ClientRecipeStatusStore.Lifecycle.KNOWN
                        && ClientRecipeStatusStore.display(candidate.id(), false) == CraftingStatus.CRAFTABLE
                        && ClientRecipeStatusStore.displayReason(candidate.id()) == CraftingResultCode.CREATED
                        && !ItemStack.isSameItemSameComponents(output, candidate.value().getResultItem(registries))) {
                    otherVariantCraftable = true;
                    break;
                }
            }
        }
        return RecipeButtonVisualState.from(current, true, otherVariantCraftable);
    }

    @org.jetbrains.annotations.Nullable
    public static RecipeHolder<?> preferredRecipe(RecipeButton button) {
        RecipeCollection collection = button.getCollection();
        List<RecipeHolder<?>> candidates = equivalentCandidates(button);
        if (candidates.isEmpty()) return null;
        List<CraftingStatus> statuses = new ArrayList<>(candidates.size());
        List<Boolean> vanillaCraftable = new ArrayList<>(candidates.size());
        List<CraftingResultCode> reasons = new ArrayList<>(candidates.size());
        for (RecipeHolder<?> candidate : candidates) {
            boolean vanillaStatus = collection.isCraftable(candidate);
            vanillaCraftable.add(vanillaStatus);
            statuses.add(ClientRecipeStatusStore.display(candidate.id(), vanillaStatus));
            reasons.add(ClientRecipeStatusStore.reason(candidate.id()));
        }
        // 中文：仅相同物品及组件的结果可替换当前目标；产量差异是路线差异，不是材质替换。
        // Only equal item+components may substitute for the displayed result.
        // Yield differences are legitimate alternative routes, not materials.
        return candidates.get(preferredIndex(statuses, vanillaCraftable, reasons, 0));
    }

    private static List<RecipeHolder<?>> equivalentCandidates(RecipeButton button) {
        var shown = button.getRecipe(); // 中文：既有 Mixin 保护过期索引边界。 English: Existing mixin bounds-checks stale indices.
        if (shown == null) return List.of();
        var registries = button.getCollection().registryAccess();
        var output = shown.value().getResultItem(registries);
        return candidates(button).stream().filter(r -> ItemStack.isSameItemSameComponents(
                output, r.value().getResultItem(registries))).toList();
    }

    /** 中文：仅提供有界 UI 上下文，绝非使用其他输出配方的授权。
     * English: Bounded UI context only; never a grant to use another output recipe. */
    public static List<net.minecraft.resources.ResourceLocation> outputVariants(RecipeButton button) {
        // 中文：保留隐藏/不可用变体供根选择器说明；额外一项仅表示既有 16 张卡片上限的截断。
        // Include hidden/unavailable variants so the root chooser can explain
        // them. The extra entry signals truncation at the existing 16-card cap.
        var shown = button.getRecipe();
        return java.util.stream.Stream.concat(shown == null ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(shown.id()), candidates(button).stream().map(RecipeHolder::id))
                .distinct().limit(17).toList();
    }

    static CraftingStatus strongestStatus(List<CraftingStatus> statuses) {
        CraftingStatus best = CraftingStatus.BLOCKED;
        for (CraftingStatus status : statuses) {
            if (status == CraftingStatus.CRAFTABLE) {
                return status;
            }
            if (status == CraftingStatus.PARTIAL) {
                best = status;
            }
        }
        return best;
    }

    public static ClientRecipeStatusStore.Lifecycle lifecycle(RecipeButton button) {
        var recipes = equivalentCandidates(button);
        var target = preferredRecipe(button);
        if (target == null) return ClientRecipeStatusStore.Lifecycle.UNKNOWN;
        if (ClientRecipeStatusStore.display(target.id(), false) == CraftingStatus.CRAFTABLE) {
            return ClientRecipeStatusStore.lifecycle(target.id());
        }
        boolean unknown = false;
        for (var recipe : recipes) {
            var state = ClientRecipeStatusStore.lifecycle(recipe.id());
            if (state == ClientRecipeStatusStore.Lifecycle.PENDING) return state;
            if (state == ClientRecipeStatusStore.Lifecycle.UNKNOWN) unknown = true;
        }
        return unknown ? ClientRecipeStatusStore.Lifecycle.UNKNOWN : ClientRecipeStatusStore.Lifecycle.KNOWN;
    }

    static int preferredIndex(
            List<CraftingStatus> statuses, List<Boolean> vanillaCraftable, int currentIndex) {
        return preferredIndex(statuses, vanillaCraftable, java.util.Collections.nCopies(statuses.size(), null), currentIndex);
    }

    static int preferredIndex(List<CraftingStatus> statuses, List<Boolean> vanillaCraftable,
            List<CraftingResultCode> reasons, int currentIndex) {
        if (statuses.size() != vanillaCraftable.size() || statuses.size() != reasons.size() || statuses.isEmpty()) {
            throw new IllegalArgumentException("Recipe variant state must be non-empty and aligned");
        }
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index) == CraftingStatus.CRAFTABLE) {
                return index;
            }
        }
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index) == CraftingStatus.PARTIAL) return index;
        }
        // 中文：同输出组内优先展示已有材料但仅投递受阻的路线，胜过未找到材料的替代路线；这不是可合成判定或执行授权。
        // A proven material route blocked only by delivery is more useful
        // than an arbitrary unseeded alternative (bones, not missing bone
        // blocks). This is target selection within the same output group,
        // never a positive craftability verdict or execution authority.
        for (int index = 0; index < reasons.size(); index++) {
            if (reasons.get(index) == CraftingResultCode.NO_OUTPUT_SPACE) return index;
        }
        // 中文：首个服务端回复前尽量保留原版已确认可用的选择，否则保留正在显示的变体。
        // Before the first server response, preserve vanilla's own positive
        // choice where possible; otherwise retain the currently shown variant.
        for (int index = 0; index < vanillaCraftable.size(); index++) {
            if (vanillaCraftable.get(index)) {
                return index;
            }
        }
        return currentIndex >= 0 && currentIndex < statuses.size() ? currentIndex : 0;
    }
}
