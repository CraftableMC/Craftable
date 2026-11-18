package org.craftablemc.craftable.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.ClientRequestSequence;
import org.craftablemc.craftable.client.NativeOptionRows;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.network.OpenInventoryRequestPayload;
import org.craftablemc.craftable.config.EnvironmentScanSettings;

/** 中文：客户端菜单打开意图和模式切换生命周期的所有者，不创建服务端菜单或推断服务端工作台权限。
 * 延迟回复必须仍匹配玩家发起请求的页面；断线清除请求、规则摘要及待返回创造界面状态。
 * English: Owns client menu-open intent and mode-change lifecycle, never creating server menus or inferring workstation permission.
 * Delayed replies must still match the initiating screen; disconnect clears requests, rule summaries and deferred creative-screen state. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class AmbientInventoryEvents {
    private static Screen requestedFrom;
    private static long requestedAt = Long.MIN_VALUE;
    private static EnvironmentScanSettings rules;
    private static net.minecraft.world.level.GameType observedMode;
    private static boolean openCreativeAfterClose;
    private AmbientInventoryEvents() {}

    public static void request(Screen screen) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != screen
                || !org.craftablemc.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // 中文：退出创造目录时原版可能自动构造 InventoryScreen，这不等于玩家新发起打开操作。
        // Vanilla may construct InventoryScreen while leaving its creative
        // catalog. That automatic conversion is not a new player open action.
        if (observedMode != null && !org.craftablemc.craftable.api.CraftableModePolicy.allows(observedMode)) return;
        long now = mc.level.getGameTime();
        if (screen == requestedFrom && now - requestedAt >= 0 && now - requestedAt < 20) return;
        requestedFrom = screen;
        requestedAt = now;
        PacketDistributor.sendToServer(new OpenInventoryRequestPayload(ClientRequestSequence.next()));
    }

    public static void receiveRules(EnvironmentScanSettings settings, boolean workbench) {
        rules = settings;
        var screen = Minecraft.getInstance().screen;
        if (workbench && screen instanceof InventoryScreen) request(screen);
    }

    public static void clear() { requestedFrom = null; requestedAt = Long.MIN_VALUE; rules = null; observedMode = null; openCreativeAfterClose = false; }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        var mode = mc.gameMode.getPlayerMode();
        if (mode != observedMode) {
            boolean returning = observedMode != null && !org.craftablemc.craftable.api.CraftableModePolicy.allows(observedMode)
                    && org.craftablemc.craftable.api.CraftableModePolicy.allows(mode);
            org.craftablemc.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
            org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.clear();
            org.craftablemc.craftable.client.recipebook.RecipeBookStatusHandler.clearRequestState();
            requestedFrom = null;
            observedMode = mode;
            var book = org.craftablemc.craftable.client.recipebook.RecipeBookProjection.component(mc.screen);
            if (book != null && book.isVisible()) book.recipesUpdated();
            // 中文：切回生存不主动打开背包，也不请求新菜单。
            // Returning to survival does not open an inventory or request a menu.
            if (returning && (mc.screen instanceof InventoryScreen || mc.screen instanceof CreativeModeInventoryScreen)) {
                mc.player.closeContainer();
            }
        }
        if (openCreativeAfterClose) {
            openCreativeAfterClose = false;
            if (mode == net.minecraft.world.level.GameType.CREATIVE && mc.screen == null
                    && mc.player.containerMenu == mc.player.inventoryMenu) {
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(),
                        mc.options.operatorItemsTab().get()));
            }
        }
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        // 中文：页面关闭为 null 不触发 Opening；等服务端正常关闭/归还包后再进入原版 UI。
        // Opening is NOT fired when Minecraft closes a screen to null. Wait
        // for the server's normal close/return packet, then enter vanilla UI.
        if (org.craftablemc.craftable.client.recipebook.RecipeBookProjection.component(event.getScreen()) != null) {
            org.craftablemc.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
        }
        var mc = Minecraft.getInstance();
        if (event.getScreen() instanceof AmbientInventoryScreen && mc.gameMode != null
                && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE) openCreativeAfterClose = true;
    }

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        var mc = Minecraft.getInstance();
        boolean allowed = org.craftablemc.craftable.client.recipebook.RecipeBookProjection.modeAllowed();
        if (org.craftablemc.craftable.client.recipebook.RecipeBookProjection.active()
                && org.craftablemc.craftable.client.recipebook.RecipeBookProjection.component(event.getNewScreen()) == null) {
            org.craftablemc.craftable.client.recipebook.RecipeBookProjection.restoreVanilla();
        }
        // 中文：迟到回复不能重开玩家已关闭的页面，应按原版 ID 关闭对应服务端菜单。
        // A delayed server reply must not reopen a screen the player has already
        // dismissed. Close the corresponding server menu using its vanilla ID.
        if (event.getNewScreen() instanceof AmbientInventoryScreen
                && (!allowed || Minecraft.getInstance().screen != requestedFrom)) {
            Screen previous = mc.screen;
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.closeContainer();
            event.setNewScreen(previous instanceof AmbientInventoryScreen ? null : previous);
        }
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (CraftableClientConfig.recipeBookEnhancementsEnabled() && screen instanceof InventoryScreen
                && org.craftablemc.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) request(screen);
        if (screen instanceof AccessibilityOptionsScreen) {
            event.getListenersList().stream().filter(OptionsList.class::isInstance).map(OptionsList.class::cast)
                    .findFirst().ifPresent(list -> appendSettingsButton(screen, list));
        }
    }

    public static void appendSettingsButton(Screen parent, OptionsList list) {
        var rows = ((ContainerEventHandler) list).children();
        boolean added = rows.stream().map(entry -> (ContainerEventHandler) entry)
                .flatMap(entry -> entry.children().stream()).anyMatch(SettingsButton.class::isInstance);
        if (added) return;
        var button = new SettingsButton(parent);
        // 中文：滚动、裁剪、焦点和缩放归原版列表拥有；嵌套按钮不能再注册为顶层控件。
        // The native list owns scrolling, clipping, focus and resize. Never
        // also register this nested button as a top-level screen widget.
        NativeOptionRows.appendSmall(list, button);
    }

    private static final class SettingsButton extends Button {
        SettingsButton(Screen parent) {
            super(0, 0, 150, 20, Component.translatable("mod.craftable.name"), button -> Minecraft.getInstance()
                    .setScreen(new org.craftablemc.craftable.client.CraftableSettingsScreen(parent)), DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.translatable("screen.craftable.settings.title")));
        }
    }

    public static Component ruleSummary() {
        return rules == null ? Component.translatable("tooltip.craftable.rules_unknown")
                : Component.translatable("tooltip.craftable.rules", rules.horizontalRadius(), rules.verticalRadius(),
                    Component.translatable(rules.includeEnderChest() ? "options.on" : "options.off"));
    }
}
