package org.craftablemc.craftable.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsUiAccess.Bounds;

/** 中文：单次设置回归共享的夹具状态；页面/快照归本批拥有，volatile 回调结果和代次使清理可隔离迟到完成。
 * English: Fixture state for one settings run; this run owns screens/snapshots, while volatile callback outcomes and epochs isolate late completion from cleanup. */
final class SettingsScenarioState {
    SettingsStep step = SettingsStep.INITIALIZE;
    int age, languageReadyTicks;
    SettingsNavigationChecks.EntryStep entryStep = SettingsNavigationChecks.EntryStep.OPEN_OPTIONS;
    int entryAge, entryScale = 1;
    volatile boolean configured;
    volatile boolean fixtureClosed;
    long configurationEpoch;
    volatile Throwable serverFailure, captureFailure;
    SettingsFixture.Preferences before, saved;
    int oldSteps, oldRadius, oldScale, oldWidth, oldHeight;
    String oldLanguage;
    InputType oldInputType;
    boolean initialized, restored;
    boolean directPreparationChecked;
    boolean professionalPolicyChecked;
    boolean professionalClientEnumsChecked;
    Object professionalListBefore;
    Screen policyWorld;
    DirectPreparationPolicy policyBefore;
    Map<ModConfigSpec.ConfigValue<?>, Object> policyDraftBefore;
    final Map<ModConfigSpec.ConfigValue<?>, Object> oldPolicyValues = new IdentityHashMap<>();
    long firstRevision;
    int oldTtl;
    Screen home;
    int expectedAccessibilityRows;
    AbstractWidget accessibilityTrailingLeft;
    final Map<AbstractWidget, Bounds> originalOptions = new IdentityHashMap<>();
    Screen captureScreen;
    AbstractWidget tooltipFocus;
    String captureName;
    volatile boolean captured;
    volatile long captureEpoch;
    Screen deferredScreen;
    Runnable afterRender;
    Runnable numericCaptureContinuation;
    int deferredRenderedFrames;
    Double fixtureMouseX, fixtureMouseY;
    AbstractWidget mouseTooltipTarget;
    final Consumer<ScreenEvent.Init.Post> initListener = event -> SettingsNavigationChecks.captureOriginalOptions(this, event);
    final Consumer<ScreenEvent.Render.Post> captureListener = event -> SettingsCapture.captureFrame(this, event);
    boolean initListenerRegistered, captureListenerRegistered;
    RuntimeException cleanupProbeFailure;
    void next() { step = step.next(); age = 0; languageReadyTicks = 0; }

}
