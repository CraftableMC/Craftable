package org.craftablemc.craftable.client;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：集中设置夹具写入/恢复，防止异步服务端工作在清理后污染用户配置；不增加产品入口或替代求解器。
 * English: Owns focused settings fixture writes/restoration so queued server work cannot pollute restored user config; no product entry points or alternate solver. */
final class SettingsFixture {
    /** 中文：仅测试批次拥有的精确快照，四态直接保留 Mode；不为产品新增偏好模型或存储入口。
     * English: An exact snapshot owned only by this test run; retain all four Modes directly, without adding a product preference model or storage entry. */
    record Preferences(boolean recipeBookEnhancements, boolean detailedFailureFeedback, boolean unlockedOnly,
            DirectPreparationPolicy.Mode partialMode, boolean allowSurplusDrops, int doublePressMillis) {}

    static Preferences preferences() {
        return preferences(new ConfigSettings.Draft(CraftableClientConfig.SPEC));
    }

    static Preferences defaultPreferences() {
        var draft = new ConfigSettings.Draft(CraftableClientConfig.SPEC);
        draft.resetDefaults();
        return preferences(draft);
    }

    private static Preferences preferences(ConfigSettings.Draft draft) {
        return new Preferences(draft.get(clientValue("presentation", "recipeBookEnhancements")),
                draft.get(clientValue("presentation", "detailedFailureFeedback")),
                draft.get(clientValue("presentation", "unlockedOnly")),
                draft.get(clientValue("crafting", "partialPolicy")),
                draft.get(clientValue("crafting", "allowSurplusDrops")),
                draft.get(clientValue("crafting", "doublePressMillis")));
    }

    // 中文：恢复也走共享草稿校验/保存；默认值和约束仍只由注册 spec 定义，不复制一套持久化实现。
    // English: Restoration uses shared draft validation/save; defaults and constraints remain owned solely by the registered spec, never a second persistence implementation.
    static void savePreferences(Preferences preferences) {
        var draft = new ConfigSettings.Draft(CraftableClientConfig.SPEC);
        draft.set(clientValue("presentation", "recipeBookEnhancements"), preferences.recipeBookEnhancements());
        draft.set(clientValue("presentation", "detailedFailureFeedback"), preferences.detailedFailureFeedback());
        draft.set(clientValue("presentation", "unlockedOnly"), preferences.unlockedOnly());
        draft.set(clientValue("crafting", "partialPolicy"), preferences.partialMode());
        draft.set(clientValue("crafting", "allowSurplusDrops"), preferences.allowSurplusDrops());
        draft.set(clientValue("crafting", "doublePressMillis"), preferences.doublePressMillis());
        draft.save();
    }

    @SuppressWarnings("unchecked")
    private static <T> ModConfigSpec.ConfigValue<T> clientValue(String section, String name) {
        return (ModConfigSpec.ConfigValue<T>) CraftableClientConfig.SPEC.getValues().get(List.of(section, name));
    }

    static void configure(SettingsScenarioState state, Minecraft mc, int steps, int radius) {
        final long epoch;
        synchronized (state) {
            require(!state.fixtureClosed, "Cannot configure a retired settings fixture");
            state.configured = false; epoch = ++state.configurationEpoch;
        }
        mc.getSingleplayerServer().execute(() -> applyConfiguration(state, epoch, steps, radius));
    }

    private static void applyConfiguration(SettingsScenarioState state, long epoch, int steps, int radius) {
        // 中文：仅短写区与退役互斥，排队的服务端任务不能在中止/完成后覆盖已恢复值。
        // Share only this short write section with retirement. A queued server
        // task must never overwrite restored values after abort or completion.
        synchronized (state) {
            if (state.fixtureClosed || epoch != state.configurationEpoch) return;
            try { serverSteps().set(steps); serverRadius().set(radius); state.configured = true; }
            catch (Throwable error) { state.serverFailure = error; }
        }
    }
    /** 中文：所有快照均早于夹具写入；一处清理失败不能阻止注销回调或恢复其他用户设置。
     * English: All snapshots precede fixture writes. A cleanup failure cannot prevent
     * unregistering callbacks or restoring unrelated user settings. */
    static void restore(SettingsScenarioState state, Minecraft mc) {
        synchronized (state) { state.fixtureClosed = true; state.configurationEpoch++; }
        if (!state.initialized || state.restored) return;
        Throwable failure = null;
        try {
            try {
                if (state.cleanupProbeFailure != null) {
                    var injected = state.cleanupProbeFailure; state.cleanupProbeFailure = null; throw injected;
                }
                SettingsFixture.savePreferences(state.before);
            } catch (Throwable error) { failure = addFailure(failure, error); }
            try { serverSteps().set(state.oldSteps); } catch (Throwable error) { failure = addFailure(failure, error); }
            try { serverRadius().set(state.oldRadius); } catch (Throwable error) { failure = addFailure(failure, error); }
            for (var entry : state.oldPolicyValues.entrySet()) {
                try { restoreValue(entry.getKey(), entry.getValue()); }
                catch (Throwable error) { failure = addFailure(failure, error); }
            }
            try { CraftableServerConfig.SPEC.save(); } catch (Throwable error) { failure = addFailure(failure, error); }
            try { restoreFixtureMouse(state, mc); } catch (Throwable error) { failure = addFailure(failure, error); }
            try {
                mc.options.guiScale().set(state.oldScale);
                mc.getWindow().setWindowed(state.oldWidth, state.oldHeight); mc.resizeDisplay();
            } catch (Throwable error) { failure = addFailure(failure, error); }
            try {
                boolean reload = !mc.getLanguageManager().getSelected().equals(state.oldLanguage);
                mc.getLanguageManager().setSelected(state.oldLanguage); mc.options.languageCode = state.oldLanguage;
                if (reload) mc.reloadResourcePacks();
                mc.options.save(); mc.setLastInputType(state.oldInputType);
            } catch (Throwable error) { failure = addFailure(failure, error); }
        } finally {
            try { SettingsCapture.clear(state); } catch (Throwable error) { failure = addFailure(failure, error); }
            try {
                NeoForge.EVENT_BUS.unregister(state.initListener); state.initListenerRegistered = false;
            } catch (Throwable error) { failure = addFailure(failure, error); }
            try {
                NeoForge.EVENT_BUS.unregister(state.captureListener); state.captureListenerRegistered = false;
            } catch (Throwable error) { failure = addFailure(failure, error); }
        }
        state.restored = failure == null; // 中文：失败清理保持可重试。 English: A failed cleanup remains retryable.
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new AssertionError("Settings fixture cleanup failed", failure);
    }

