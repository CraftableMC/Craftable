package org.craftablemc.craftable.client.recipebook;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.CraftableKeyMappings;
import org.craftablemc.craftable.client.ClientRequestSequence;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.network.CreateRecipeRequestPayload;
import org.craftablemc.craftable.network.CreateRecipeResultPayload;

/** 中文：客户端线程上的配方书输入适配器；只发请求，不从按钮颜色推断执行权限。
 * 普通按键只请求完整合成；双击链接前次请求，实际部分准备资格由服务端决定。
 * English: Client-thread recipe-book input adapter; it sends requests, never infers authority from button colors.
 * A normal press requests full crafting; a double press links a prior request while the server decides partial eligibility. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class RecipeBookInputHandler {
    private static final org.craftablemc.craftable.client.DoublePressGesture GESTURE =
            new org.craftablemc.craftable.client.DoublePressGesture();
    private static CreatePresentation pendingPresentation;
    private record CreatePresentation(long sequence, net.minecraft.resources.ResourceLocation recipe,
            Object screen, Object menu, Object level, Object connection) {}
    private RecipeBookInputHandler() {}

    /** 中文：只拥有最新普通 C 的展示归属，不是执行授权；匹配回复先消费，旧序号不能清掉更新的请求。
     * English: Owns only the latest ordinary C's presentation context, never execution authority.
     * Consume a matching reply before identity checks; an older sequence must not retire a newer request. */
    public static boolean consumePresentation(CreateRecipeResultPayload reply) {
        var sent = pendingPresentation;
        if (sent == null || sent.sequence() != reply.requestId()) return false;
        pendingPresentation = null;
        var mc = Minecraft.getInstance();
        return sent.recipe().equals(reply.recipeId()) && mc.player != null && sent.screen() == mc.screen
                && sent.menu() == mc.player.containerMenu && sent.level() == mc.level && sent.connection() == mc.getConnection();
    }

    /** 页面、模式、配方或连接退役时释放单份展示状态。 / Retire the single display context with its page, mode, recipes or connection. */
    public static void clearRequestState() { GESTURE.clear(); pendingPresentation = null; }

    @SubscribeEvent
    public static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (CraftableKeyMappings.CREATE_ONE.isActiveAndMatches(
                InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) GESTURE.release();
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) { clearRequestState(); }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        // 中文：分类/翻页点击会切断双击上下文，即使同一配方很快再次出现在鼠标下。
        // English: A category/page click breaks gesture context even if the same recipe soon reappears under the cursor.
        GESTURE.clear();
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        // 中文：嵌入详情独占输入，搜索框保留文字输入；这只是客户端路由，服务端仍需独立校验。
        // English: Embedded details own input and search keeps text input; this is routing only, not server validation.
        if (org.craftablemc.craftable.client.CraftingPlanOverlay.active()) return;
        if (!RecipeBookProjection.active()) {
            return;
        }
        RecipeBookComponent component = RecipeBookProjection.component(event.getScreen());
        if (component == null || !component.isVisible()) {
            return;
        }

        RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) component;
        EditBox searchBox = componentAccessor.craftable$getSearchBox();
        if (searchBox != null && searchBox.isFocused()) {
            GESTURE.clear();
            return;
        }

        InputConstants.Key key = InputConstants.getKey(event.getKeyCode(), event.getScanCode());
        // 中文：NeoForge 的 GUI/NONE 映射有意拒绝按住的修饰键，Shift+C 须显式匹配配置的基础键。
        // GUI-context NONE mappings intentionally reject held modifiers in
        // NeoForge. Match the configured base key explicitly for Shift+C.
        boolean detail = event.getModifiers() == org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT
                && CraftableKeyMappings.CREATE_ONE.getKey().equals(key);
        if (!detail && !CraftableKeyMappings.CREATE_ONE.isActiveAndMatches(key)) {
            GESTURE.clear();
            return;
        }

        if (event.getModifiers() != 0 && !detail) {
            GESTURE.clear();
            return; // 中文：带修饰键的 C 不能误触完整合成。 English: Modified C must never accidentally consume a FULL_ONLY craft.
        }

        RecipeBookPage page = componentAccessor.craftable$getRecipeBookPage();
        RecipeButton hoveredButton = ((RecipeBookPageAccessor) page).craftable$getHoveredButton();
        if (hoveredButton == null || !hoveredButton.visible) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        // 中文：路线优化仅限当前显示物品的精确输出组。
        // Optimize routes only within the displayed item's exact output group.
        RecipeHolder<?> recipe = RecipeButtonTargetResolver.preferredRecipe(hoveredButton);
        if (recipe == null) return; // 中文：筛选/重载的集合可能在渲染与输入之间消失。 English: A filtered/reloaded collection can disappear between render and input.
        if (detail) {
            clearRequestState();
            org.craftablemc.craftable.client.CraftingPlanOverlay.open(event.getScreen(), recipe.id(), false,
                    RecipeButtonTargetResolver.outputVariants(hoveredButton.getCollection(), recipe));
            event.setCanceled(true);
            return;
        }
        long sequence = ClientRequestSequence.next();
        var press = GESTURE.press(minecraft.player.containerMenu, recipe.id(), sequence,
                System.nanoTime(), CraftableClientConfig.doublePressMillis() * 1_000_000L);
        if (press.isPresent()) {
            pendingPresentation = new CreatePresentation(sequence, recipe.id(), event.getScreen(),
                    minecraft.player.containerMenu, minecraft.level, minecraft.getConnection());
            PacketDistributor.sendToServer(new CreateRecipeRequestPayload(
                recipe.id(), sequence, press.getAsLong(), CraftableClientConfig.allowSurplusDrops(),
                CraftableClientConfig.partialPolicy()));
        }
        event.setCanceled(true);
    }
}
