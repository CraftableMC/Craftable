package org.craftablemc.craftable.client;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.neoforged.fml.config.ModConfig;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsCapture.deferUntilRendered;
import static org.craftablemc.craftable.client.SettingsCapture.setLanguage;
import static org.craftablemc.craftable.client.SettingsFixture.configure;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.openWorldAfterRender;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.reopenMain;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyConfigPresentation;
import static org.craftablemc.craftable.client.SettingsRulesChecks.rulesContainRecipe;
import static org.craftablemc.craftable.client.SettingsRulesChecks.rulesReady;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;
import static org.craftablemc.craftable.client.SettingsWhitelistChecks.directCatalogReady;
import static org.craftablemc.craftable.client.SettingsWhitelistChecks.searchDirectRecipe;
import static org.craftablemc.craftable.client.SettingsWhitelistChecks.toggleDirectRecipe;

/** 中文：同时观察注册值、TOML 与重开页面验证草稿/取消/默认/完成，区分“仅按钮看似保存”与真实持久化。
 * English: Checks draft/Cancel/Defaults/Done against registered values, TOML and reopened screens, distinguishing apparent UI saves from real persistence. */
final class SettingsPersistenceChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.CANCEL_PERSONAL_DRAFT && state.age > 2) {
            toggleEnhancements(mc);
            require(SettingsFixture.preferences().equals(state.before), "Editing a personal draft saved before Done");
            mc.screen.keyPressed(256, 0, 0);
            require(SettingsFixture.preferences().equals(state.before), "Escape saved an unconfirmed personal draft");
            reopenMain(state, mc); state.next(); return false;
        }
        if (state.step == SettingsStep.SAVE_PERSONAL_DRAFT && state.age > 2) {
            toggleEnhancements(mc);
            setOption(binding(mc.screen, "partialPolicy").option(), DirectPreparationPolicy.Mode.CUSTOM);
            state.saved = personalDraft(mc.screen);
            click(mc.screen, CommonComponents.GUI_DONE.getString()); state.next(); return false;
        }
        if (state.step == SettingsStep.VERIFY_PERSONAL_FILE && state.age > 2) {
            require(SettingsFixture.preferences().equals(state.saved), "Done did not apply the registered client spec");
            verifySavedFile(state.saved);
            reopenMain(state, mc); state.next(); return false;
        }
        if (state.step == SettingsStep.CANCEL_PERSONAL_DEFAULTS && state.age > 2) {
            require(personalDraft(mc.screen).equals(state.saved), "Reopened native settings did not reload saved preferences");
            click(mc.screen, translated("defaults"));
            require(personalDraft(mc.screen).equals(SettingsFixture.defaultPreferences()), "Defaults did not use registered spec defaults");
            require(SettingsFixture.preferences().equals(state.saved), "Defaults saved without Done");
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(SettingsFixture.preferences().equals(state.saved), "Cancel saved defaults");
            reopenMain(state, mc); state.next(); return false;
        }
        if (state.step == SettingsStep.SAVE_PERSONAL_DEFAULTS && state.age > 2) {
            click(mc.screen, translated("defaults")); click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(SettingsFixture.preferences().equals(SettingsFixture.defaultPreferences()), "Done did not persist defaults");
            verifySavedFile(SettingsFixture.defaultPreferences());
            SettingsFixture.savePreferences(state.before);
            reopenMain(state, mc); state.next(); return false;
        }
        if (state.step == SettingsStep.OPEN_WORLD_EDITOR && state.age > 2) {
            state.home = mc.screen;
            click(mc.screen, (Button) read(mc.screen, UiField.WORLD_ENTRY));
            require(mc.screen instanceof CraftableSettingsScreen && read(mc.screen, UiField.DRAFT) != null,
                    "World entry did not open the ordinary native editor");
            state.next(); return false;
        }
        if (state.step == SettingsStep.EDIT_WORLD_DRAFT && state.age > 2) {
            verifyConfigPresentation(mc);
            if (!state.directPreparationChecked) {
                state.policyWorld = mc.screen; state.policyBefore = CraftableServerConfig.craftingRules().directPreparation();
                var draft = (ConfigSettings.Draft) read(mc.screen, UiField.DRAFT);
                draft.set(directList("directPreparationAllowedRecipes"), List.of("craftable:unknown_settings_probe"));
                state.policyDraftBefore = ((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot();
                click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY));
                state.step = SettingsStep.CANCEL_WHITELIST_DRAFT; state.age = 0; return false;
            }
            setSliderEndpoint(mc, "maxPlanSteps", true);
            require(serverSteps().get() == 1, "Native world draft changed the live config before Done");
            click(mc.screen, CommonComponents.GUI_CANCEL.getString());
            require(mc.screen == state.home && serverSteps().get() == 1, "World Cancel committed an unconfirmed draft");
            openWorldAfterRender(state, mc);
            state.next(); return false;
        }
        if (state.step == SettingsStep.CANCEL_WORLD_DEFAULTS && state.age > 2) {
            require(binding(mc.screen, "maxPlanSteps").option().get().equals(1), "World Cancel leaked a draft into reopening");
            click(mc.screen, translated("defaults"));
            require(binding(mc.screen, "maxPlanSteps").option().get().equals(serverSteps().getDefault()),
                    "World Defaults did not use the registered spec default");
            require(serverSteps().get() == 1, "World Defaults saved before Done");
            mc.screen.keyPressed(256, 0, 0);
            openWorldAfterRender(state, mc);
            state.next(); return false;
        }
        if (state.step == SettingsStep.SAVE_WORLD_DRAFT && state.age > 2) {
            setSliderEndpoint(mc, "maxPlanSteps", true);
            require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).isEmpty(),
                    "Cancelled native recipe tests leaked into registered rules before persistence coverage");
            click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.SAVE_SUPPORTED_WHITELIST; state.age = 0; return false;
        }
        if (state.step == SettingsStep.SAVE_SUPPORTED_WHITELIST && state.age > 2 && directCatalogReady(mc)) {
            searchDirectRecipe(mc, "minecraft:stick");
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    toggleDirectRecipe(mc, "minecraft:stick");
                    click(mc.screen, CommonComponents.GUI_DONE.getString());
                    require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).isEmpty(),
                            "Native recipe child Done wrote registered rules before parent Done");
                    state.step = SettingsStep.COMMIT_WORLD_WHITELIST;
                } catch (Exception error) { throw new AssertionError("Native persisted supported recipe edit failed", error); }
            });
            return false;
        }
        if (state.step == SettingsStep.COMMIT_WORLD_WHITELIST && state.age > 2) {
            click(mc.screen, CommonComponents.GUI_DONE.getString());
            require(mc.screen == state.home, "World Done did not return to the ordinary main page");
            state.step = SettingsStep.VERIFY_WORLD_FILE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.VERIFY_WORLD_FILE && rulesReady(mc, CraftableServerConfig.MAX_MAX_PLAN_STEPS, 3)
                && rulesContainRecipe(mc, "minecraft:stick")) {
            require(serverSteps().get() == CraftableServerConfig.MAX_MAX_PLAN_STEPS, "World Done did not update the registered server spec");
            verifyServerSavedFile(CraftableServerConfig.MAX_MAX_PLAN_STEPS);
            require(((List<?>) directList("directPreparationAllowedRecipes").getRaw()).contains("minecraft:stick"),
                    "Native parent Done did not apply the registered recipe list");
            require(serverValue("environment", "diagnosticCacheTicks").get() == state.oldTtl, "Ordinary world save changed professional-only TTL");
            configure(state, mc, state.oldSteps, state.oldRadius); state.next(); return false;
        }
        if (state.step == SettingsStep.LOAD_CHINESE && state.configured && state.age > 4) {
            var rules = read(state.home, UiField.RULES);
            var request = method(rules, UiMethod.REQUEST, boolean.class);
            request.setAccessible(true); request.invoke(rules, true);
            setLanguage(mc, "zh_cn"); state.next(); return false;
        }
        return false;
    }

    static void toggleEnhancements(Minecraft mc) throws Exception {
        var option = binding(mc.screen, "recipeBookEnhancements").option();
        var list = (OptionsList) read(mc.screen, UiField.LIST); var widget = list.findOption(option);
        require(widget != null && widget.active, "Native personal toggle is unavailable");
        var old = option.get();
        mc.setLastInputType(InputType.MOUSE);
        require(mc.screen.mouseClicked(widget.getX() + 6, widget.getY() + 5, 0), "Personal toggle did not accept an actual click");
        mc.screen.mouseReleased(widget.getX() + 6, widget.getY() + 5, 0);
        require(!option.get().equals(old), "Native personal option did not change its draft value");
    }

    static SettingsFixture.Preferences personalDraft(Screen screen) throws Exception {
        return new SettingsFixture.Preferences((boolean) binding(screen, "recipeBookEnhancements").option().get(),
                (boolean) binding(screen, "detailedFailureFeedback").option().get(), (boolean) binding(screen, "unlockedOnly").option().get(),
                (DirectPreparationPolicy.Mode) binding(screen, "partialPolicy").option().get(),
                (boolean) binding(screen, "allowSurplusDrops").option().get(), (int) binding(screen, "doublePressMillis").option().get());
    }

    static void setSliderEndpoint(Minecraft mc, String name, boolean maximum) throws Exception {
        var binding = binding(mc.screen, name);
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        var widget = list.findOption(binding.option());
        require(widget != null && widget.active, "Native slider is unavailable: " + name);
        require(widget.getY() >= list.getY() && widget.getY() + widget.getHeight() <= list.getBottom(),
                "Native slider is not fully visible after rendering: " + name);
        int x = widget.getX() + (maximum ? widget.getWidth() - 4 : 4);
        mc.setLastInputType(InputType.MOUSE);
        require(mc.screen.mouseClicked(x, widget.getY() + 5, 0), "Native slider ignored an actual click: " + name);
        mc.screen.mouseReleased(x, widget.getY() + 5, 0);
        list.applyUnsavedChanges();
        var range = binding.configValue().getSpec().<Integer>getRange();
        require(binding.option().get().equals(maximum ? range.getMax() : range.getMin()),
                "Native slider did not retain its registered endpoint: " + name + "=" + binding.option().get());
    }

    static void verifySavedFile(SettingsFixture.Preferences expected) {
        var config = registered(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
        try (var file = CommentedFileConfig.of(config.getFullPath())) {
            file.load();
            require((boolean) file.get("presentation.recipeBookEnhancements") == expected.recipeBookEnhancements()
                    && (boolean) file.get("presentation.detailedFailureFeedback") == expected.detailedFailureFeedback()
                    && (boolean) file.get("presentation.unlockedOnly") == expected.unlockedOnly()
                    && file.get("crafting.partialPolicy").equals(expected.partialMode().name())
                    && (boolean) file.get("crafting.allowSurplusDrops") == expected.allowSurplusDrops()
                    && ((Number) file.get("crafting.doublePressMillis")).intValue() == expected.doublePressMillis(),
                    "Native settings did not persist to the registered TOML");
        }
    }

    static void verifyPersonalModePersistence(SettingsScenarioState state) {
        require(SettingsFixture.defaultPreferences().partialMode() == DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                "Personal default must follow the world's stricter rule rather than silently impose another whitelist");
        try {
            for (var mode : DirectPreparationPolicy.Mode.values()) {
                var proposed = new SettingsFixture.Preferences(state.before.recipeBookEnhancements(), state.before.detailedFailureFeedback(),
                        state.before.unlockedOnly(), mode, state.before.allowSurplusDrops(), state.before.doublePressMillis());
                SettingsFixture.savePreferences(proposed);
                require(CraftableClientConfig.partialMode() == mode && SettingsFixture.preferences().equals(proposed),
                        "Registered personal mode did not retain its four-state preference: " + mode);
                verifySavedFile(proposed);
            }
        } finally { SettingsFixture.savePreferences(state.before); }
    }

    static void verifyServerSavedFile(int steps) {
        var config = registered(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
        try (var file = CommentedFileConfig.of(config.getFullPath())) {
            file.load();
            require(((Number) file.get("crafting.maxPlanSteps")).intValue() == steps,
                    "Native world Done did not persist to the current world's registered TOML");
            require(file.get("crafting.directPreparationAllowedRecipes") instanceof List<?> ids
                    && ids.contains("minecraft:stick"),
                    "Native world Done did not persist its whitelist to the registered TOML");
            require(file.get("crafting.directPreparationConfirmationRecipes") == null,
                    "Saving the single whitelist restored a retired confirmation-list setting");
        }
    }
}