    static void restoreAndVerifyRecovery(SettingsScenarioState state, Minecraft mc) {
        // 中文：有意留下待执行回调后制造开发夹具失败，验证 finally 即使前序失败仍退役回调和两个监听器；不冒充游戏故障。
        // Controlled dev-fixture failure, not a simulated game failure. Leave
        // pending callbacks on purpose, then prove the fixed finally retires
        // them and both registered listeners despite an earlier failed step.
        var injected = new IllegalStateException("controlled settings cleanup probe");
        long staleCapture = state.captureEpoch;
        long staleConfiguration = state.configurationEpoch;
        state.cleanupProbeFailure = injected;
        state.afterRender = () -> { throw new AssertionError("retired callback ran"); };
        state.numericCaptureContinuation = state.afterRender;
        state.deferredScreen = mc.screen; state.captureName = "retired-settings-probe";
        try { restore(state, mc); throw new AssertionError("Controlled cleanup failure was swallowed"); }
        catch (AssertionError error) {
            require(error.getCause() == injected, "Cleanup did not preserve its original failure");
            if (injected.getSuppressed().length != 0) throw error;
            require(!state.restored && !state.initListenerRegistered && !state.captureListenerRegistered
                    && !SettingsCapture.pending(state), "Failed cleanup left an owned listener or callback active");
            verifyRestoredServerValues(state);
            SettingsCapture.completed(state, staleCapture, net.minecraft.network.chat.Component.literal("late screenshot completion"));
            require(!state.captured, "Retired screenshot completion changed fixture state after cleanup");
            applyConfiguration(state, staleConfiguration, state.oldSteps + 1, state.oldRadius + 1);
            verifyRestoredServerValues(state);
        }
        restore(state, mc);
        verifyRestoredServerValues(state);
        require(state.restored && SettingsFixture.preferences().equals(state.before)
                && !SettingsCapture.pending(state), "Cleanup retry failed to restore the original personal values");
        require(mc.options.guiScale().get() == state.oldScale
                && mc.getLanguageManager().getSelected().equals(state.oldLanguage)
                && mc.getLastInputType() == state.oldInputType,
                "Cleanup retry failed to restore the original scale, language, or input type");
        Craftable.LOGGER.warn("SETTINGS_CLEANUP PASS: controlled early cleanup failure preserved; both listener unregister calls and deferred/capture retirement completed; stale screenshot and server configuration callbacks ignored; second cleanup restored original preferences; no product failure injected");
    }

    private static void verifyRestoredServerValues(SettingsScenarioState state) {
        require(Objects.equals(serverSteps().getRaw(), state.oldSteps)
                && Objects.equals(serverRadius().getRaw(), state.oldRadius),
                "Cleanup did not restore the original server step/radius values");
        for (var entry : state.oldPolicyValues.entrySet())
            require(Objects.equals(entry.getKey().getRaw(), entry.getValue()),
                    "Cleanup did not restore the original registered policy value: " + entry.getKey().getPath());
    }

    private static Throwable addFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    static void restoreFixtureMouse(SettingsScenarioState state, Minecraft mc) {
        if (state.fixtureMouseX == null) return;
        try { writeField(mc.mouseHandler, UiField.XPOS, state.fixtureMouseX); writeField(mc.mouseHandler, UiField.YPOS, state.fixtureMouseY); }
        catch (Exception error) { throw new AssertionError("Could not restore owned mouse-coordinate fixture", error); }
        state.fixtureMouseX = null; state.fixtureMouseY = null; state.mouseTooltipTarget = null;
    }
}
