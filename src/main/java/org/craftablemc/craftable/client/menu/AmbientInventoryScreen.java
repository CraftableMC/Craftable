package org.craftablemc.craftable.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/** 中文：在既有合成菜单交互上使用原版背包美术及实体显示；槽位和容器状态仍归父类/服务端菜单拥有。
 * 本类只适配布局与配方书输入，不复制合成提交、容量校验或物品归还逻辑。
 * English: Vanilla inventory artwork/entity rendering over existing crafting-menu interaction; superclass/server menu retain slot/container ownership.
 * This class adapts layout and recipe-book input only, without copying commit, capacity checks or item-return logic. */
public final class AmbientInventoryScreen extends EffectRenderingInventoryScreen<CraftingMenu> implements RecipeUpdateListener {
    private static final ResourceLocation INVENTORY = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final ResourceLocation CRAFTING = ResourceLocation.withDefaultNamespace("textures/gui/container/crafting_table.png");
    private ImageButton bookButton;
    private final RecipeBookComponent recipeBook = new RecipeBookComponent();
    private boolean widthTooNarrow;

    public AmbientInventoryScreen(CraftingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override protected void init() {
        super.init();
        widthTooNarrow = width < 379;
        recipeBook.init(width, height, minecraft, widthTooNarrow, menu);
        leftPos = recipeBook.updateScreenPosition(width, imageWidth);
        bookButton = addRenderableWidget(new ImageButton(0, 0, 20, 18, RecipeBookComponent.RECIPE_BUTTON_SPRITES, button -> {
            recipeBook.toggleVisibility();
            leftPos = recipeBook.updateScreenPosition(width, imageWidth);
            positionBookButton();
        }));
        addWidget(recipeBook);
        positionBookButton();
    }

    private void positionBookButton() {
        if (bookButton != null) bookButton.setPosition(leftPos + 76, topPos + 38);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (recipeBook.mouseClicked(x, y, button)) {
            setFocused(recipeBook);
            return true;
        }
        return widthTooNarrow && recipeBook.isVisible() || super.mouseClicked(x, y, button);
    }

    @Override public void containerTick() {
        super.containerTick();
        recipeBook.tick();
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 中文：遵循原版窄/宽配方书路由；效果布局/提示仍归父类，HUD 图标仍由原版 Gui 抑制，不复制渲染器或新增策略。
        // Follow vanilla inventory's narrow/wide recipe-book routing. The parent
        // owns effect layout/tooltips and lets vanilla Gui suppress the HUD icons;
        // do not copy the effect renderer or introduce a second HUD policy.
        if (recipeBook.isVisible() && widthTooNarrow) {
            renderBackground(gui, mouseX, mouseY, partialTick);
            recipeBook.render(gui, mouseX, mouseY, partialTick);
        } else {
            super.render(gui, mouseX, mouseY, partialTick);
            recipeBook.render(gui, mouseX, mouseY, partialTick);
            recipeBook.renderGhostRecipe(gui, leftPos, topPos, false, partialTick);
        }
        renderTooltip(gui, mouseX, mouseY);
        recipeBook.renderTooltip(gui, leftPos, topPos, mouseX, mouseY);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return recipeBook.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean charTyped(char character, int modifiers) {
        return recipeBook.charTyped(character, modifiers) || super.charTyped(character, modifiers);
    }

    @Override protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return (!widthTooNarrow || !recipeBook.isVisible()) && super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    @Override protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        boolean outside = mouseX < guiLeft || mouseY < guiTop || mouseX >= guiLeft + imageWidth || mouseY >= guiTop + imageHeight;
        return outside && recipeBook.hasClickedOutside(mouseX, mouseY, leftPos, topPos, imageWidth, imageHeight, button);
    }

    @Override protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        super.slotClicked(slot, slotId, button, type);
        recipeBook.slotClicked(slot);
    }

    @Override public void recipesUpdated() { recipeBook.recipesUpdated(); }
    @Override public RecipeBookComponent getRecipeBookComponent() { return recipeBook; }

    @Override protected void renderLabels(GuiGraphics gui, int x, int y) {
        // 中文：九输入加输出使用旧标题区，不让工作台背包标题覆盖原版人物/护甲布局。
        // Nine inputs plus the output use the former title area. No workbench
        // inventory heading is drawn over the vanilla character/armor layout.
    }

    @Override protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        gui.blit(INVENTORY, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        // 中文：仅清旧 2x2/箭头/输出区域，逐像素保留装备、人物背景、背包/快捷栏及外框。
        // Clear only the old 2x2/arrow/result area; retain vanilla equipment,
        // character backdrop, inventory/hotbar and outer border pixel-for-pixel.
        gui.fill(leftPos + 96, topPos + 5, leftPos + 173, topPos + 80, 0xFFC6C6C6);
        for (int i = 0; i < 10; i++) {
            var slot = menu.getSlot(i);
            gui.blit(INVENTORY, leftPos + slot.x - 1, topPos + slot.y - 1, 7, 7, 18, 18);
        }
        gui.blit(CRAFTING, leftPos + 105, topPos + 63, 90, 35, 22, 15);
        InventoryScreen.renderEntityInInventoryFollowsMouse(gui, leftPos + 26, topPos + 8,
                leftPos + 75, topPos + 78, 30, 0.0625F, mouseX, mouseY, minecraft.player);
    }
}
