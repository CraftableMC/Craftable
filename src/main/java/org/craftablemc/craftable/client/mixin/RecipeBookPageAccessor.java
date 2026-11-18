package org.craftablemc.craftable.client.mixin;

import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 中文：借用原版当前按钮和悬停目标做输入路由，调用者不能把返回列表当作自己的缓存或修改它。
 * English: Borrows vanilla buttons and hover target for input routing; callers must not own, cache as independent state or mutate the returned list. */
@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {
    @Accessor("buttons")
    java.util.List<RecipeButton> craftable$getButtons();
    @Accessor("hoveredButton")
    @Nullable
    RecipeButton craftable$getHoveredButton();
}
