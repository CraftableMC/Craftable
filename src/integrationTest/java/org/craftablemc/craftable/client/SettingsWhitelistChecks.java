package org.craftablemc.craftable.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

import static org.craftablemc.craftable.client.SettingsCapture.capture;
import static org.craftablemc.craftable.client.SettingsCapture.deferUntilRendered;
import static org.craftablemc.craftable.client.SettingsCapture.setLanguage;
import static org.craftablemc.craftable.client.SettingsFixture.restoreFixtureMouse;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.openReadOnlyWorld;
import static org.craftablemc.craftable.client.SettingsPersistenceChecks.personalDraft;
import static org.craftablemc.craftable.client.SettingsPersistenceChecks.toggleEnhancements;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyEditableFooter;
import static org.craftablemc.craftable.client.SettingsRulesChecks.rulesReady;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：用白名单草稿、目录状态及配方材料提示反例检查保存边界与显示聚合，尤其防止同图标误合并和提示越界。
 * English: Uses whitelist drafts, catalog states and recipe-material tooltip counterexamples to check save boundaries and display grouping, especially false icon-based merging and tooltip overflow. */
final class SettingsWhitelistChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.CANCEL_WHITELIST_DRAFT && state.age > 2 && directCatalogReady(mc)) {
            verifyDirectPreparationEditor(mc);
            searchDirectRecipe(mc, "minecraft:stick");
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    toggleDirectRecipe(mc, "minecraft:stick");
                    toggleDirectRecipe(mc, "minecraft:stick");
                    require(CraftableServerConfig.craftingRules().directPreparation().equals(state.policyBefore),
                            "Recipe row editing changed registered world rules before Done");
                    mc.screen.keyPressed(256, 0, 0);
                    require(mc.screen == state.policyWorld && ((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot().equals(state.policyDraftBefore),
                            "Recipe editor Escape leaked unsaved list changes to the world draft");
                    state.step = SettingsStep.REOPEN_WHITELIST;
                } catch (Exception error) { throw new AssertionError("Native two-state cancellation failed", error); }
            });
            return false;
        }
        if (state.step == SettingsStep.REOPEN_WHITELIST && state.age > 2) {
            click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.ACCEPT_WHITELIST_DRAFT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.ACCEPT_WHITELIST_DRAFT && state.age > 2 && directCatalogReady(mc)) {
            searchDirectRecipe(mc, "minecraft:stick");
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    toggleDirectRecipe(mc, "minecraft:stick");
                    verifyUnreadyRecipeCatalogPresentation(mc);
                    click(mc.screen, CommonComponents.GUI_DONE.getString());
                    require(mc.screen == state.policyWorld && CraftableServerConfig.craftingRules().directPreparation().equals(state.policyBefore),
                            "Recipe editor Done saved rather than updating only the parent world draft");
                    var parentDraft = (ConfigSettings.Draft) read(mc.screen, UiField.DRAFT);
                    require(((List<?>) parentDraft.snapshot().get(directList("directPreparationAllowedRecipes")))
                            .contains("craftable:unknown_settings_probe"), "Recipe editor Done lost a saved unknown recipe ID");
                    require(((List<?>) parentDraft.snapshot().get(directList("directPreparationAllowedRecipes")))
                            .contains("minecraft:stick"), "Native two-state edit did not add its exact whitelist entry");
                    state.step = SettingsStep.NATIVE_MODE_GUARDS;
                } catch (Exception error) { throw new AssertionError("Native two-state acceptance failed", error); }
            });
            return false;
        }
        if (state.step == SettingsStep.NATIVE_MODE_GUARDS && state.age > 2) {
            verifyNativeModeGuards(mc);
            click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY)); state.step = SettingsStep.CANCEL_WHITELIST_DEFAULTS; state.age = 0; return false;
        }
        if (state.step == SettingsStep.CANCEL_WHITELIST_DEFAULTS && state.age > 2 && directCatalogReady(mc)) {
            require(((List<?>) read(mc.screen, UiField.ALLOWED)).contains("craftable:unknown_settings_probe"),
                    "Mode shortcut failed to preserve recipe lists on reopening");
            verifyNativeChildModeGuard(mc);
            click(mc.screen, translated("defaults"));
            require(read(mc.screen, UiField.ALLOWED).equals(directList("directPreparationAllowedRecipes").getDefault()),
                    "Recipe Defaults did not restore the registered shipped whitelist");
            mc.screen.keyPressed(256, 0, 0);
            require(((List<?>) ((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot()
                    .get(directList("directPreparationAllowedRecipes"))).contains("craftable:unknown_settings_probe"),
                    "Recipe Defaults followed by Escape discarded the parent's prior accepted draft");
            require(((List<?>) ((ConfigSettings.Draft) read(mc.screen, UiField.DRAFT)).snapshot()
                    .get(directList("directPreparationAllowedRecipes"))).contains("minecraft:stick"),
                    "Recipe Defaults followed by Escape lost the accepted supported recipe choice");
            require(CraftableServerConfig.craftingRules().directPreparation().equals(state.policyBefore),
                    "Recipe defaults or mode shortcut bypassed World Done");
            state.directPreparationChecked = true; state.step = SettingsStep.EDIT_WORLD_DRAFT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.CAPTURE_WHITELIST_CHINESE && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(state, mc, false, "settings-native-preparation-recipes-zh-20261021-refactor");
            state.step = SettingsStep.RESIZE_WHITELIST; state.age = 0; return false;
        }
        if (state.step == SettingsStep.RESIZE_WHITELIST && state.captured) {
            mc.getWindow().setWindowed(854, 480); mc.options.guiScale().set(2); mc.resizeDisplay();
            state.step = SettingsStep.PREPARE_SMALL_WHITELIST; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PREPARE_SMALL_WHITELIST && state.age > 2 && directCatalogReady(mc)) {
            require(mc.screen.width >= 320 && mc.screen.height == 240,
                    "Small tooltip screenshot did not reach the supported 240px GUI height: " + mc.screen.width + "x" + mc.screen.height);
            searchDirectRecipe(mc, "minecraft:chest");
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    state.mouseTooltipTarget = directChoice(mc, "minecraft:chest");
                    var list = (OptionsList) read(mc.screen, UiField.LIST);
                    require(state.mouseTooltipTarget.getY() >= list.getY()
                            && state.mouseTooltipTarget.getY() < mc.screen.height / 2,
                            "Mouse tooltip fixture did not hover a top-half actual chest row");
                    state.fixtureMouseX = mc.mouseHandler.xpos(); state.fixtureMouseY = mc.mouseHandler.ypos();
                    mc.screen.clearFocus(); mc.setLastInputType(InputType.MOUSE);
                    // 中文：向真实游戏渲染器提供正常 MouseHandler 坐标，不移动系统鼠标或制造假提示。
                    // Feed the real game renderer its normal MouseHandler coordinates;
                    // no OS cursor movement or synthetic tooltip is involved.
                    writeField(mc.mouseHandler, UiField.XPOS, (state.mouseTooltipTarget.getX() + 5.0)
                            * mc.getWindow().getScreenWidth() / mc.screen.width);
                    writeField(mc.mouseHandler, UiField.YPOS, (state.mouseTooltipTarget.getY() + 5.0)
                            * mc.getWindow().getScreenHeight() / mc.screen.height);
                    state.step = SettingsStep.CAPTURE_SMALL_MOUSE_TOOLTIP;
                } catch (Exception error) { throw new AssertionError("Small GUI top mouse tooltip setup failed", error); }
            });
            return false;
        }
        if (state.step == SettingsStep.CAPTURE_SMALL_MOUSE_TOOLTIP && state.age > 2) {
            require(state.mouseTooltipTarget.isHovered(), "Small GUI screenshot did not use the actual hovered chest widget");
            capture(state, mc, "settings-native-whitelist-chest-top-mouse-small-zh-20261021-refactor");
            state.step = SettingsStep.PREPARE_SMALL_KEYBOARD_TOOLTIP; state.age = 0; return false;
        }
        if (state.step == SettingsStep.PREPARE_SMALL_KEYBOARD_TOOLTIP && state.captured) {
            restoreFixtureMouse(state, mc);
            searchDirectRecipe(mc, "minecraft:ender_chest");
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    mc.screen.clearFocus(); mc.setLastInputType(InputType.KEYBOARD_TAB);
                    directChoice(mc, "minecraft:ender_chest").setFocused(true);
                    state.step = SettingsStep.CAPTURE_SMALL_KEYBOARD_TOOLTIP;
                } catch (Exception error) { throw new AssertionError("Small GUI keyboard tooltip setup failed", error); }
            });
            return false;
        }
        if (state.step == SettingsStep.CAPTURE_SMALL_KEYBOARD_TOOLTIP && state.age > 2) {
            capture(state, mc, "settings-native-whitelist-ender-chest-keyboard-small-zh-20261021-refactor");
            state.step = SettingsStep.RESTORE_WHITELIST_VIEWPORT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.RESTORE_WHITELIST_VIEWPORT && state.captured) {
            mc.getWindow().setWindowed(1280, 720); mc.options.guiScale().set(1); mc.resizeDisplay();
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == state.policyWorld, "Chinese recipe editor lost its world draft parent");
            mc.screen.keyPressed(256, 0, 0);
            openReadOnlyWorld(state, mc); state.step = SettingsStep.OPEN_READONLY_WHITELIST_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.OPEN_READONLY_WHITELIST_CHINESE && rulesReady(mc, state.oldSteps, state.oldRadius)) {
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    state.policyWorld = mc.screen;
                    click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY));
                } catch (Exception error) { throw new AssertionError("Deferred read-only recipe navigation failed", error); }
            });
            state.step = SettingsStep.CAPTURE_READONLY_WHITELIST_CHINESE; state.age = 0; return false;
        }
        if (state.step == SettingsStep.CAPTURE_READONLY_WHITELIST_CHINESE && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(state, mc, true, "settings-native-preparation-read-only-zh-20261021-refactor");
            state.step = SettingsStep.LOAD_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.LOAD_ENGLISH && state.captured) {
            mc.screen.keyPressed(256, 0, 0); mc.screen.keyPressed(256, 0, 0);
            require(personalDraft(mc.screen).equals(state.before), "Language/resize lost the restored personal config");
            setLanguage(mc, "en_us"); state.step = SettingsStep.ENGLISH_PERSONAL_LAYOUT; state.age = 0; state.languageReadyTicks = 0; return false;
        }
        if (state.step == SettingsStep.CAPTURE_WHITELIST_ENGLISH && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(state, mc, false, "settings-native-preparation-recipes-en-20261021-refactor");
            state.step = SettingsStep.OPEN_READONLY_RULES_ENGLISH; state.age = 0; return false;
        }
        if (state.step == SettingsStep.OPEN_READONLY_RULES_ENGLISH && state.captured) {
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == state.policyWorld, "English recipe editor lost its world draft parent");
            mc.screen.keyPressed(256, 0, 0); openReadOnlyWorld(state, mc); state.step = SettingsStep.ENGLISH_RULES_LAYOUT; state.age = 0; return false;
        }
        if (state.step == SettingsStep.CAPTURE_READONLY_WHITELIST_ENGLISH && directCatalogReady(mc)) {
            prepareDirectRecipeCapture(state, mc, true, "settings-native-preparation-read-only-en-20261021-refactor");
            state.step = SettingsStep.REOPEN_PROFESSIONAL; state.age = 0; return false;
        }
        if (state.step == SettingsStep.REOPEN_PROFESSIONAL && state.captured) {
            mc.screen.keyPressed(256, 0, 0); mc.screen.keyPressed(256, 0, 0);
            deferUntilRendered(state, mc.screen, () -> {
                try {
                    toggleEnhancements(mc); state.saved = personalDraft(mc.screen);
                    click(mc.screen, (Button) read(mc.screen, UiField.ADVANCED));
                    require(mc.screen instanceof ConfigurationScreen, "Advanced fallback did not use the existing NeoForge config UI");
                } catch (Exception error) { throw new AssertionError("Deferred professional navigation failed", error); }
            });
            state.step = SettingsStep.OPEN_PROFESSIONAL_CLIENT; state.age = 0; return false;
        }
        return false;
    }

    static boolean directCatalogReady(Minecraft mc) throws Exception {
        return mc.screen instanceof DirectPreparationSettingsScreen
                && ((org.craftablemc.craftable.recipe.PlanningInput.Catalog) read(mc.screen, UiField.CATALOG)).state()
                    == org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.READY
                && !((Map<?, ?>) read(mc.screen, UiField.RECIPES)).isEmpty();
    }

    static void prepareDirectRecipeCapture(SettingsScenarioState state, Minecraft mc, boolean readOnly, String name) throws Exception {
        require(directCatalogReady(mc), "Recipe screenshot attempted before its bounded static catalog was ready");
        require((read(mc.screen, UiField.DRAFT) == null) == readOnly, "Recipe screenshot used the wrong editing/inspection state");
        verifyRecipeCatalogStatusWidth(mc);
        var search = (net.minecraft.client.gui.components.EditBox) read(mc.screen, UiField.SEARCH);
        search.setValue("minecraft:stick");
        verifyDirectoryStatusCopy(mc);
        var choices = (List<?>) read(mc.screen, UiField.CHOICES);
        var stick = directChoice(mc, "minecraft:stick");
        if (!readOnly) {
            // 中文：原版键盘提示必须展示真实配方 ID、名单选项、网格和成本总览，不能用合成文本冒充。
            // Native keyboard tooltip presentation shows the actual recipe ID,
            // membership choice, grid and cost overview, not synthetic text.
            mc.screen.clearFocus();
            mc.setLastInputType(InputType.KEYBOARD_TAB);
            stick.setFocused(true);
        } else require(choices.stream().allMatch(choice -> !((Button) choice).active),
                "Read-only screenshot retained an editable recipe choice");
        deferUntilRendered(state, mc.screen, () -> capture(state, mc, name));
    }

    static void verifyDirectPreparationEditor(Minecraft mc) throws Exception {
        require(mc.screen instanceof DirectPreparationSettingsScreen, "Native recipe-list entry did not use its bounded editor");
        var parent = ((DirectPreparationSettingsScreen) mc.screen).settingsParent();
        var draft = (ConfigSettings.Draft) read(mc.screen, UiField.DRAFT);
        require(draft == read(parent, UiField.DRAFT), "Recipe editor introduced a second world draft or config store");
        require(read(mc.screen, UiField.ALLOWED_VALUE) == directList("directPreparationAllowedRecipes"),
                "Recipe editor duplicated its registered whitelist setting identity");
        verifyEditableFooter(mc);
        require(widgets(mc.screen).stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).count() == 1,
                "Ordinary recipe page retained an exact-ID input in addition to its search field");
        verifyRecipeSummaryPresentation(mc);
        require(((List<?>) read(mc.screen, UiField.ALLOWED)).equals(List.of("craftable:unknown_settings_probe")),
                "Opening the ordinary recipe directory silently restored default whitelist entries");
        var search = (net.minecraft.client.gui.components.EditBox) read(mc.screen, UiField.SEARCH);
        search.setValue("minecraft:stick");
        verifyDirectoryStatusCopy(mc);
        require(((List<?>) read(mc.screen, UiField.CHOICES)).size() <= 256, "Recipe editor rendered an unbounded widget list");
        var stick = directChoice(mc, "minecraft:stick");
        require(stick.getMessage().getString()
                .equals(Component.translatable("screen.craftable.settings.direct.confirm").getString()),
                "An unlisted basic recipe was not displayed as needing confirmation: recipe=minecraft:stick, caption="
                        + stick.getMessage().getString());
        search.setValue("craftable:unknown_settings_probe");
        require(((List<?>) read(mc.screen, UiField.ALLOWED)).contains("craftable:unknown_settings_probe")
                && ((List<?>) read(mc.screen, UiField.CHOICES)).stream().allMatch(row -> !((Button) row).active),
                "Saved unknown ID was dropped or exposed as an editable supported recipe");
        search.setValue("");
    }

    static void searchDirectRecipe(Minecraft mc, String id) throws Exception {
        ((net.minecraft.client.gui.components.EditBox) read(mc.screen, UiField.SEARCH)).setValue(id);
    }

    static void verifyRecipeSummaryPresentation(Minecraft mc) throws Exception {
        var recipes = (Map<?, ?>) read(mc.screen, UiField.RECIPES);
        var chest = (org.craftablemc.craftable.recipe.CraftingRecipes.Entry) recipes.get(
                net.minecraft.resources.ResourceLocation.parse("minecraft:chest"));
        var ender = (org.craftablemc.craftable.recipe.CraftingRecipes.Entry) recipes.get(
                net.minecraft.resources.ResourceLocation.parse("minecraft:ender_chest"));
        require(chest != null && ender != null, "Material-summary fixture lost actual normalized vanilla recipes");
        var chestGroups = DirectPreparationSettingsScreen.inputGroups(chest.requirements());
        require(chestGroups.size() == 1 && chestGroups.getFirst().count() == 8
                && chestGroups.getFirst().options().size() > 4,
                "Eight independent any-plank chest slots were not merged before display truncation");
        var enderGroups = DirectPreparationSettingsScreen.inputGroups(ender.requirements());
        require(enderGroups.size() == 2 && enderGroups.stream().anyMatch(group -> group.count() == 8
                    && group.options().size() == 1 && group.options().getFirst().is(net.minecraft.world.item.Items.OBSIDIAN))
                && enderGroups.stream().anyMatch(group -> group.count() == 1 && group.options().size() == 1
                    && group.options().getFirst().is(net.minecraft.world.item.Items.ENDER_EYE)),
                "Ender chest inputs were not displayed as eight obsidian plus one eye");
        require(DirectPreparationSettingsScreen.inputSummary(chest).startsWith("8 × (")
                && DirectPreparationSettingsScreen.inputSummary(ender).contains("8 × (")
                && DirectPreparationSettingsScreen.inputSummary(ender).contains("1 × ("),
                "Normalized material groups did not reach the actual displayed summary");

        var a = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        var b = a.copy(); b.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("probe"));
        var common = List.of(net.minecraft.world.item.Items.ACACIA_PLANKS, net.minecraft.world.item.Items.BIRCH_PLANKS,
                net.minecraft.world.item.Items.CHERRY_PLANKS, net.minecraft.world.item.Items.DARK_OAK_PLANKS);
        var left = new ArrayList<net.minecraft.world.item.ItemStack>();
        var right = new ArrayList<net.minecraft.world.item.ItemStack>();
        common.forEach(item -> { left.add(new net.minecraft.world.item.ItemStack(item)); right.add(new net.minecraft.world.item.ItemStack(item)); });
        left.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
        right.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SPRUCE_PLANKS));
        var requirements = List.of(
                new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(0, net.minecraft.world.item.crafting.Ingredient.of(a)),
                new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(1, net.minecraft.world.item.crafting.Ingredient.of(a.copyWithCount(5))),
                new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(2, net.minecraft.world.item.crafting.Ingredient.of(b)),
                new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(3, net.minecraft.world.item.crafting.Ingredient.of(left.toArray(net.minecraft.world.item.ItemStack[]::new))),
                new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(4, net.minecraft.world.item.crafting.Ingredient.of(right.toArray(net.minecraft.world.item.ItemStack[]::new))));
        var distinct = DirectPreparationSettingsScreen.inputGroups(requirements);
        require(distinct.size() == 4 && distinct.getFirst().count() == 2,
                "Material grouping used Ingredient identity/count, display prefixes, or discarded full component differences");
        java.util.Collections.reverse(left);
        var reordered = List.of(requirements.get(3), new org.craftablemc.craftable.recipe.CraftingRecipes.Requirement(5,
                net.minecraft.world.item.crafting.Ingredient.of(left.toArray(net.minecraft.world.item.ItemStack[]::new))));
        require(DirectPreparationSettingsScreen.inputGroups(reordered).size() == 1
                && DirectPreparationSettingsScreen.inputGroups(reordered).getFirst().count() == 2,
                "Equivalent complete material option sets depended on their source ordering");

        int height = 240, width = 427;
        var longTooltip = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> Component.literal("tooltip-line-" + i).getVisualOrderText()).toList();
        var visible = DirectPreparationSettingsScreen.boundedTooltipLines(longTooltip, height);
        int limit = DirectPreparationSettingsScreen.tooltipLineLimit(height);
        require(visible.size() == limit && visible.getFirst() == longTooltip.getFirst()
                && visible.getLast() == longTooltip.getLast() && visible.size() < longTooltip.size(),
                "Viewport tooltip elision lost recipe identity/action tail or exceeded its height bound");
        int tooltipHeight = visible.size() * 10 + 2;
        for (var desired : List.of(new org.joml.Vector2i(-200, -1000), new org.joml.Vector2i(10000, 10000))) {
            var position = DirectPreparationSettingsScreen.boundedTooltipPosition(desired, width, height, 170, tooltipHeight);
            require(position.x() >= 4 && position.y() >= 4 && position.x() + 170 <= width - 4
                    && position.y() + tooltipHeight <= height - 6,
                    "Tooltip position escaped top/bottom/left/right at a supported small GUI size: " + position);
        }
        // 中文：检查真实页面的提示调度覆写，不能仅测纯辅助函数而漏掉接线错误。
        // Test the actual screen scheduling override, not only its pure helper.
        mc.screen.setTooltipForNextRenderPass(longTooltip, (w, h, x, y, tw, th) -> new org.joml.Vector2i(-200, -1000), true);
        var scheduled = read(mc.screen, UiField.DEFERRED_TOOLTIP_RENDERING);
        require(((List<?>) read(scheduled, UiField.TOOLTIP)).size() <= DirectPreparationSettingsScreen.tooltipLineLimit(mc.screen.height),
                "Actual native tooltip scheduling bypassed its height bound");
        var actualPositioner = (net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner) read(scheduled, UiField.POSITIONER);
        var actualPosition = actualPositioner.positionTooltip(width, height, 0, 0, 170, tooltipHeight);
        require(actualPosition.x() >= 4 && actualPosition.y() >= 4, "Actual screen tooltip scheduler retained a negative position");
        Craftable.LOGGER.warn("SETTINGS_MATERIAL_TOOLTIP PASS: actual normalized chest eight equivalent plank slots merge into one independent-choice group; ender chest eight obsidian plus one eye; complete option order and item components determine semantic equality before four-name truncation; visual tooltip cap retains identity and action tail and actual screen scheduler clamps all viewport edges at427x240; no recipe/planner mutation");
    }

    static Button directChoice(Minecraft mc, String id) throws Exception {
        // 中文：按输出搜索会合法返回替代配方（如竹制木棍），须按精确行身份选择，不能假定唯一结果或首行。
        // Searching an output item intentionally also returns its alternate
        // recipes (stick from bamboo, for example). Select by the screen's
        // exact row identity, not a single-result or first-row assumption.
        var expected = net.minecraft.resources.ResourceLocation.parse(id);
        var choices = (List<?>) read(mc.screen, UiField.CHOICES);
        var ids = (Map<?, ?>) read(mc.screen, UiField.CHOICE_IDS);
        var matches = ids.entrySet().stream().filter(entry -> expected.equals(entry.getValue())).toList();
        require(ids.size() == choices.size() && matches.size() == 1 && choices.contains(matches.getFirst().getKey()),
                "Missing or ambiguous exact native recipe row: expected=" + id + ", search="
                        + ((net.minecraft.client.gui.components.EditBox) read(mc.screen, UiField.SEARCH)).getValue()
                        + ", visibleCount=" + choices.size() + ", rowRecipeIds=" + ids.values());
        return (Button) matches.getFirst().getKey();
    }

    static void toggleDirectRecipe(Minecraft mc, String id) throws Exception {
        var choice = directChoice(mc, id);
        var allowed = (List<?>) read(mc.screen, UiField.ALLOWED);
        var otherAllowed = allowed.stream().filter(value -> !value.equals(id)).toList();
        var entry = (org.craftablemc.craftable.recipe.CraftingRecipes.Entry) ((Map<?, ?>) read(mc.screen, UiField.RECIPES))
                .get(net.minecraft.resources.ResourceLocation.parse(id));
        require(entry != null, "Native two-state fixture did not use a normalized supported recipe: " + id);
        var effective = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                allowed.stream().map(String.class::cast).toList());
        boolean old = effective.allowsWithoutConfirmation(entry.id());
        String countBefore = ((StringWidget) read(mc.screen, UiField.STATUS)).getMessage().getString();
        String before = Component.translatable("screen.craftable.settings.direct." + (old ? "allow" : "confirm")).getString();
        require(choice.getMessage().getString().equals(before), "Recipe button did not show its effective two-state judgement");
        click(mc.screen, choice);
        require(allowed.contains(id) == !old,
                "Recipe flip did not toggle exact whitelist membership: " + id);
        require(allowed.stream().filter(value -> !value.equals(id)).toList().equals(otherAllowed),
                "Exact recipe flip changed another matching recipe's membership: " + id);
        require(choice.getMessage().getString().equals(Component.translatable("screen.craftable.settings.direct."
                + (old ? "confirm" : "allow")).getString()), "Recipe flip retained an inherited third state");
        verifyDirectoryStatusCopy(mc);
        require(((StringWidget) read(mc.screen, UiField.STATUS)).getMessage().getString().equals(countBefore),
                "Changing whitelist membership leaked its size into the recipe-count status");
    }

    static void verifyDirectoryStatusCopy(Minecraft mc) throws Exception {
        var status = (StringWidget) read(mc.screen, UiField.STATUS);
        require(status.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                "Recipe-count status lost its translatable component identity");
        var count = (net.minecraft.network.chat.contents.TranslatableContents) status.getMessage().getContents();
        require(count.getKey().equals("screen.craftable.settings.direct.count") && count.getArgs().length == 1
                && count.getArgs()[0].equals(read(mc.screen, UiField.MATCHING_RECIPES)),
                "Recipe status must pass exactly one matching-recipe count, never an extra whitelist size: "
                        + count.getKey() + "; arguments=" + java.util.Arrays.toString(count.getArgs()));
        require(status.getMessage().getString().equals(Component.translatable(count.getKey(), read(mc.screen, UiField.MATCHING_RECIPES)).getString()),
                "Recipe-count status retained appended whitelist text");
        var help = (Component) read(status.getTooltip(), UiField.MESSAGE);
        require(help.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translation
                && translation.getKey().equals("screen.craftable.settings.direct.help") && translation.getArgs().length == 0
                && help.getString().lines().count() == 1,
                "Actual recipe status help retained removed commentary: " + help.getString());
    }

    static void verifyNativeModeGuards(Minecraft mc) throws Exception {
        var parent = mc.screen;
        var draft = (ConfigSettings.Draft) read(parent, UiField.DRAFT);
        var allowed = draft.snapshot().get(directList("directPreparationAllowedRecipes"));
        var mode = binding(parent, "directPreparationMode").option();
        var entry = (Button) read(parent, UiField.DIRECT_PREPARATION_ENTRY);
        require(java.util.Arrays.stream(DirectPreparationPolicy.Mode.values()).filter(
                directList("directPreparationMode").getSpec()::test).count() == 4,
                "Merged partial-crafting setting does not accept exactly its four registered modes");
        for (var shortcut : List.of(DirectPreparationPolicy.Mode.DISABLED,
                DirectPreparationPolicy.Mode.ALL_SUPPORTED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION)) {
            setOption(mode, shortcut); parent.tick();
            require(!entry.active, "Native recipe lists remained available under shortcut mode: " + shortcut);
            entry.onPress();
            require(mc.screen == parent, "Disabled native recipe-list callback opened an editable child: " + shortcut);
            require(draft.snapshot().get(directList("directPreparationAllowedRecipes")).equals(allowed),
                    "Native shortcut mode erased whitelist IDs");
        }
        setOption(mode, DirectPreparationPolicy.Mode.CUSTOM); parent.tick();
        require(entry.active, "Returning to custom mode did not re-enable the native recipe list");
    }

    static void verifyNativeChildModeGuard(Minecraft mc) throws Exception {
        var child = mc.screen;
        var draft = (ConfigSettings.Draft) read(child, UiField.DRAFT);
        var originalAllowed = List.copyOf((List<?>) read(child, UiField.ALLOWED));
        var modeValue = directList("directPreparationMode");
        try {
            for (var shortcut : List.of(DirectPreparationPolicy.Mode.DISABLED,
                    DirectPreparationPolicy.Mode.ALL_SUPPORTED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION)) {
                draft.set(modeValue, shortcut); child.tick();
                require(((List<?>) read(child, UiField.CHOICES)).stream().allMatch(row -> !((Button) row).active),
                        "Already-open native recipe rows remained editable under a new shortcut mode: " + shortcut);
                var defaults = button(child, translated("defaults"));
                var done = button(child, CommonComponents.GUI_DONE.getString());
                require(!defaults.active && !done.active, "Already-open native recipe footer allowed writes under a shortcut mode: " + shortcut);
                for (var row : (List<?>) read(child, UiField.CHOICES)) ((Button) row).onPress();
                defaults.onPress(); done.onPress();
                require(mc.screen == child && read(child, UiField.ALLOWED).equals(originalAllowed),
                        "Old native row/default/save callbacks changed a now-disabled recipe list: " + shortcut);
            }
        } finally { draft.set(modeValue, DirectPreparationPolicy.Mode.CUSTOM); child.tick(); }
    }

    static void verifyUnreadyRecipeCatalogPresentation(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var search = (net.minecraft.client.gui.components.EditBox) read(screen, UiField.SEARCH);
        String originalSearch = search.getValue(); search.setValue("");
        var catalog = (org.craftablemc.craftable.recipe.PlanningInput.Catalog) read(screen, UiField.CATALOG);
        var originalState = catalog.state();
        var originalAllowed = List.copyOf((List<?>) read(screen, UiField.ALLOWED));
        var updateState = method(screen, UiMethod.UPDATE_CATALOG_STATE);
        var clearRecipes = method(screen, UiMethod.CLEAR_UNAVAILABLE_RECIPES, boolean.class);
        var rebuild = method(screen, UiMethod.REBUILD_ROWS);
        var refresh = method(screen, UiMethod.REFRESH_RECIPES);
        updateState.setAccessible(true); clearRecipes.setAccessible(true); rebuild.setAccessible(true); refresh.setAccessible(true);
        try {
            // 中文：验证真实显示转换，不换世界配方、不分配巨型目录或冒充自然归一化失败；结束恢复就绪目录。
            // Exercise the real presentation transitions without replacing
            // recipes, allocating a huge catalog, or claiming this is a
            // natural normalization failure. Restore the ready catalog below.
            for (var state : List.of(org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.BUILDING,
                    org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.LIMITED,
                    org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.EMPTY)) {
                writeField(catalog, UiField.STATE, state);
                boolean changed = (boolean) updateState.invoke(screen);
                require(changed && (boolean) clearRecipes.invoke(screen, changed),
                        "Unready catalog state transition failed to request a presentation refresh: " + state);
                rebuild.invoke(screen);
                require(((Map<?, ?>) read(screen, UiField.RECIPES)).isEmpty() && ((List<?>) read(screen, UiField.OBSERVED_RECIPES)).isEmpty(),
                        "Unready recipe catalog retained a previous generation's recipe rows");
                require(read(screen, UiField.ALLOWED).equals(originalAllowed),
                        "Unready recipe catalog discarded configured whitelist IDs");
                String statusKey = state == org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.BUILDING
                        ? "catalog_loading" : "catalog_unavailable";
                String expected = Component.translatable("screen.craftable.settings.direct." + statusKey).getString();
                require(((StringWidget) read(screen, UiField.STATUS)).getMessage().getString().equals(expected),
                        "Unready catalog was incorrectly presented as a zero recipe count: " + state);
                require(mc.font.width(((StringWidget) read(screen, UiField.STATUS)).getMessage()) <= 310,
                        "Unready catalog status exceeds its actual native row width: " + expected);
                var rows = rowWidgets((OptionsList) read(screen, UiField.LIST));
                require(rows.stream().filter(StringWidget.class::isInstance).noneMatch(widget -> widget.getMessage().getString()
                        .equals(Component.translatable("screen.craftable.settings.direct.unsupported").getString())),
                        "Unready catalog asserted that a configured recipe is unsupported");
                var choices = (List<?>) read(screen, UiField.CHOICES);
                require(choices.isEmpty() && rows.size() == 1 && rows.getFirst() == read(screen, UiField.STATUS)
                        && ((Map<?, ?>) read(screen, UiField.CHOICE_IDS)).isEmpty()
                        && widgets(screen).stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).count() == 1,
                        "Unready ordinary catalog flashed configured raw IDs instead of a status-only row: " + state);
            }
        } finally {
            writeField(catalog, UiField.STATE, originalState);
            refresh.invoke(screen); rebuild.invoke(screen); search.setValue(originalSearch);
        }
        require(directCatalogReady(mc) && read(screen, UiField.ALLOWED).equals(originalAllowed),
                "Presentation-state regression failed to restore the real ready catalog or its overrides");
        Craftable.LOGGER.warn("SETTINGS_CATALOG_STATE PASS: injected presentation states BUILDING/LIMITED/EMPTY show status only without raw configured-ID flashes, clear stale recipe rows but preserve every whitelist ID, no unsupported/default qualification claim or zero-recipe count, actual 310px status width checked, original READY catalog restored with supported and disabled unknown entries; not a natural normalization failure");
    }

    static void verifyRecipeCatalogStatusWidth(Minecraft mc) {
        for (String key : List.of("catalog_loading", "catalog_unavailable")) {
            var message = Component.translatable("screen.craftable.settings.direct." + key);
            require(mc.font.width(message) <= 310, "Native recipe catalog status is clipped in "
                    + mc.getLanguageManager().getSelected() + ": " + message.getString());
        }
    }
}
