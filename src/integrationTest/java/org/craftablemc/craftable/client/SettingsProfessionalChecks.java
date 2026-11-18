package org.craftablemc.craftable.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsCapture.capture;
import static org.craftablemc.craftable.client.SettingsCapture.captureNumericTooltip;
import static org.craftablemc.craftable.client.SettingsCapture.deferUntilRendered;
import static org.craftablemc.craftable.client.SettingsCapture.setLanguage;
import static org.craftablemc.craftable.client.SettingsFixture.restoreAndVerifyRecovery;
import static org.craftablemc.craftable.client.SettingsPersistenceChecks.personalDraft;
import static org.craftablemc.craftable.client.SettingsPersistenceChecks.verifySavedFile;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyEditableFooter;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyFourModeParagraphs;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyProfessionalFooter;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyProfessionalNumericTooltips;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：走真实 NeoForge 编辑/撤销，并保留过期模式/上下文回调反例，防止仅检查控件变灰而遗漏写入边界。
 * English: Exercises real NeoForge editing/Undo and stale mode/context callbacks so disabled styling cannot substitute for write-boundary coverage. */
final class SettingsProfessionalChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_CLIENT && state.age > 2) {
            var config = registered(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
            var professional = (ConfigurationScreen) mc.screen;
            click(mc.screen, Component.translatable("neoforge.configuration.uitext.section",
                    professional.translatableConfig(config, "", "neoforge.configuration.uitext.type.client")).getString());
            state.next(); return false;
        }
        if (state.step == SettingsStep.SELECT_PROFESSIONAL_CLIENT_SECTION && state.age > 2) {
            if (!state.professionalClientEnumsChecked) {
                clickSection(mc.screen, "craftable.configuration.crafting"); state.step = SettingsStep.PROFESSIONAL_PERSONAL_ENGLISH; state.age = 0; return false;
            }
            clickSection(mc.screen, "craftable.configuration.presentation"); state.next(); return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_PRESENTATION_LAYOUT && state.age > 2) {
            capture(state, mc, "settings-native-professional-presentation-en-20261021-refactor");
            state.step = SettingsStep.EDIT_PROFESSIONAL_FEEDBACK; state.age = 0; return false;
        }
        if (state.step == SettingsStep.EDIT_PROFESSIONAL_FEEDBACK && state.captured) {
            verifyProfessionalFooter(mc);
            clickLabeledValue(mc.screen, "config.craftable.client.detailed_failure_feedback");
            require(CraftableClientConfig.detailedFailureFeedbackEnabled() != state.before.detailedFailureFeedback(),
                    "NeoForge control did not edit the shared registered value");
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.LEAVE_PROFESSIONAL_PRESENTATION; state.age = 0; return false;
        }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_PRESENTATION && state.age > 2) { mc.screen.keyPressed(256, 0, 0); state.next(); return false; }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_CLIENT && state.age > 2) { mc.screen.keyPressed(256, 0, 0); state.next(); return false; }
        if (state.step == SettingsStep.RETURN_FROM_PROFESSIONAL && state.age > 2) {
            require(mc.screen instanceof CraftableSettingsScreen && read(mc.screen, UiField.PAGE).toString().equals("MAIN"),
                    "Professional settings did not return to the flattened main page");
            var refreshed = personalDraft(mc.screen);
            require(refreshed.recipeBookEnhancements() == state.saved.recipeBookEnhancements()
                    && refreshed.detailedFailureFeedback() == CraftableClientConfig.detailedFailureFeedbackEnabled(),
                    "Returning from professional settings lost a dirty draft or retained an unchanged stale value");
            require(CraftableClientConfig.recipeBookEnhancementsEnabled() == state.before.recipeBookEnhancements(),
                    "Opening professional settings committed an ordinary unsaved draft");
            verifySavedFile(SettingsFixture.preferences());
            if (!state.professionalPolicyChecked) {
                click(mc.screen, (Button) read(mc.screen, UiField.ADVANCED)); state.step = SettingsStep.OPEN_PROFESSIONAL_WORLD; state.age = 0; return false;
            }
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(CraftableClientConfig.detailedFailureFeedbackEnabled() != state.before.detailedFailureFeedback()
                    && CraftableClientConfig.recipeBookEnhancementsEnabled() == state.before.recipeBookEnhancements(),
                    "Ordinary Cancel undid professional saves or committed its own draft");
            restoreAndVerifyRecovery(state, mc);
            Craftable.LOGGER.warn("SETTINGS PASS: named navigation, authoritative rules/stale replies, native/professional draft persistence, single-whitelist authority and stale/expired callback guards, registered metadata identity/captions/ranges, multilingual font/layout/viewport diagnostics, and retryable fixture cleanup; 20261021-refactor screenshots retain the historical sample mapping. Injected context expiry is not actual LAN publication; remote/LAN/outside-world permissions require separate evidence");
            return true;
        }
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_WORLD && state.age > 2) {
            var config = registered(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
            var professional = (ConfigurationScreen) mc.screen;
            click(mc.screen, Component.translatable("neoforge.configuration.uitext.section",
                    professional.translatableConfig(config, "", "neoforge.configuration.uitext.type.server")).getString());
            state.step = SettingsStep.OPEN_PROFESSIONAL_ENVIRONMENT_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_ENVIRONMENT_ENGLISH && state.age > 2) {
            SettingsProfessionalGuardChecks.verifyNavigation(mc);
            clickSection(mc.screen, "craftable.configuration.environment"); state.step = SettingsStep.PROFESSIONAL_ENVIRONMENT_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_ENVIRONMENT_ENGLISH && state.age > 2) {
            SettingsProfessionalGuardChecks.verifyScalars(mc, "environment");
            verifyProfessionalNumericTooltips(mc, CraftableServerConfig.SPEC, "environment");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.server.diagnostic_cache_ticks"),
                    "settings-professional-ttl-numeric-tooltip-en-20261021-refactor", () -> {
                        mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.OPEN_PROFESSIONAL_CRAFTING_ENGLISH;
                    });
            return false;
        }
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_CRAFTING_ENGLISH && state.age > 2) {
            clickSection(mc.screen, "craftable.configuration.crafting"); state.step = SettingsStep.PROFESSIONAL_WORLD_WRITE_GUARDS; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_WORLD_WRITE_GUARDS && state.age > 2) {
            SettingsProfessionalGuardChecks.verifyScalars(mc, "crafting");
            SettingsProfessionalGuardChecks.verifyResetUndo(mc);
            // Reset 会重建列表；先让真实渲染排布新控件，再做逐行排版/点击检查，不读取尚未定位的控件坐标。
            // Reset rebuilds the list: allow real rendering to position its new widgets before row-layout/click checks, rather than reading unplaced coordinates.
            state.step = SettingsStep.PROFESSIONAL_WORLD_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_WORLD_ENGLISH && state.age > 2) {
            verifyProfessionalEnumCaptions(mc, CraftableServerConfig.SPEC);
            verifyProfessionalModeGuards(mc);
            verifyProfessionalNumericTooltips(mc, CraftableServerConfig.SPEC, "crafting");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.server.max_plan_steps"),
                    "settings-professional-steps-numeric-tooltip-en-20261021-refactor", () -> {
                        state.professionalListBefore = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
                        clickLabeledValue(mc.screen, "config.craftable.server.direct_preparation_allowed_recipes"); state.step = SettingsStep.CANCEL_PROFESSIONAL_IDS;
                    });
            return false;
        }
        if (state.step == SettingsStep.CANCEL_PROFESSIONAL_IDS && state.age > 2) {
            verifyEditableFooter(mc);
            require(read(mc.screen, UiField.VALUE) == directList("directPreparationAllowedRecipes"),
                    "Professional list control duplicated the setting identity");
            appendProfessionalUnknownId(mc);
            require(directList("directPreparationAllowedRecipes").getRaw().equals(state.professionalListBefore),
                    "Professional child editing bypassed its Done/undo lifecycle");
            deferUntilRendered(state, mc.screen, () -> capture(state, mc, "settings-professional-preparation-ids-en-20261021-refactor"));
            state.step = SettingsStep.CAPTURE_PROFESSIONAL_IDS; state.age = 0; return false;
        }
        if (state.step == SettingsStep.REOPEN_PROFESSIONAL_IDS && state.age > 2) {
            require(directList("directPreparationAllowedRecipes").getRaw().equals(state.professionalListBefore),
                    "Professional list Escape saved an unconfirmed child edit");
            clickLabeledValue(mc.screen, "config.craftable.server.direct_preparation_allowed_recipes");
            state.step = SettingsStep.SAVE_PROFESSIONAL_IDS; state.age = 0; return false;
        }
        if (state.step == SettingsStep.SAVE_PROFESSIONAL_IDS && state.age > 2) {
            appendProfessionalUnknownId(mc);
            verifyProfessionalChildModeGuard(mc);
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).contains("craftable:professional_settings_probe"),
                    "Professional list Done did not use its shared registered value");
            state.step = SettingsStep.PROFESSIONAL_CONTEXT_EXPIRY; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_CONTEXT_EXPIRY && state.age > 2) {
            verifyProfessionalFooter(mc);
            verifyProfessionalPolicyContextExpiry(mc);
            click(mc.screen, ConfigurationScreen.UNDO.getString());
            require(directList("directPreparationAllowedRecipes").getRaw().equals(state.professionalListBefore),
                    "Professional recipe list edit bypassed NeoForge's real undo buffer");
            setLanguage(mc, "zh_cn"); state.step = SettingsStep.PROFESSIONAL_WORLD_CHINESE; state.age = 0; state.languageReadyTicks = 0; return false;
        }
        if (state.step == SettingsStep.CAPTURE_PROFESSIONAL_IDS && state.captured) {
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.REOPEN_PROFESSIONAL_IDS; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_WORLD_CHINESE && state.languageReadyTicks > 4) {
            verifyProfessionalEnumCaptions(mc, CraftableServerConfig.SPEC);
            verifyProfessionalModeGuards(mc);
            verifyProfessionalNumericTooltips(mc, CraftableServerConfig.SPEC, "crafting");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.server.max_plan_steps"),
                    "settings-professional-steps-numeric-tooltip-zh-20261021-refactor", () -> {
                        deferUntilRendered(state, mc.screen, () -> capture(state, mc, "settings-professional-world-crafting-zh-20261021-refactor")); state.step = SettingsStep.LEAVE_PROFESSIONAL_WORLD_CHINESE;
                    });
            return false;
        }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_WORLD_CHINESE && state.captured) {
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.OPEN_PROFESSIONAL_ENVIRONMENT_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_PERSONAL_ENGLISH && state.age > 2) {
            verifyProfessionalEnumCaptions(mc, CraftableClientConfig.SPEC);
            verifyProfessionalNumericTooltips(mc, CraftableClientConfig.SPEC, "crafting");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.client.double_press_millis"),
                    "settings-professional-double-press-numeric-tooltip-en-20261021-refactor", () -> {
                        mc.screen.keyPressed(256, 0, 0); state.professionalClientEnumsChecked = true; state.step = SettingsStep.SELECT_PROFESSIONAL_CLIENT_SECTION;
                    });
            return false;
        }
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_ENVIRONMENT_CHINESE && state.age > 2) {
            clickSection(mc.screen, "craftable.configuration.environment"); state.step = SettingsStep.PROFESSIONAL_ENVIRONMENT_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_ENVIRONMENT_CHINESE && state.age > 2) {
            verifyProfessionalNumericTooltips(mc, CraftableServerConfig.SPEC, "environment");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.server.diagnostic_cache_ticks"),
                    "settings-professional-ttl-numeric-tooltip-zh-20261021-refactor", () -> {
                        mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.LEAVE_PROFESSIONAL_ENVIRONMENT_CHINESE;
                    });
            return false;
        }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_ENVIRONMENT_CHINESE && state.age > 2) {
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.REOPEN_PROFESSIONAL_CLIENT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.REOPEN_PROFESSIONAL_CLIENT && state.age > 2) {
            var config = registered(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
            var professional = (ConfigurationScreen) mc.screen;
            click(mc.screen, Component.translatable("neoforge.configuration.uitext.section",
                    professional.translatableConfig(config, "", "neoforge.configuration.uitext.type.client")).getString());
            state.step = SettingsStep.OPEN_PROFESSIONAL_PERSONAL_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.OPEN_PROFESSIONAL_PERSONAL_CHINESE && state.age > 2) {
            clickSection(mc.screen, "craftable.configuration.crafting"); state.step = SettingsStep.PROFESSIONAL_PERSONAL_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PROFESSIONAL_PERSONAL_CHINESE && state.age > 2) {
            verifyProfessionalEnumCaptions(mc, CraftableClientConfig.SPEC);
            verifyProfessionalNumericTooltips(mc, CraftableClientConfig.SPEC, "crafting");
            captureNumericTooltip(state, mc, professionalControl(mc.screen, "config.craftable.client.double_press_millis"),
                    "settings-professional-double-press-numeric-tooltip-zh-20261021-refactor", () -> {
                        deferUntilRendered(state, mc.screen, () -> capture(state, mc, "settings-professional-personal-crafting-zh-20261021-refactor")); state.step = SettingsStep.LEAVE_PROFESSIONAL_PERSONAL_CHINESE;
                    });
            return false;
        }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_PERSONAL_CHINESE && state.captured) {
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.LEAVE_PROFESSIONAL_CLIENT_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.LEAVE_PROFESSIONAL_CLIENT_CHINESE && state.age > 2) {
            mc.screen.keyPressed(256, 0, 0); state.step = SettingsStep.LOAD_PROFESSIONAL_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.LOAD_PROFESSIONAL_ENGLISH && state.age > 2) {
            mc.screen.keyPressed(256, 0, 0);
            setLanguage(mc, "en_us"); state.step = SettingsStep.RETURN_PROFESSIONAL_ENGLISH; state.age = 0; state.languageReadyTicks = 0; return false;
        }
        if (state.step == SettingsStep.RETURN_PROFESSIONAL_ENGLISH && state.languageReadyTicks > 4) {
            state.professionalPolicyChecked = true; state.step = SettingsStep.RETURN_FROM_PROFESSIONAL; state.age = 0; return false;
        }
        return false;
    }

    static void appendProfessionalUnknownId(Minecraft mc) throws Exception {
        click(mc.screen, (Button) read(mc.screen, UiField.ADD));
        require(!button(mc.screen, CommonComponents.GUI_DONE.getString()).active,
                "Professional list Done remained enabled for an invalid incomplete recipe ID");
        var boxes = (List<?>) read(mc.screen, UiField.BOXES);
        ((net.minecraft.client.gui.components.EditBox) boxes.getLast()).setValue("craftable:professional_settings_probe");
        require(button(mc.screen, CommonComponents.GUI_DONE.getString()).active,
                "Professional list rejected a valid explicit unknown recipe ID");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void verifyProfessionalEnumCaptions(Minecraft mc, ModConfigSpec spec) throws Exception {
        var values = ConfigSettings.values(spec).stream().filter(value -> value.getDefault() instanceof Enum<?>).toList();
        for (var value : values) {
            if (value.getDefault() instanceof DirectPreparationPolicy.Mode) verifyFourModeParagraphs(value);
            var widget = professionalControl(mc.screen, value.getSpec().getTranslationKey());
            require(widget instanceof net.minecraft.client.gui.components.CycleButton<?>,
                    "Professional enum did not retain its real vanilla cycle widget: " + value.getPath());
            var cycle = (net.minecraft.client.gui.components.CycleButton) widget;
            Object old = cycle.getValue(), originalRaw = value.getRaw();
            for (var candidate : ((Enum<?>) value.getDefault()).getDeclaringClass().getEnumConstants()) {
                if (!value.getSpec().test(candidate)) continue;
                cycle.setValue(candidate);
                String expected = NativeConfigOption.display(value, candidate).getString();
                require(cycle.getMessage().getString().equals(expected),
                        "Professional enum caption differs from the shared native translation in "
                                + mc.getLanguageManager().getSelected() + ": " + value.getPath()
                                + "=" + candidate + "; actual=" + cycle.getMessage().getString() + "; expected=" + expected);
                require(!cycle.getMessage().getString().equals(((Enum<?>) candidate).name()),
                        "Professional enum exposed its untranslated code name: " + value.getPath() + "=" + candidate);
                require(mc.font.width(cycle.getMessage()) <= cycle.getWidth() - 12,
                        "Professional translated enum caption is clipped: " + value.getPath() + "=" + expected);
            }
            cycle.setValue(old);
            require(value.getRaw().equals(originalRaw), "Caption candidate sweep committed a professional enum change");
            if (spec == CraftableClientConfig.SPEC && old instanceof DirectPreparationPolicy.Mode personal) {
                var candidates = NativeConfigOption.candidates(personal, value.getSpec());
                int originalIndex = candidates.indexOf(personal);
                require(candidates.size() == 4, "Professional personal settings omitted a partial-crafting mode");
                for (int step = 1; step <= candidates.size(); step++) {
                    clickLabeledValue(mc.screen, value.getSpec().getTranslationKey());
                    require(value.getRaw() == candidates.get((originalIndex + step) % candidates.size()),
                            "Professional personal clicks did not use the shared four-mode setting");
                }
                require(value.getRaw().equals(originalRaw), "Professional complete personal cycle failed to restore its original value");
            }
        }
    }

    static void verifyProfessionalModeGuards(Minecraft mc) throws Exception {
        var parent = mc.screen;
        var modeValue = directList("directPreparationMode");
        require(modeValue.getRaw() == DirectPreparationPolicy.Mode.CUSTOM,
                "Professional mode fixture must begin with its owned custom policy");
        var allowed = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
        require(ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                .noneMatch(value -> value.getPath().getLast().equals("directPreparationConfirmationRecipes")),
                "Retired confirmation list remains registered in the professional settings");
        require(ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                .filter(value -> value.getDefault() instanceof List<?>).count() == 1,
                "Professional world settings do not expose exactly one registered recipe-ID list");
        require(widgets(parent).stream().noneMatch(widget -> widget.getMessage().getString()
                .equals(Component.translatable("config.craftable.server.direct_preparation_confirmation_recipes").getString())),
                "Professional section still displays a second preparation ID editor");
        var candidates = NativeConfigOption.candidates(DirectPreparationPolicy.Mode.CUSTOM, modeValue.getSpec());
        require(candidates.equals(List.of(DirectPreparationPolicy.Mode.DISABLED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION,
                DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.Mode.ALL_SUPPORTED)),
                "Native/professional merged policy did not share the intended four-state presentation order");
        int originalIndex = candidates.indexOf(DirectPreparationPolicy.Mode.CUSTOM);
        for (int step = 1; step <= candidates.size(); step++) {
            var expected = candidates.get((originalIndex + step) % candidates.size());
            clickLabeledValue(parent, modeValue.getSpec().getTranslationKey()); parent.tick();
            require(modeValue.getRaw() == expected, "Professional mode did not use its shared four-state cycle: " + expected);
            for (var key : List.of("config.craftable.server.direct_preparation_allowed_recipes")) {
                var button = (Button) professionalControl(parent, key);
                require(button.active == (expected == DirectPreparationPolicy.Mode.CUSTOM),
                        "Professional recipe-list availability did not follow the current mode: " + expected + "/" + key);
                if (expected != DirectPreparationPolicy.Mode.CUSTOM) {
                    button.onPress();
                    require(mc.screen == parent, "Disabled professional recipe-list callback opened an editable child");
                }
            }
            require(directList("directPreparationAllowedRecipes").getRaw().equals(allowed),
                    "Professional shortcut mode erased a custom ID list");
        }
    }

    static void verifyProfessionalChildModeGuard(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var proposed = List.copyOf((List<?>) read(screen, UiField.PROPOSED));
        var original = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
        var mode = directList("directPreparationMode");
        try {
            for (var shortcut : List.of(DirectPreparationPolicy.Mode.DISABLED,
                    DirectPreparationPolicy.Mode.ALL_SUPPORTED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION)) {
                restoreValue(mode, shortcut); screen.tick();
                for (var name : List.of(UiField.ADD, UiField.DEFAULTS, UiField.DONE)) {
                    var control = (Button) read(screen, name);
                    require(!control.active, "Old professional ID editor retained an active write control under " + shortcut);
                    control.onPress();
                }
                for (var remove : (List<?>) read(screen, UiField.REMOVES)) ((Button) remove).onPress();
                require(mc.screen == screen && read(screen, UiField.PROPOSED).equals(proposed)
                        && directList("directPreparationAllowedRecipes").getRaw().equals(original),
                        "Old professional ID-editor callbacks changed local or registered lists under " + shortcut);
            }
        } finally { restoreValue(mode, DirectPreparationPolicy.Mode.CUSTOM); screen.tick(); }
        require(button(screen, CommonComponents.GUI_DONE.getString()).active,
                "Returning to custom mode lost the professional editor's valid pending IDs");
    }

    static void verifyProfessionalPolicyContextExpiry(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var originalConnection = read(screen, UiField.CONNECTION);
        var current = List.copyOf((List<?>) directList("directPreparationAllowedRecipes").getRaw());
        try {
            // 中文：只模拟旧页面身份，不公开夹具世界，也不宣称此例证明真实 LAN/服务端权限。
            // Model an obsolete screen identity without publishing the fixture
            // world or claiming this proves real LAN/server permissions.
            writeField(screen, UiField.CONNECTION, null);
            screen.tick();
            var undo = button(screen, ConfigurationScreen.UNDO.getString());
            var reset = button(screen, ConfigurationScreen.RESET.getString());
            require(!undo.active && !reset.active, "Expired professional context retained active Undo/Reset before a render");
            undo.onPress(); reset.onPress();
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Professional footer callback wrote a recipe list after context expiry");
            var manager = read(screen, UiField.UNDO_MANAGER);
            invokePublic(manager, "undo");
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Recipe undo mutation callback lacked an independent context guard");
            invokePublic(manager, "redo");
            require(directList("directPreparationAllowedRecipes").getRaw().equals(current),
                    "Recipe redo mutation callback lacked an independent context guard");
        } finally { writeField(screen, UiField.CONNECTION, originalConnection); }
        screen.tick();
        require(button(screen, ConfigurationScreen.UNDO.getString()).active,
                "Restoring the same professional context failed to derive its real available undo state");
    }
}
