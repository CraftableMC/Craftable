package org.craftablemc.craftable.client.recipebook;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.config.CraftableClientConfig;

/** 中文：客户端线程上对原版已同步配方目录的受限投影；影响可见性，不解锁配方或授予执行权限。
 * 恢复原版时暂时禁用自身投影，避免重入集合计算再次写回增强状态。
 * English: A client-thread scoped projection of vanilla's synchronized catalog, affecting visibility without unlocking recipes or execution.
 * Vanilla restoration temporarily disables this projection so reentrant collection updates cannot restore enhanced state. */
public final class RecipeBookProjection {
    private static boolean restoring;
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
        return recipe.value() instanceof CraftingRecipe && !recipe.value().isSpecial()
                && !recipe.value().isIncomplete() && recipe.value().canCraftInDimensions(3, 3)
                && (!CraftableClientConfig.unlockedOnly() || mc.player.getRecipeBook().contains(recipe));
    }
    public static List<RecipeHolder<?>> candidates(RecipeCollection collection) {
        return collection.getRecipes().stream().filter(RecipeBookProjection::visible).toList();
    }
    public static List<RecipeCollection> scope(RecipeBookComponent component) {
        var mc = Minecraft.getInstance();
        var access = (RecipeBookComponentAccessor) component;
        var tab = access.craftable$getSelectedTab();
        if (tab == null) return List.of();
        var list = mc.player.getRecipeBook().getCollection(tab.getCategory());
        var search = access.craftable$getSearchBox();
        if (search == null || search.getValue().isEmpty()) return list;
        var matches = mc.getConnection().searchTrees().recipes().search(search.getValue().toLowerCase(Locale.ROOT));
        return list.stream().filter(matches::contains).toList();
    }
}
