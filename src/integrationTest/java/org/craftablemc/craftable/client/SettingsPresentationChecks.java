package org.craftablemc.craftable.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsCapture.capture;
import static org.craftablemc.craftable.client.SettingsCapture.captureNumericTooltip;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.openReadOnlyWorld;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.openWorldAfterRender;
import static org.craftablemc.craftable.client.SettingsRulesChecks.rulesReady;
import static org.craftablemc.craftable.client.SettingsRulesChecks.verifyWorldPage;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：按注册元数据及真实字体验证双语/多视口几何，避免测试复制手写范围或以固定文案替代真实排版边界。
 * English: Checks metadata-derived controls and real-font geometry across languages/viewports without duplicating handwritten bounds or replacing real layout boundaries with fixed copy. */
final class SettingsPresentationChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.CHINESE_PERSONAL_LAYOUT && state.languageReadyTicks > 4) {
            require(mc.getLanguageManager().getSelected().equals("zh_cn"), "Chinese resources did not finish loading");
            verifySectionTranslations();
            verifyPersonalPresentation(mc);
            capture(state, mc, "settings-native-home-zh-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.CHINESE_PERSONAL_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, nativeControl(mc.screen, "doublePressMillis"),
                    "settings-native-double-press-numeric-tooltip-zh-20261021-refactor", () -> {
                        state.home = mc.screen; focusPartialPolicyTooltip(state, mc); state.step = SettingsStep.CAPTURE_CHINESE_MODE;
                    });
            return false;
        }
        if (state.step == SettingsStep.CAPTURE_CHINESE_MODE && state.age > 2) {
            capture(state, mc, "settings-native-partial-policy-tooltip-zh-20261021-refactor");
            state.next(); return false;
        }
        if (state.step == SettingsStep.OPEN_CHINESE_RULES && state.captured) {
            state.tooltipFocus.setFocused(false); state.tooltipFocus = null;
            openReadOnlyWorld(state, mc);
            state.step = SettingsStep.CHINESE_RULES_LAYOUT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.CHINESE_RULES_LAYOUT && state.age > 2 && rulesReady(mc, state.oldSteps, state.oldRadius)) {
            verifyWorldPage(mc, state.oldSteps);
            capture(state, mc, "settings-native-world-view-zh-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.CHINESE_RULES_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, readOnlyNumericControl(mc.screen, "maxPlanSteps"),
                    "settings-native-read-only-steps-numeric-tooltip-zh-20261021-refactor", () -> {
                        mc.screen.keyPressed(256, 0, 0); openWorldAfterRender(state, mc); state.step = SettingsStep.CHINESE_WORLD_LAYOUT;
                    });
            return false;
        }
        if (state.step == SettingsStep.CHINESE_WORLD_LAYOUT && state.age > 2) {
            verifyConfigPresentation(mc);
            capture(state, mc, "settings-native-world-editor-zh-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.CHINESE_WORLD_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, nativeControl(mc.screen, "maxPlanSteps"),
                    "settings-native-editable-steps-numeric-tooltip-zh-20261021-refactor", () -> {
                        captureNumericTooltip(state, mc, nativeControl(mc.screen, "directPreparationMode"),
                                "settings-native-world-mode-tooltip-zh-20261021-refactor", () -> {
                                    state.policyWorld = mc.screen; click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.CAPTURE_WHITELIST_CHINESE;
                                });
                    });
            return false;
        }
        if (state.step == SettingsStep.ENGLISH_PERSONAL_LAYOUT && state.languageReadyTicks > 4) {
            require(mc.getLanguageManager().getSelected().equals("en_us"), "English resources did not finish loading");
            verifySectionTranslations();
            verifyPersonalPresentation(mc);
            capture(state, mc, "settings-native-home-en-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.ENGLISH_PERSONAL_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, nativeControl(mc.screen, "doublePressMillis"),
                    "settings-native-double-press-numeric-tooltip-en-20261021-refactor", () -> {
                        state.home = mc.screen; focusPartialPolicyTooltip(state, mc); state.step = SettingsStep.CAPTURE_ENGLISH_MODE;
                    });
            return false;
        }
        if (state.step == SettingsStep.CAPTURE_ENGLISH_MODE && state.age > 2) {
            capture(state, mc, "settings-native-partial-policy-tooltip-en-20261021-refactor");
            state.next(); return false;
        }
        if (state.step == SettingsStep.OPEN_ENGLISH_WORLD && state.captured) {
            state.tooltipFocus.setFocused(false); state.tooltipFocus = null;
            click(mc.screen, (Button) read(mc.screen, UiField.WORLD_ENTRY));
            state.step = SettingsStep.ENGLISH_WORLD_LAYOUT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.ENGLISH_WORLD_LAYOUT && state.age > 2) {
            verifyConfigPresentation(mc);
            capture(state, mc, "settings-native-world-editor-en-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.ENGLISH_WORLD_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, nativeControl(mc.screen, "maxPlanSteps"),
                    "settings-native-editable-steps-numeric-tooltip-en-20261021-refactor", () -> {
                        state.policyWorld = mc.screen; click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.CAPTURE_WHITELIST_ENGLISH;
                    });
            return false;
        }
        if (state.step == SettingsStep.ENGLISH_RULES_LAYOUT && rulesReady(mc, state.oldSteps, state.oldRadius)) {
            verifyWorldPage(mc, state.oldSteps);
            capture(state, mc, "settings-native-world-view-en-20261021-refactor"); state.next(); return false;
        }
        if (state.step == SettingsStep.ENGLISH_RULES_TOOLTIP && state.captured) {
            captureNumericTooltip(state, mc, readOnlyNumericControl(mc.screen, "maxPlanSteps"),
                    "settings-native-read-only-steps-numeric-tooltip-en-20261021-refactor", () -> {
                        state.policyWorld = mc.screen; click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.CAPTURE_READONLY_WHITELIST_ENGLISH;
                    });
            return false;
        }
        return false;
    }

    static void verifySectionTranslations() {
        for (String section : List.of("presentation", "environment", "crafting")) {
            String key = "craftable.configuration." + section + ".button";
            require(net.minecraft.locale.Language.getInstance().has(key)
                    && !Component.translatable(key).getString().isBlank(), "Missing native config button translation: " + key);
        }
    }

    static void verifyPersonalPresentation(Minecraft mc) throws Exception {
        require(read(mc.screen, UiField.PAGE).toString().equals("MAIN"), "Personal options still require a redundant second page");
        verifyConfigPresentation(mc);
        verifyPersonalModeCycle(mc);
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var hints = rowWidgets(list).stream().filter(StringWidget.class::isInstance).toList();
        require(hints.size() == 1 && hints.getFirst().getMessage().getString().equals(translated("preferences_only"))
                && hints.getFirst().getY() > ((Button) read(mc.screen, UiField.ADVANCED)).getY(),
                "Main settings preference explanation is missing or no longer refers to the options above it");
        require(mc.font.width(hints.getFirst().getMessage()) <= hints.getFirst().getWidth(),
                "Main settings trailing preference explanation is clipped: " + hints.getFirst().getMessage().getString());
        var world = (Button) read(mc.screen, UiField.WORLD_ENTRY);
        var advanced = (Button) read(mc.screen, UiField.ADVANCED);
        require(world.getWidth() == 150 && advanced.getWidth() == 150 && world.getY() == advanced.getY(),
                "Navigation does not use a native half-width pair");
        require(mc.font.width(world.getMessage()) <= 138 && mc.font.width(advanced.getMessage()) <= 138,
                "Half-width navigation captions are clipped");
        require(widgets(mc.screen).stream().filter(w -> w instanceof Button
                && w.getMessage().equals(CommonComponents.GUI_DONE)).count() == 1, "Defaults accumulated native footer widgets");
    }

    static void verifyWorldWhitelistLastRow(Minecraft mc) throws Exception {
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var entry = (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY);
        var draft = (ConfigSettings.Draft) read(mc.screen, UiField.DRAFT);
        var left = draft == null ? (AbstractWidget) ((List<?>) read(mc.screen, UiField.RULE_ROWS)).getLast()
                : list.findOption(binding(mc.screen, "maxPlanSteps").option());
        var rows = ((ContainerEventHandler) list).children();
        var trailing = widgets(rows.getLast());
        require(left != null && trailing.size() == 2 && trailing.getFirst() == left && trailing.getLast() == entry
                && entry.getX() == left.getX() + 160 && entry.getY() == left.getY() && entry.getWidth() == 150,
                "World whitelist navigation did not fill the final scalar row's unused right slot while preserving its original widget"
                        + "; draft=" + (draft != null) + "; rows=" + rows.size()
                        + "; trailing=" + trailing.stream().map(SettingsUiAccess::bounds).toList()
                        + "; originalLeft=" + (left == null ? "null" : bounds(left))
                        + "; entry=" + bounds(entry)
                        + "; sameLeft=" + (!trailing.isEmpty() && trailing.getFirst() == left)
                        + "; sameEntry=" + (!trailing.isEmpty() && trailing.getLast() == entry));
    }

    static void verifyPersonalModeCycle(Minecraft mc) throws Exception {
        var option = binding(mc.screen, "partialPolicy");
        var registered = option.configValue();
        var current = (DirectPreparationPolicy.Mode) option.option().get();
        var candidates = NativeConfigOption.candidates(current, registered.getSpec());
        require(candidates.size() == 4 && ConfigSettings.values(CraftableClientConfig.SPEC).stream()
                .noneMatch(value -> value.getDefault() instanceof List<?>),
                "Personal partial crafting did not reuse the four world modes without creating another whitelist");
        var raw = registered.getRaw();
        var beforeDraft = ((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot();
        int originalIndex = candidates.indexOf(current);
        for (int step = 1; step <= candidates.size(); step++) {
            var control = (OptionsList) read(mc.screen, UiField.LIST);
            var widget = control.findOption(option.option());
            require(widget != null && widget.active, "Personal four-mode native cycle control is unavailable");
            mc.setLastInputType(InputType.MOUSE);
            require(mc.screen.mouseClicked(widget.getX() + 5, widget.getY() + 5, 0),
                    "Personal native mode cycle ignored an actual click");
            mc.screen.mouseReleased(widget.getX() + 5, widget.getY() + 5, 0);
            require(option.option().get() == candidates.get((originalIndex + step) % candidates.size()),
                    "Personal native setting did not use its shared four-mode click order");
            require(registered.getRaw().equals(raw), "Personal native cycle persisted before Done");
        }
        require(((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot().equals(beforeDraft),
                "A complete four-mode personal cycle failed to restore the original draft");
    }

    static void focusPartialPolicyTooltip(SettingsScenarioState state, Minecraft mc) throws Exception {
        mc.screen.clearFocus();
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        state.tooltipFocus = list.findOption(binding(mc.screen, "partialPolicy").option());
        require(state.tooltipFocus != null && state.tooltipFocus.getTooltip() != null, "Missing native multi-choice tooltip for screenshot");
        mc.setLastInputType(InputType.KEYBOARD_TAB);
        state.tooltipFocus.setFocused(true);
    }

    static void verifyConfigPresentation(Minecraft mc) throws Exception {
        var bindings = configOptions(mc.screen);
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var draft = (org.craftablemc.craftable.config.ConfigSettings.Draft) read(mc.screen, UiField.DRAFT);
        var beforeDraft = draft.snapshot();
        boolean world = read(mc.screen, UiField.PAGE).toString().equals("WORLD");
        var spec = world ? CraftableServerConfig.SPEC : CraftableClientConfig.SPEC;
        var expected = NativeConfigOption.definitions(spec).stream().filter(NativeConfigOption::scalar).toList();
        require(draft != null && bindings.stream().map(NativeConfigOption::configValue).toList().equals(expected),
                "Native controls do not preserve the registered scalar identities/order");
        verifyEditableFooter(mc);
        if (world) {
            require(read(mc.screen, UiField.PAGE).toString().equals("WORLD") && read(mc.screen, UiField.RETRY) == null
                    && ((ContainerEventHandler) list).children().size() == 4 && rowWidgets(list).size() == 8,
                    "Editable native world rules retained a redundant status label or reload control");
            require(button(mc.screen, CommonComponents.GUI_DONE.getString()).getWidth() == 100
                    && button(mc.screen, CommonComponents.GUI_CANCEL.getString()).getWidth() == 100
                    && button(mc.screen, translated("defaults")).getWidth() == 100,
                    "Removing editor status/reload changed its save/cancel/default footer");
            require(ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                    .noneMatch(value -> value.getPath().getLast().equals("partialExecution")),
                    "Merged partial policy retained a second server partialExecution setting");
            require(bindings.stream().filter(binding -> binding.configValue().getPath().getLast().equals("directPreparationMode"))
                    .count() == 1, "Native world page did not expose exactly one merged partial-crafting mode");
            verifyWorldWhitelistLastRow(mc);
        }
        for (var binding : bindings) {
            var value = binding.configValue();
            var option = binding.option();
            var widget = list.findOption(option);
            require(spec.getValues().get(value.getPath()) == value,
                    "Native settings introduced a duplicate config definition: " + value.getPath());
            require(!value.getPath().getLast().equals("diagnosticCacheTicks"), "Ordinary settings exposed diagnostic TTL");
            require(widget != null && widget.active && widget.getWidth() == 150 && widget.getTooltip() != null,
                    "Config control does not use a vanilla half-width widget: " + value.getPath());
            if (SettingTooltip.hasNumericRange(value)) verifyNumericTooltip(widget, value, mc, "native editable");
            require(widget.getX() == mc.screen.width / 2 - 155 || widget.getX() == mc.screen.width / 2 + 5,
                    "Config control is not aligned to the native two-column grid: " + value.getPath());
            var candidates = new ArrayList<Object>();
            candidates.add(option.get());
            if (value.getDefault() instanceof Boolean) { candidates.add(false); candidates.add(true); }
            else if (value.getDefault() instanceof Enum<?> enumeration)
                for (var candidate : enumeration.getDeclaringClass().getEnumConstants())
                    if (value.getSpec().test(candidate)) candidates.add(candidate);
            else if (value.getDefault() instanceof Integer) {
                candidates.add(value.getSpec().<Integer>getRange().getMin());
                candidates.add(value.getSpec().<Integer>getRange().getMax());
            }
            if (value.getDefault() instanceof Enum<?>) {
                String key = value.getSpec().getTranslationKey() + ".tooltip";
                String description = Component.translatable(key).getString();
                var paragraphs = description.lines().toList();
                require(paragraphs.size() >= candidates.stream().distinct().count(),
                        "Multi-option config description still runs its choices together: " + key);
                if (value.getDefault() instanceof DirectPreparationPolicy.Mode) verifyFourModeParagraphs(value);
                String rendered = tooltipText(widget, mc);
                require(paragraphs.stream().allMatch(line -> rendered.contains(tooltipText(Tooltip.create(Component.literal(line)), mc))),
                        "Native tooltip lost explicit choice paragraphs: " + key);
                verifyNoIsolatedClosingPunctuation(widget, mc, key);
            }
            Object old = option.get();
            for (var candidate : candidates) {
                setOption(option, candidate);
                var candidateWidget = option.createButton(mc.options, 0, 0, 150);
                String caption = Component.translatable(value.getSpec().getTranslationKey()).getString();
                String message = candidateWidget.getMessage().getString();
                require(message.contains(caption) && message.indexOf(caption) == message.lastIndexOf(caption),
                        "Native option omitted or duplicated its canonical caption: " + message);
                require(mc.font.width(candidateWidget.getMessage()) <= 138,
                        "Half-width candidate caption is clipped: " + value.getPath() + "=" + candidate + " (" + message + ")");
            }
            setOption(option, old);
        }
        require(draft.snapshot().equals(beforeDraft), "Presentation candidate sweep changed the user's draft");
        var heading = (AbstractWidget) read(mc.screen, UiField.HEADING);
        require(mc.font.width(mc.screen.getTitle()) <= heading.getWidth(), "Language reload clipped the settings title");
    }

    static String tooltipText(AbstractWidget widget, Minecraft mc) {
        require(widget.getTooltip() != null, "Expected a readable native tooltip");
        return tooltipText(widget.getTooltip(), mc);
    }

    static String tooltipText(Tooltip tooltip, Minecraft mc) {
        // 中文：实际/期望都按同一真实字体分行；Font.split 会丢换行处英文空格，拼接结果不能直接与原句比较。
        // Use the same real-font split for both actual and expected text.
        // Font.split drops a separating space at wrapped English lines;
        // concatenating those lines cannot be compared to the raw sentence.
        return String.join("", tooltipLines(tooltip, mc));
    }

    static List<String> tooltipLines(Tooltip tooltip, Minecraft mc) {
        return tooltip.toCharSequence(mc).stream().map(line -> {
            var result = new StringBuilder();
            line.accept((index, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
            return result.toString();
        }).toList();
    }

    static void verifyNumericTooltip(AbstractWidget widget, ModConfigSpec.ConfigValue<?> value,
            Minecraft mc, String presentation) throws Exception {
        require(widget.getTooltip() != null, "Missing numeric tooltip in " + presentation + ": " + value.getPath());
        var message = (Component) read(widget.getTooltip(), UiField.MESSAGE);
        var range = value.getSpec().getRange();
        String rangeLine = Component.translatable("screen.craftable.settings.range",
                String.valueOf(range.getMin()), String.valueOf(range.getMax())).getString();
        String defaultLine = Component.translatable("screen.craftable.settings.default_value", String.valueOf(value.getDefault())).getString();
        String footer = "\n\n" + rangeLine + "\n" + defaultLine;
        String raw = message.getString();
        require(raw.endsWith(footer) && raw.indexOf(rangeLine) == raw.lastIndexOf(rangeLine)
                && raw.indexOf(defaultLine) == raw.lastIndexOf(defaultLine),
                "Numeric tooltip did not end with one blank-line metadata range/default footer in " + presentation
                        + ": " + value.getPath() + "; expected=" + footer + "; actual=" + raw);
        var keys = new ArrayList<String>(); collectTranslationKeys(message, keys);
        require(keys.stream().filter("screen.craftable.settings.range"::equals).count() == 1
                && keys.stream().filter("screen.craftable.settings.default_value"::equals).count() == 1
                && keys.stream().noneMatch("neoforge.configuration.uitext.rangetooltip"::equals),
                "Numeric footer was hardcoded, duplicated, or retained NeoForge's automatic range in " + presentation
                        + ": " + value.getPath() + "; keys=" + keys);
        int footerStart = raw.length() - footer.length();
        var offset = new int[] { 0 };
        var gray = net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GRAY);
        message.visit((net.minecraft.network.chat.FormattedText.StyledContentConsumer<Void>) (style, contents) -> {
            int start = offset[0]; offset[0] += contents.length();
            if (offset[0] > footerStart && !contents.substring(Math.max(0, footerStart - start)).isBlank())
                require(gray.equals(style.getColor()), "Numeric metadata footer lost gray style in " + presentation
                        + ": " + value.getPath() + "; text=" + contents + "; style=" + style);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        require(offset[0] == raw.length(), "Numeric tooltip style traversal skipped translated metadata arguments");
        verifyNoIsolatedClosingPunctuation(widget, mc, value.getSpec().getTranslationKey() + ".tooltip");
    }

    static void verifyFourModeParagraphs(ModConfigSpec.ConfigValue<?> value) {
        var paragraphs = Component.translatable(value.getSpec().getTranslationKey() + ".tooltip").getString().lines().toList();
        var modes = NativeConfigOption.candidates((DirectPreparationPolicy.Mode) value.getDefault(), value.getSpec());
        require(paragraphs.size() == modes.size() + 1, "Policy tooltip must describe each registered mode plus one tightening-rule line: " + value.getPath());
        for (int index = 0; index < modes.size(); index++)
            require(paragraphs.get(index).startsWith(NativeConfigOption.display(value, modes.get(index)).getString()),
                    "Policy tooltip no longer keeps each of its four options on a separate matching line: " + value.getPath());
        if (Minecraft.getInstance().getLanguageManager().getSelected().equals("zh_cn"))
            require(paragraphs.stream().allMatch(line -> line.endsWith("。")),
                    "Chinese personal/world policy tooltip has a line without a final full stop: " + value.getPath());
        require(!paragraphs.getLast().isBlank(), "Policy tooltip lost its tightening-rule explanation");
    }

    static void collectTranslationKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated) {
            keys.add(translated.getKey());
            for (var argument : translated.getArgs()) if (argument instanceof Component child) collectTranslationKeys(child, keys);
        }
        for (var child : component.getSiblings()) collectTranslationKeys(child, keys);
    }

    static void verifyProfessionalNumericTooltips(Minecraft mc, ModConfigSpec spec, String section) throws Exception {
        var numeric = ConfigSettings.values(spec).stream().filter(SettingTooltip::hasNumericRange)
                .filter(value -> value.getPath().getFirst().equals(section)).toList();
        require(!numeric.isEmpty(), "Professional numeric fixture selected an empty registered section: " + section);
        for (var value : numeric) {
            var label = professionalLabel(mc.screen, value.getSpec().getTranslationKey());
            var control = professionalControl(mc.screen, value.getSpec().getTranslationKey());
            verifyNumericTooltip(label, value, mc, "professional label");
            verifyNumericTooltip(control, value, mc, "professional control");
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    static void verifyNoIsolatedClosingPunctuation(AbstractWidget widget, Minecraft mc, String key) {
        if (!mc.getLanguageManager().getSelected().equals("zh_cn")) return;
        var lines = tooltipLines(widget.getTooltip(), mc);
        String closing = "。，；：！？、）】〕〉》」』．,.;:!?)]}";
        var isolated = lines.stream().map(String::strip).filter(line -> !line.isEmpty()
                && line.codePoints().allMatch(codePoint -> closing.indexOf(codePoint) >= 0)).toList();
        require(isolated.isEmpty(), "Actual 170px Chinese tooltip has isolated closing punctuation: " + key
                + "; isolated=" + isolated + "; visualLines=" + lines);
    }

    static void verifyEditableFooter(Minecraft mc) throws Exception {
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var cancel = button(mc.screen, CommonComponents.GUI_CANCEL.getString());
        var defaults = button(mc.screen, translated("defaults"));
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        require(cancel.getWidth() == 100 && defaults.getWidth() == 100 && done.getWidth() == 100
                && cancel.getY() == defaults.getY() && defaults.getY() == done.getY()
                && cancel.getX() + cancel.getWidth() + 5 == defaults.getX()
                && defaults.getX() + defaults.getWidth() + 5 == done.getX()
                && done.getY() >= list.getBottom()
                && !rowWidgets(list).contains(cancel) && !rowWidgets(list).contains(defaults) && !rowWidgets(list).contains(done),
                "Editable native footer must keep Cancel / Defaults / Done left-to-right with Done on the right");
        require(mc.screen.children().indexOf(cancel) >= 0
                && mc.screen.children().indexOf(cancel) < mc.screen.children().indexOf(defaults)
                && mc.screen.children().indexOf(defaults) < mc.screen.children().indexOf(done),
                "Editable footer listener registration does not follow Cancel / Defaults / Done");
    }

    static void verifyProfessionalFooter(Minecraft mc) {
        // 中文：NeoForge 首按钮是撤销而非普通取消；调整完成位置不能改其原生撤销/保存生命周期。
        // NeoForge's first button is Undo, not ordinary Cancel. Its native
        // undo/save lifecycle must remain unchanged when aligning Done.
        var undo = button(mc.screen, ConfigurationScreen.UNDO.getString());
        var reset = button(mc.screen, ConfigurationScreen.RESET.getString());
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        // 中文：专业页三按钮使用原版 SMALL_WIDTH（1.21.1 为 120）及 8px 间距，不是普通编辑器有意缩窄的 100px 控件。
        // ConfigurationSectionScreen creates all three using the actual
        // vanilla Button.SMALL_WIDTH (120 in 1.21.1), not our native editor's
        // deliberately narrower 100px controls, and arranges them with 8px.
        require(undo.getWidth() == Button.SMALL_WIDTH && reset.getWidth() == Button.SMALL_WIDTH
                && done.getWidth() == Button.SMALL_WIDTH
                && undo.getY() == reset.getY() && reset.getY() == done.getY()
                && undo.getX() + undo.getWidth() + 8 == reset.getX()
                && reset.getX() + reset.getWidth() + 8 == done.getX(),
                "Professional native footer must keep Undo / Reset / Done left-to-right with Done on the right"
                    + "; nativeSmallWidth=" + Button.SMALL_WIDTH + "; undo=" + bounds(undo)
                    + "; reset=" + bounds(reset) + "; done=" + bounds(done));
        require(mc.screen.children().indexOf(undo) >= 0
                && mc.screen.children().indexOf(undo) < mc.screen.children().indexOf(reset)
                && mc.screen.children().indexOf(reset) < mc.screen.children().indexOf(done),
                "Professional footer listener registration does not follow Undo / Reset / Done"
                    + "; indices=" + List.of(mc.screen.children().indexOf(undo), mc.screen.children().indexOf(reset),
                        mc.screen.children().indexOf(done))
                    + "; undo=" + bounds(undo) + "; reset=" + bounds(reset) + "; done=" + bounds(done));
    }
}
