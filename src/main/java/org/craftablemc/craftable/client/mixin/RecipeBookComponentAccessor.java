package org.craftablemc.craftable.client.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 中文：只读取原版 UI 的选中页签/页面/搜索框，状态仍归原组件拥有，不能从其可见性推断执行权。
 * English: Reads vanilla UI tab/page/search state owned by its component; visibility conveys no execution authority. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
    @Accessor("selectedTab")
    net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton craftable$getSelectedTab();
    @Accessor("recipeBookPage")
    RecipeBookPage craftable$getRecipeBookPage();

    @Accessor("searchBox")
    @Nullable
    EditBox craftable$getSearchBox();
}
