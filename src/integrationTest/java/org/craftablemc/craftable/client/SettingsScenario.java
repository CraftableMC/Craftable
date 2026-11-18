package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;

/** 中文：真实设置回归的调度器；各章节拥有行为，主流程仅派发具名步骤、保留看门狗并保证清理，不形成第二套测试框架。
 * English: Real settings regression. Chapters own their behavior; this controller only
 * dispatches named steps, retains the unchanged watchdog, and guarantees cleanup without another test framework. */
final class SettingsScenario {
    private final SettingsScenarioState state = new SettingsScenarioState();

    boolean tick(Minecraft mc) throws Exception {
        try {
            if (state.serverFailure != null) throw new AssertionError("Settings server fixture failed", state.serverFailure);
            if (state.captureFailure != null) throw new AssertionError("Settings screenshot failed", state.captureFailure);
            if (++state.age > 400) throw new AssertionError("Settings timeout step=" + state.step
                    + ", screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
            if (SettingsCapture.waiting(state)) return false;
            if (state.step.languageWait())
                state.languageReadyTicks = mc.getOverlay() == null ? state.languageReadyTicks + 1 : 0;
            return switch (state.step.owner) {
                case NAVIGATION -> SettingsNavigationChecks.advance(state, mc);
                case RULES -> SettingsRulesChecks.advance(state, mc);
                case PERSISTENCE -> SettingsPersistenceChecks.advance(state, mc);
                case WHITELIST -> SettingsWhitelistChecks.advance(state, mc);
                case PROFESSIONAL -> SettingsProfessionalChecks.advance(state, mc);
                case PRESENTATION -> SettingsPresentationChecks.advance(state, mc);
            };
        } catch (Throwable failure) {
            try { SettingsFixture.restore(state, mc); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            if (failure instanceof Exception error) throw error;
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Settings fixture failed", failure);
        }
    }

    void abort(Minecraft mc) { SettingsFixture.restore(state, mc); }
}
