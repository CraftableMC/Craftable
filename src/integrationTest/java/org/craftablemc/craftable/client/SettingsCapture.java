package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import static org.craftablemc.craftable.client.SettingsUiAccess.CheckedAction;
import static org.craftablemc.craftable.client.SettingsUiAccess.require;

/** 中文：只负责真实渲染等待及截图，截图完成按代次隔离，清理后迟到回调不能复活状态；保留历史前缀及 35 个诊断样本的映射。
 * English: Render waits and screenshots only. All historical prefixes are retained:
 * *-20261019-copy.png maps to *-20261021-refactor.png (35 diagnostic samples).
 * Capture completion is scoped to an epoch so cleanup retires late callbacks. */
final class SettingsCapture {
    static void captureNumericTooltip(SettingsScenarioState state, Minecraft mc, AbstractWidget widget, String name, CheckedAction continuation) {
        require(state.numericCaptureContinuation == null && state.afterRender == null, "Numeric tooltip capture overlaps another deferred interaction");
        require(widget != null && widget.getTooltip() != null, "Numeric screenshot lacks a real tooltip control: " + name);
        mc.screen.clearFocus(); mc.setLastInputType(InputType.KEYBOARD_TAB);
        state.tooltipFocus = widget; state.tooltipFocus.setFocused(true);
        state.captured = false; state.numericCaptureContinuation = () -> {
            try { continuation.run(); }
            catch (Exception error) { throw new AssertionError("Numeric tooltip screenshot continuation failed: " + name, error); }
        };
        deferUntilRendered(state, mc.screen, () -> capture(state, mc, name));
    }

    static void deferUntilRendered(SettingsScenarioState state, Screen screen, Runnable action) {
        require(state.afterRender == null, "A native interaction is already waiting for layout");
        state.deferredScreen = screen; state.afterRender = action; state.deferredRenderedFrames = 0;
    }

    static void setLanguage(Minecraft mc, String language) {
        mc.getLanguageManager().setSelected(language); mc.options.languageCode = language; mc.reloadResourcePacks();
    }

    static void capture(SettingsScenarioState state, Minecraft mc, String name) {
        state.captureEpoch++; state.captured = false; state.captureScreen = mc.screen; state.captureName = name;
    }

    static void captureFrame(SettingsScenarioState state, ScreenEvent.Render.Post event) {
        if (state.afterRender != null && event.getScreen() == state.deferredScreen) state.deferredRenderedFrames++;
        if (state.captureName == null || event.getScreen() != state.captureScreen) return;
        var name = state.captureName; state.captureName = null;
        long epoch = state.captureEpoch;
        event.getGuiGraphics().flush();
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message -> completed(state, epoch, message));
    }

    static void completed(SettingsScenarioState state, long epoch, Component message) {
        synchronized (state) {
            if (epoch != state.captureEpoch || state.fixtureClosed || state.restored) return;
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                    && contents.getKey().equals("screenshot.failure")) state.captureFailure = new AssertionError(message.getString());
            else state.captured = true;
        }
    }
    static boolean waiting(SettingsScenarioState state) {
        if (state.afterRender != null) {
            require(Minecraft.getInstance().screen == state.deferredScreen, "Deferred interaction lost its screen");
            if (state.deferredRenderedFrames < 2) return true;
            var action = state.afterRender; state.afterRender = null; state.deferredScreen = null;
            action.run(); state.age = 0; return true;
        }
        if (state.numericCaptureContinuation != null) {
            if (!state.captured) return true;
            if (state.tooltipFocus != null) { state.tooltipFocus.setFocused(false); state.tooltipFocus = null; }
            var action = state.numericCaptureContinuation; state.numericCaptureContinuation = null;
            action.run(); state.age = 0; return true;
        }
        return false;
    }
    static void clear(SettingsScenarioState state) {
        try { if (state.tooltipFocus != null) state.tooltipFocus.setFocused(false); }
        finally {
            synchronized (state) {
                state.captureEpoch++;
                state.tooltipFocus = null; state.afterRender = state.numericCaptureContinuation = null;
                state.deferredScreen = state.captureScreen = null; state.captureName = null;
                state.captured = false; state.deferredRenderedFrames = 0;
            }
        }
    }
    static boolean pending(SettingsScenarioState state) {
        return state.afterRender != null || state.numericCaptureContinuation != null || state.captureName != null
                || state.deferredScreen != null || state.captureScreen != null || state.tooltipFocus != null;
    }

}
