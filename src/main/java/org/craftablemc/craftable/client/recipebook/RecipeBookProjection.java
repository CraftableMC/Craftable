package org.craftablemc.craftable.client.recipebook;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.recipe.FurnaceRecipe;

/** 中文：客户端线程上对原版已同步配方目录的受限投影；影响可见性，不解锁配方或授予执行权限。
 * 恢复原版时暂时禁用自身投影，避免重入集合计算再次写回增强状态。
 * English: A client-thread scoped projection of vanilla's synchronized catalog, affecting visibility without unlocking recipes or execution.
 * Vanilla restoration temporarily disables this projection so reentrant collection updates cannot restore enhanced state. */
public final class RecipeBookProjection {
    private static boolean restoring;
    private static List<RecipeCollection> catalog = List.of();
    private static final Map<RecipeBookCategories, TabProjection> tabs = new EnumMap<>(RecipeBookCategories.class);

    private RecipeBookProjection() {}
    public static RecipeBookComponent component(Screen screen) {
        if (screen instanceof org.craftablemc.craftable.client.menu.AmbientInventoryScreen ambient) return ambient.getRecipeBookComponent();
        if (screen instanceof InventoryScreen inventory) return inventory.getRecipeBookComponent();
        if (screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen crafting) return crafting.getRecipeBookComponent();
        return null;
    }
    public static boolean active() {
        return !restoring && modeAllowed() && CraftableClientConfig.recipeBookEnhancementsEnabled()
                && component(Minecraft.getInstance().screen) != null;
    }
    public static boolean modeAllowed() {
        var mode = Minecraft.getInstance().gameMode;
        return mode != null && org.craftablemc.craftable.api.CraftableModePolicy.allows(mode.getPlayerMode());
    }
    public static void restoreVanilla() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        restoring = true;
        try {
            var contents = new net.minecraft.world.entity.player.StackedContents();
            mc.player.getInventory().fillStackedContents(contents);
            for (var collection : mc.player.getRecipeBook().getCollections()) {
                collection.canCraft(contents, 2, 2, mc.player.getRecipeBook());
            }
        } finally { restoring = false; }
    }
    public static boolean visible(RecipeHolder<?> recipe) {
        var mc = Minecraft.getInstance();
        return (recipe.value() instanceof CraftingRecipe || FurnaceRecipe.of(recipe.value()) != null) && !recipe.value().isSpecial()
                && !recipe.value().isIncomplete() && recipe.value().canCraftInDimensions(3, 3)
                && (!CraftableClientConfig.unlockedOnly() || mc.player.getRecipeBook().contains(recipe));
    }

    /** 合成页只合并相同物品及组件的加工产物。集合身份与原版目录一起更新，材料状态变动不重建按钮。
     * Crafting tabs merge cooking results by item and components. Collection identities change with the vanilla catalog, independent of material status. */
    public static List<RecipeCollection> collections(ClientRecipeBook book, RecipeBookCategories category) {
        return active() ? tabProjection(book, category).collections() : book.getCollection(category);
    }

    public static List<RecipeCollection> searchMatches(ClientRecipeBook book, RecipeBookCategories category,
            List<RecipeCollection> matches) {
        if (!active()) return book.getCollection(category).stream().filter(matches::contains).toList();
        var projection = tabProjection(book, category);
        var vanillaMatches = new HashSet<>(matches);
        return projection.collections().stream().filter(collection -> {
            var sources = projection.searchSources().get(collection);
            return sources == null ? vanillaMatches.contains(collection) : sources.stream().anyMatch(vanillaMatches::contains);
        }).toList();
    }

    public static void clearCatalog() {
        catalog = List.of();
        tabs.clear();
    }

    public static boolean isMergedCookingGroup(RecipeCollection collection) {
        for (var tab : tabs.values()) {
            var sources = tab.searchSources().get(collection);
            if (sources != null && sources.size() > 1) return true;
        }
        return false;
    }

    private static TabProjection tabProjection(ClientRecipeBook book, RecipeBookCategories category) {
        if (catalog != book.getCollections()) {
            catalog = book.getCollections();
            tabs.clear();
        }
        return tabs.computeIfAbsent(category, key -> buildTab(book, key));
    }

    private static TabProjection buildTab(ClientRecipeBook book, RecipeBookCategories category) {
        var original = book.getCollection(category);
        List<RecipeBookCategories> cooking = switch (category) {
            case CRAFTING_SEARCH -> List.of(RecipeBookCategories.FURNACE_SEARCH,
                    RecipeBookCategories.BLAST_FURNACE_SEARCH, RecipeBookCategories.SMOKER_SEARCH, RecipeBookCategories.CAMPFIRE);
            case CRAFTING_BUILDING_BLOCKS -> List.of(RecipeBookCategories.FURNACE_BLOCKS, RecipeBookCategories.BLAST_FURNACE_BLOCKS);
            case CRAFTING_MISC -> List.of(RecipeBookCategories.FURNACE_FOOD, RecipeBookCategories.FURNACE_MISC,
                    RecipeBookCategories.BLAST_FURNACE_MISC, RecipeBookCategories.SMOKER_FOOD, RecipeBookCategories.CAMPFIRE);
            default -> List.of();
        };
        if (cooking.isEmpty()) return new TabProjection(original, Map.of());

        var cookingCollections = cooking.stream().flatMap(source -> book.getCollection(source).stream()).toList();
        var outputs = new HashMap<RecipeHolder<?>, Output>();
        for (var collection : cookingCollections) for (var recipe : collection.getRecipes()) {
            if (FurnaceRecipe.of(recipe.value()) != null) {
                var result = recipe.value().getResultItem(collection.registryAccess());
                if (!result.isEmpty()) outputs.put(recipe, Output.of(result));
            }
        }
        if (outputs.isEmpty()) return new TabProjection(original, Map.of());

        var cookingOutputs = new HashSet<>(outputs.values());
        var firstGroupForOutput = new HashMap<Output, Group>();
        var sources = new LinkedHashSet<>(original);
        sources.addAll(cookingCollections);
        var groups = new ArrayList<Group>(sources.size());
        // 优先保留合成配方所在的原版分组和顺序。每个同产物路线仍保有自己的配方 ID。
        // Prefer the native crafting group and position. Every route still has its own recipe ID.
        for (var source : sources) {
            var group = new Group(source);
            groups.add(group);
            for (var recipe : source.getRecipes()) {
                Output output = outputs.get(recipe);
                if (output == null && recipe.value() instanceof CraftingRecipe)
                    output = Output.of(recipe.value().getResultItem(source.registryAccess()));
                var destination = cookingOutputs.contains(output)
                        ? firstGroupForOutput.computeIfAbsent(output, ignored -> group) : group;
                destination.recipes.add(recipe);
                destination.sources.add(source);
            }
        }
        var result = new ArrayList<RecipeCollection>(groups.size());
        var searchSources = new HashMap<RecipeCollection, List<RecipeCollection>>();
        for (var group : groups) {
            if (group.recipes.isEmpty()) continue;
            if (group.recipes.equals(group.original.getRecipes())) {
                result.add(group.original);
                continue;
            }
            var merged = new RecipeCollection(group.original.registryAccess(), group.recipes);
            result.add(merged);
            // 原版搜索树仅索引同步集合。合并后仍用原集合做搜索，无需另外索引翻译与提示。
            // Vanilla indexes only synchronized collections. Keep the source aliases for localized name and tooltip search.
            searchSources.put(merged, List.copyOf(group.sources));
        }
        return new TabProjection(List.copyOf(result), Map.copyOf(searchSources));
    }

    public static List<RecipeHolder<?>> candidates(RecipeCollection collection) {
        return collection.getRecipes().stream().filter(RecipeBookProjection::visible).toList();
    }
    public static List<RecipeCollection> scope(RecipeBookComponent component) {
        var mc = Minecraft.getInstance();
        var access = (RecipeBookComponentAccessor) component;
        var tab = access.craftable$getSelectedTab();
        if (tab == null) return List.of();
        var book = mc.player.getRecipeBook();
        var search = access.craftable$getSearchBox();
        if (search == null || search.getValue().isEmpty()) return collections(book, tab.getCategory());
        var matches = mc.getConnection().searchTrees().recipes().search(search.getValue().toLowerCase(Locale.ROOT));
        return searchMatches(book, tab.getCategory(), matches);
    }

    private record Output(Item item, DataComponentPatch components) {
        static Output of(ItemStack stack) { return new Output(stack.getItem(), stack.getComponentsPatch()); }
    }

    private record TabProjection(List<RecipeCollection> collections, Map<RecipeCollection, List<RecipeCollection>> searchSources) {}

    private static final class Group {
        private final RecipeCollection original;
        private final List<RecipeHolder<?>> recipes = new ArrayList<>();
        private final LinkedHashSet<RecipeCollection> sources = new LinkedHashSet<>();

        private Group(RecipeCollection original) { this.original = original; }
    }
}
