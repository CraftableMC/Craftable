package org.craftablemc.craftable.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.craftablemc.craftable.client.menu.AmbientInventoryEvents;
import org.craftablemc.craftable.client.mixin.OptionsListEntryAccessor;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsCapture.capture;
import static org.craftablemc.craftable.client.SettingsCapture.deferUntilRendered;
import static org.craftablemc.craftable.client.SettingsFixture.configure;
import static org.craftablemc.craftable.client.SettingsPersistenceChecks.verifyPersonalModePersistence;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：检验原版选项/辅助功能导航与末行补位边界，防止追加按钮丢失原控件映射、焦点或延迟滑块写入。
 * English: Checks native Options/Accessibility navigation and last-row insertion boundaries against lost control mappings, focus or deferred slider writes. */
final class SettingsNavigationChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.INITIALIZE) {
            state.before = SettingsFixture.preferences();
            state.oldSteps = serverSteps().get(); state.oldRadius = serverRadius().get();
            for (var value : CraftableServerConfig.directPreparationValues()) {
                Object original = value.getRaw();
                state.oldPolicyValues.put(value, original instanceof List<?> ids ? List.copyOf(ids) : original);
            }
            // 中文：用户测试世界可能留有快捷模式，夹具先持有确定策略，再完整恢复注册值，避免继承用户状态造成假失败。
            // User test worlds may retain a shortcut mode. Own a deterministic
            // policy for this fixture and restore every registered value below.
            state.oldTtl = serverValue("environment", "diagnosticCacheTicks").get();
            state.oldScale = mc.options.guiScale().get(); state.oldLanguage = mc.getLanguageManager().getSelected();
            state.oldInputType = mc.getLastInputType();
            state.oldWidth = mc.getWindow().getScreenWidth(); state.oldHeight = mc.getWindow().getScreenHeight();
            state.initialized = true;
            restoreValue(directList("directPreparationMode"), DirectPreparationPolicy.Mode.CUSTOM);
            restoreValue(directList("directPreparationAllowedRecipes"), List.of());
            verifyPersonalModePersistence(state);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Init.Post.class, state.initListener); state.initListenerRegistered = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ScreenEvent.Render.Post.class, state.captureListener); state.captureListenerRegistered = true;
            verifyAccessibilityAppendBoundaries(mc);
            mc.player.closeContainer(); mc.setScreen(null);
            configure(state, mc, 192, 3);
            mc.getWindow().setWindowed(1280, 720); mc.options.guiScale().set(1); mc.resizeDisplay();
            state.next(); return false;
        }
        if (state.step == SettingsStep.NAVIGATE_ACCESSIBILITY && state.configured && state.age > 4) {
            if (testSettingsEntry(state, mc)) { state.step = SettingsStep.INITIAL_RULES; state.age = 0; }
            return false;
        }
        return false;
    }

    static void captureOriginalOptions(SettingsScenarioState state, ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof AccessibilityOptionsScreen) {
            var list = event.getListenersList().stream().filter(OptionsList.class::isInstance)
                    .map(OptionsList.class::cast).findFirst().orElseThrow();
            var rows = ((ContainerEventHandler) list).children();
            var lastWidgets = rows.isEmpty() ? List.<GuiEventListener>of()
                    : ((ContainerEventHandler) rows.getLast()).children();
            state.accessibilityTrailingLeft = lastWidgets.size() == 1 && lastWidgets.getFirst() instanceof AbstractWidget widget
                    && widget.getWidth() <= 150 ? widget : null;
            state.expectedAccessibilityRows = rows.size() + (state.accessibilityTrailingLeft == null ? 1 : 0);
            return;
        }
        if (!(event.getScreen() instanceof OptionsScreen)) return;
        state.originalOptions.clear();
        for (var listener : event.getListenersList()) if (listener instanceof AbstractWidget widget)
            state.originalOptions.put(widget, new Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
    }

    static boolean testSettingsEntry(SettingsScenarioState state, Minecraft mc) throws Exception {
        state.entryAge++;
        if (state.entryStep == EntryStep.OPEN_OPTIONS) {
            mc.options.guiScale().set(state.entryScale); mc.resizeDisplay();
            if (mc.screen instanceof AccessibilityOptionsScreen) verifyAccessibility(state, mc, false);
            mc.setScreen(new OptionsScreen(null, mc.options));
            verifyMainOptions(state, mc);
            if (state.entryScale == 1) capture(state, mc, "settings-native-main-options-restored-20261021-refactor");
            state.entryStep = state.entryStep.next(); state.entryAge = 0;
        } else if (state.entryStep == EntryStep.OPEN_ACCESSIBILITY && state.entryAge > 2 && (state.entryScale != 1 || state.captured)) {
            click(mc.screen, Component.translatable("options.accessibility").getString());
            require(mc.screen instanceof AccessibilityOptionsScreen, "Vanilla accessibility button opened the wrong page");
            state.entryStep = state.entryStep.next(); state.entryAge = 0;
        } else if (state.entryStep == EntryStep.SCROLL_ACCESSIBILITY && state.entryAge > 2) {
            verifyAccessibility(state, mc, false);
            var list = (OptionsList) read(mc.screen, UiField.LIST);
            require(mc.screen.mouseScrolled(mc.screen.width / 2, list.getY() + list.getHeight() / 2, 0, -100),
                    "Native accessibility list ignored mouse wheel scrolling");
            state.entryStep = state.entryStep.next(); state.entryAge = 0;
        } else if (state.entryStep == EntryStep.CAPTURE_ACCESSIBILITY && state.entryAge > 2) {
            verifyAccessibility(state, mc, true);
            capture(state, mc, "settings-native-accessibility-scale-" + state.entryScale + "-20261021-refactor");
            state.entryStep = state.entryStep.next(); state.entryAge = 0;
        } else if (state.entryStep == EntryStep.FOCUS_ENTRY && state.captured) {
            if (state.entryScale < 3) { state.entryScale++; state.entryStep = EntryStep.OPEN_OPTIONS; state.entryAge = 0; return false; }
            var accessibility = mc.screen;
            var entry = button(accessibility, inGameName());
            // 中文：直接 Screen.keyPressed 跳过输入类型标记；原版只在键盘类型下自动滚动焦点行，夹具须复现此环境。
            // Screen.keyPressed bypasses KeyboardHandler's input-type marker;
            // vanilla only scrolls focused rows automatically for keyboard input.
            mc.setLastInputType(InputType.KEYBOARD_TAB);
            accessibility.clearFocus();
            ((OptionsList) read(accessibility, UiField.LIST)).setScrollAmount(0);
            for (int i = 0; i < 100 && !entry.isFocused(); i++) accessibility.keyPressed(258, 0, 0);
            require(entry.isFocused(), "Tab did not reach the nested Craftable list entry");
            state.entryStep = EntryStep.OPEN_WITH_KEYBOARD; state.entryAge = 0;
        } else if (state.entryStep == EntryStep.OPEN_WITH_KEYBOARD && state.entryAge > 2) {
            verifyAccessibility(state, mc, true);
            var accessibility = mc.screen;
            var list = (OptionsList) read(accessibility, UiField.LIST);
            double scroll = list.getScrollAmount();
            require(scroll > 0, "Keyboard focus did not scroll the offscreen settings entry into view");
            accessibility.keyPressed(257, 0, 0);
            require(mc.screen instanceof CraftableSettingsScreen, "Enter did not open settings from accessibility");
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == accessibility, "Escape did not return to the same accessibility page");
            require(list.getScrollAmount() == scroll, "Returning from settings lost accessibility scroll position");
            verifyAccessibility(state, mc, true);
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(mc.screen instanceof OptionsScreen, "Accessibility Done did not return to vanilla options");
            verifyMainOptions(state, mc);
            state.entryStep = EntryStep.REOPEN_ACCESSIBILITY; state.entryAge = 0;
        } else if (state.entryStep == EntryStep.REOPEN_ACCESSIBILITY && state.entryAge > 2) {
            click(mc.screen, Component.translatable("options.accessibility").getString());
            ((OptionsList) read(mc.screen, UiField.LIST)).setScrollAmount(Double.MAX_VALUE);
            state.entryStep = state.entryStep.next(); state.entryAge = 0;
        } else if (state.entryStep == EntryStep.OPEN_WITH_MOUSE && state.entryAge > 2) {
            verifyAccessibility(state, mc, true);
            click(mc.screen, inGameName());
            require(mc.screen instanceof CraftableSettingsScreen, "Mouse did not open the nested settings entry");
            require(SettingsFixture.preferences().equals(state.before), "Opening settings changed personal values");
            mc.options.guiScale().set(2); mc.resizeDisplay();
            return true;
        }
        return false;
    }

    static void verifyMainOptions(SettingsScenarioState state, Minecraft mc) {
        require(mc.screen instanceof OptionsScreen && !state.originalOptions.isEmpty(), "Original Options layout was not observed");
        require(widgets(mc.screen).stream().noneMatch(widget -> widget.getMessage().getString().equals(inGameName())),
                "Craftable still occupies the main Options page");
        for (var entry : state.originalOptions.entrySet())
            require(entry.getValue().equals(new Bounds(entry.getKey().getX(), entry.getKey().getY(),
                    entry.getKey().getWidth(), entry.getKey().getHeight())), "Settings insertion moved an original option: "
                    + entry.getKey().getMessage().getString() + " expected=" + entry.getValue() + " actual="
                    + new Bounds(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getWidth(), entry.getKey().getHeight()));
    }

    static void verifyAccessibility(SettingsScenarioState state, Minecraft mc, boolean visible) throws Exception {
        require(mc.screen instanceof AccessibilityOptionsScreen, "Settings entry is not inside accessibility");
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var entry = button(mc.screen, inGameName());
        var rows = ((ContainerEventHandler) list).children();
        require(rows.size() == state.expectedAccessibilityRows && widgets(list).stream()
                .filter(widget -> widget.getMessage().getString().equals(inGameName())).count() == 1,
                "Accessibility has missing native rows or duplicate settings entries");
        require(widgets((ContainerEventHandler) rows.getLast()).contains(entry),
                "Craftable is not a native scrollable list row");
        var lastWidgets = ((ContainerEventHandler) rows.getLast()).children();
        require(state.accessibilityTrailingLeft == null ? lastWidgets.size() == 1 && lastWidgets.getFirst() == entry
                : lastWidgets.size() == 2 && lastWidgets.getFirst() == state.accessibilityTrailingLeft && lastWidgets.getLast() == entry,
                "Craftable did not fill the available trailing slot without displacing its native left-hand option");
        require(list.findOption(mc.options.hideSplashTexts()) != null && list.findOption(mc.options.narratorHotkey()) != null,
                "Settings entry displaced vanilla accessibility options");
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        var guide = button(mc.screen, Component.translatable("options.accessibility.link").getString());
        require(done.getY() == guide.getY() && done.getX() == guide.getX() + guide.getWidth() + 8
                && done.getWidth() == 150 && guide.getWidth() == 150,
                "Settings entry changed the native accessibility footer");
        require(entry.active && entry.getTooltip() != null && entry.getWidth() == 150,
                "Settings list entry lacks native focus/tooltip state");
        if (visible) require(entry.getX() == mc.screen.width / 2 + (state.accessibilityTrailingLeft == null ? -155 : 5)
                && entry.getY() >= list.getY()
                && entry.getY() + entry.getHeight() <= list.getBottom() && entry.getY() + entry.getHeight() < done.getY(),
                "Scrolled settings entry is clipped or overlaps the native footer");
    }

    static void verifyAccessibilityAppendBoundaries(Minecraft mc) {
        var parent = new AccessibilityOptionsScreen(null, mc.options);
        parent.width = mc.getWindow().getGuiScaledWidth(); parent.height = mc.getWindow().getGuiScaledHeight();
        var empty = new OptionsList(mc, parent.width, parent);
        AmbientInventoryEvents.appendSettingsButton(parent, empty);
        require(((ContainerEventHandler) empty).children().size() == 1 && rowWidgets(empty).size() == 1,
                "Appending to an empty native list did not create one left-hand settings slot");

        var draft = new org.craftablemc.craftable.config.ConfigSettings.Draft(CraftableServerConfig.SPEC);
        var slider = NativeConfigOption.create(serverSteps(), draft);
        var single = new OptionsList(mc, parent.width, parent);
        single.addSmall(slider.option());
        var originalRow = ((ContainerEventHandler) single).children().getFirst();
        var originalSlider = single.findOption(slider.option());
        AmbientInventoryEvents.appendSettingsButton(parent, single);
        require(((ContainerEventHandler) single).children().size() == 1
                && ((ContainerEventHandler) single).children().getFirst() == originalRow
                && ((ContainerEventHandler) originalRow).children().getFirst() == originalSlider
                && ((ContainerEventHandler) originalRow).children().size() == 2
                && single.findOption(slider.option()) == originalSlider,
                "Filling a trailing native option row lost its identity, option mapping or original control");
        AmbientInventoryEvents.appendSettingsButton(parent, single);
        require(((ContainerEventHandler) single).children().size() == 1
                && ((ContainerEventHandler) originalRow).children().size() == 2,
                "Appending accessibility settings twice duplicated the entry");
        var range = serverSteps().getSpec().<Integer>getRange();
        boolean maximum = !slider.option().get().equals(range.getMax());
        int x = originalSlider.getX() + (maximum ? originalSlider.getWidth() - 4 : 4);
        var input = mc.getLastInputType(); mc.setLastInputType(InputType.MOUSE);
        require(originalSlider.mouseClicked(x, originalSlider.getY() + 5, 0), "Synthetic native slider ignored its actual click");
        originalSlider.mouseReleased(x, originalSlider.getY() + 5, 0);
        single.applyUnsavedChanges();
        mc.setLastInputType(input);
        require(slider.option().get().equals(maximum ? range.getMax() : range.getMin())
                && draft.get(serverSteps()).equals(slider.option().get()),
                "Filling a trailing slot lost the native delayed-slider applyUnsavedChanges mapping");

        var leftOption = OptionInstance.createBoolean("options.hideSplashTexts", false);
        var rightOption = OptionInstance.createBoolean("options.narrator_hotkey", true);
        var full = new OptionsList(mc, parent.width, parent); full.addSmall(leftOption, rightOption);
        var originalFullRow = ((ContainerEventHandler) full).children().getFirst();
        var left = full.findOption(leftOption); var right = full.findOption(rightOption);
        AmbientInventoryEvents.appendSettingsButton(parent, full);
        require(((ContainerEventHandler) full).children().size() == 2
                && ((ContainerEventHandler) full).children().getFirst() == originalFullRow
                && ((ContainerEventHandler) originalFullRow).children().size() == 2
                && full.findOption(leftOption) == left && full.findOption(rightOption) == right,
                "Appending after a full native row displaced its existing option mappings");
        require(widgets(((ContainerEventHandler) full).children().getLast()).size() == 1,
                "A full trailing row did not fall back to a new left-hand slot");

        var big = new OptionsList(mc, parent.width, parent); big.addBig(leftOption);
        var originalBig = big.findOption(leftOption);
        AmbientInventoryEvents.appendSettingsButton(parent, big);
        require(((ContainerEventHandler) big).children().size() == 2 && big.findOption(leftOption) == originalBig
                && originalBig.getWidth() == 310 && ((ContainerEventHandler) ((ContainerEventHandler) big).children()
                    .getFirst()).children().size() == 1,
                "A full-width trailing widget was incorrectly treated as an empty right-hand slot");

        var narrow = new OptionsList(mc, parent.width, parent);
        var narrowButton = Button.builder(Component.literal("Narrow native slot"), button -> {}).width(120).build();
        narrow.addSmall(narrowButton, null);
        AmbientInventoryEvents.appendSettingsButton(parent, narrow);
        require(((ContainerEventHandler) narrow).children().size() == 1
                && ((ContainerEventHandler) ((ContainerEventHandler) narrow).children().getFirst()).children().size() == 2,
                "A valid narrow trailing widget was not kept while filling the right-hand slot");

        var emptyRow = new OptionsList(mc, parent.width, parent);
        emptyRow.addSmall(Button.builder(Component.literal("Empty row fixture"), button -> {}).width(150).build(), null);
        ((OptionsListEntryAccessor) ((ContainerEventHandler) emptyRow).children().getFirst()).craftable$setWidgets(List.of());
        AmbientInventoryEvents.appendSettingsButton(parent, emptyRow);
        require(((ContainerEventHandler) emptyRow).children().size() == 2
                && ((ContainerEventHandler) ((ContainerEventHandler) emptyRow).children().getFirst()).children().isEmpty(),
                "An unusual empty trailing row was not safely preserved with new-row fallback");
    }

    static void reopenMain(SettingsScenarioState state, Minecraft mc) {
        require(mc.screen instanceof AccessibilityOptionsScreen, "Personal settings did not return to accessibility");
        // 中文：GUI 缩放返回后列表命中框立即变位，子控件坐标却由 Entry.render 赋值；须等真实渲染再点击。
        // Returning after changing GUI scale repositions the native list's
        // hitboxes immediately, but Entry.render assigns child coordinates.
        // Wait for actual render events before using those child coordinates.
        deferUntilRendered(state, mc.screen, () -> {
            try {
                verifyAccessibility(state, mc, true);
                click(mc.screen, inGameName());
                require(mc.screen instanceof CraftableSettingsScreen, "Accessibility entry did not reopen the flattened main page");
            } catch (Exception error) { throw new AssertionError("Deferred accessibility navigation failed", error); }
        });
    }

    static void openWorldAfterRender(SettingsScenarioState state, Minecraft mc) {
        deferUntilRendered(state, mc.screen, () -> {
            try { click(mc.screen, (Button) read(mc.screen, UiField.WORLD_ENTRY)); }
            catch (Exception error) { throw new AssertionError("Deferred ordinary world navigation failed", error); }
        });
    }

    static void openReadOnlyWorld(SettingsScenarioState state, Minecraft mc) throws Exception {
        mc.setScreen(readOnlyWorld(state.home));
    }

    enum EntryStep {
        OPEN_OPTIONS, OPEN_ACCESSIBILITY, SCROLL_ACCESSIBILITY, CAPTURE_ACCESSIBILITY, FOCUS_ENTRY, REOPEN_ACCESSIBILITY, OPEN_WITH_MOUSE, OPEN_WITH_KEYBOARD;
        EntryStep next() { return values()[ordinal() + 1]; }
    }

}
