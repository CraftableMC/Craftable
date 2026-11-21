package org.craftablemc.craftable.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.craftablemc.craftable.network.FurnaceProgressPayload;

/**
 * 客户端线程上的计时加工只读显示。三个合成界面共用一份连接内快照，不接管鼠标、槽位或任务生命周期。
 * Client-thread read-only timed-processing display. Three crafting screens share one connection-scoped snapshot, owning no mouse, slots or job lifecycle.
 * 只画服务端已确认进度，暂停不自增、就绪不等于已交付；关闭界面只隐藏显示，不取消任务。
 * Draw only server-confirmed progress: pauses never advance locally and ready does not mean delivered; closing a screen only hides the display.
 */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class FurnaceProgressOverlay {
    private static Connection connection;
    private static FurnaceProgressPayload progress;

    private FurnaceProgressOverlay() {}

    /** 在客户端主线程核对原连接及当前维度，拒绝重连或换维度前排队的旧显示包。
     * On the client main thread, check the originating connection/current dimension against queued packets from before reconnect or dimension change. */
    public static void receive(FurnaceProgressPayload payload, Connection source) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.getConnection().getConnection() != source || mc.level == null
                || !mc.level.dimension().location().equals(payload.dimension())) return;
        if (payload.mode() == FurnaceProgressPayload.Mode.NONE) { clear(); return; }
        connection = source;
        progress = payload;
    }

    public static void clear() { connection = null; progress = null; }

    static FurnaceProgressPayload current() {
        var mc = Minecraft.getInstance();
        if (progress != null && (mc.getConnection() == null || mc.getConnection().getConnection() != connection
                || mc.level == null || !mc.level.dimension().location().equals(progress.dimension()))) clear();
        return progress;
    }

    @SubscribeEvent
    public static void background(ContainerScreenEvent.Render.Background event) {
        var value = current();
        var screen = event.getContainerScreen();
        var area = area(screen);
        if (value == null || area == null || !visible(screen)) return;
        var graphics = event.getGuiGraphics();
        int x = screen.getGuiLeft() + area.x(), y = screen.getGuiTop() + area.barY();
        int color = color(value.mode());
        // 画在槽位/鼠标手持物之前；不增加 Widget 或输入监听，保留原版箭头与点击命中。
        // Draw before slots/carried stacks; no widget or input listener is added, preserving the vanilla arrow and hit-testing.
        graphics.fill(x - 1, y - 1, x + area.width() + 1, y + 3, 0xFF494949);
        graphics.fill(x, y, x + area.width(), y + 2, 0xFFB3B3B3);
        int width = value.total() == 0 ? 0 : (int) ((long) value.elapsed() * area.width() / value.total());
        if (width > 0) graphics.fill(x, y, x + width, y + 2, color);
        graphics.fill(x + area.width() + 2, y, x + area.width() + 4, y + 2, color);
    }

    @SubscribeEvent
    public static void tooltip(ScreenEvent.Render.Post event) {
        var value = current();
        if (value == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !visible(screen)) return;
        var area = area(screen);
        if (area == null || !screen.getMenu().getCarried().isEmpty()) return;
        int x = event.getMouseX() - screen.getGuiLeft(), y = event.getMouseY() - screen.getGuiTop();
        boolean arrow = x >= area.x() - 1 && x < area.x() + area.width() + 1 && y >= area.y() && y < area.barY() + 3;
        boolean marker = x >= area.x() + area.width() + 2 && x < area.x() + area.width() + 4
                && y >= area.barY() && y < area.barY() + 2;
        if (!arrow && !marker) return;
        // 原页面的提示遍历已结束，必须直接绘制；排到下一次提示遍历会产生迟帧或被后续绘制覆盖。
        // The parent's tooltip pass has ended: render directly rather than queueing a delayed/covered tooltip for a later pass.
        var status = Component.translatable("tooltip.craftable.furnace." + value.mode().name().toLowerCase(java.util.Locale.ROOT));
        List<Component> lines = value.mode() == FurnaceProgressPayload.Mode.READY || value.mode() == FurnaceProgressPayload.Mode.REFUND
                ? List.of(status)
                : List.of(status, Component.translatable("tooltip.craftable.furnace.remaining",
                        (value.total() - (long) value.elapsed() + 19) / 20));
        event.getGuiGraphics().renderComponentTooltip(Minecraft.getInstance().font, lines, event.getMouseX(), event.getMouseY());
    }

    private static boolean visible(Screen screen) {
        if (!RecipeBookProjection.modeAllowed() || CraftingPlanOverlay.active()) return false;
        var book = RecipeBookProjection.component(screen);
        // 窄屏时原版只画配方书，不画背包；不能把加工标记悬浮在隐藏的箭头位置。
        // Narrow screens render only the recipe book, not inventory; never float processing markers over a hidden arrow.
        return book != null && (screen.width >= 379 || !book.isVisible());
    }

    private static Area area(Screen screen) {
        if (screen instanceof AmbientInventoryScreen) return new Area(105, 63, 22, 79);
        if (screen instanceof InventoryScreen) return new Area(134, 28, 18, 45);
        if (screen instanceof CraftingScreen) return new Area(90, 35, 22, 54);
        return null;
    }

    private static int color(FurnaceProgressPayload.Mode mode) {
        return switch (mode) {
            case NONE -> 0xFFB3B3B3;
            case RUNNING -> 0xFFE0C45A;
            case UNLOADED, EXTINGUISHED -> 0xFF969696;
            case BUSY -> 0xFFE39B43;
            case READY -> 0xFF73C66B;
            case REFUND -> 0xFFE17060;
        };
    }

    private record Area(int x, int y, int width, int barY) {}
}
