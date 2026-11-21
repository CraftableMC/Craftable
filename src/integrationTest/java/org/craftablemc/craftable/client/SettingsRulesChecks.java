package org.craftablemc.craftable.client;

import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.craftablemc.craftable.config.CraftableServerConfig;

import static org.craftablemc.craftable.client.SettingsFixture.configure;
import static org.craftablemc.craftable.client.SettingsNavigationChecks.openReadOnlyWorld;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.tooltipText;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyNumericTooltip;
import static org.craftablemc.craftable.client.SettingsPresentationChecks.verifyWorldWhitelistLastRow;
import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：验证权威回复来源、旧序号及待定/只读规则显示，防止客户端本地 SERVER spec 被当作远端真实规则。
 * English: Checks authoritative reply sourcing, stale sequences and pending/read-only presentation so local SERVER specs cannot masquerade as remote rules. */
final class SettingsRulesChecks {
    static boolean advance(SettingsScenarioState state, Minecraft mc) throws Exception {
        if (state.step == SettingsStep.INITIAL_RULES && rulesReady(mc, 192, 3)) {
            state.firstRevision = (long) read(read(mc.screen, UiField.RULES), UiField.REVISION);
            state.home = mc.screen;
            require(((Button) read(state.home, UiField.WORLD_ENTRY)).getMessage().getString().equals(translated("edit_world")),
                    "Unpublished integrated world did not offer Edit World Rules");
            openReadOnlyWorld(state, mc); state.next(); return false;
        }
        if (state.step == SettingsStep.INSPECT_RULES && state.age > 2 && rulesReady(mc, 192, 3)) {
            verifyWorldPage(mc, 192);
            state.policyWorld = mc.screen;
            click(mc.screen, (Button) read(mc.screen, UiField.DIRECT_PREPARATION_ENTRY));
            state.step = SettingsStep.INSPECT_WHITELIST; state.age = 0; return false;
        }
        if (state.step == SettingsStep.INSPECT_WHITELIST && state.age > 2 && !((Map<?, ?>) read(mc.screen, UiField.RECIPES)).isEmpty()) {
            require(mc.screen instanceof DirectPreparationSettingsScreen && read(mc.screen, UiField.DRAFT) == null,
                    "Read-only recipe rules acquired a local server draft");
            var effective = ((CraftableServerConfig.CraftingRules) read(read(state.policyWorld, UiField.RULES), UiField.CRAFTING)).directPreparation();
            require(read(mc.screen, UiField.OBSERVED_POLICY).equals(effective), "Read-only recipe editor substituted the local SERVER spec");
            require(((List<?>) read(mc.screen, UiField.CHOICES)).stream().allMatch(button -> !((Button) button).active)
                    && widgets(mc.screen).stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).count() == 1,
                    "Read-only recipe rules exposed writable recipe overrides");
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == state.policyWorld, "Read-only recipe editor lost its world parent");
            configure(state, mc, 1, 3); state.step = SettingsStep.STALE_REPLY_AND_RELOAD; state.age = 0; return false;
        }
        if (state.step == SettingsStep.STALE_REPLY_AND_RELOAD && state.configured && state.age > 4) {
            // 中文：集成进程的可变 spec 两侧一起改变，但已收到显示值仍须为 192，才能证明未偷读客户端当前 spec。
            // In one integrated process the mutable spec changes on both
            // sides. The displayed response must nevertheless remain 192,
            // proving it is not silently reading the client's current spec.
            require(serverSteps().get() == 1 && rulesReady(mc, 192, 3), "World page substituted local config for the last response");
            CraftableSettingsScreen.receiveRules(state.firstRevision - 1, CraftableServerConfig.scanSettings(), CraftableServerConfig.craftingRules());
            require(rulesReady(mc, 192, 3), "Stale settings response replaced the displayed rules");
            click(mc.screen, translated("retry"));
            require((boolean) read(read(mc.screen, UiField.RULES), UiField.PENDING), "Native retry did not request a fresh settings response");
            require(!((Button) read(mc.screen, UiField.RETRY)).active,
                    "Footer retry remained active while its authoritative reply was pending");
            require(tooltipText((Button) read(mc.screen, UiField.RETRY), mc)
                    .contains(tooltipText(Tooltip.create(Component.translatable("screen.craftable.settings.rules_pending")), mc)),
                    "Pending authoritative status is missing from the disabled footer retry tooltip");
            state.next(); return false;
        }
        if (state.step == SettingsStep.VERIFY_RELOADED_RULES && rulesReady(mc, 1, 3)) {
            verifyWorldPage(mc, 1);
            mc.screen.keyPressed(256, 0, 0);
            require(mc.screen == state.home, "Read-only rules did not return to their main page");
            state.next(); return false;
        }
        return false;
    }

    static boolean rulesReady(Minecraft mc, int steps, int radius) throws Exception {
        if (!(mc.screen instanceof CraftableSettingsScreen)) return false;
        var state = read(mc.screen, UiField.RULES);
        if ((boolean) read(state, UiField.PENDING)) return false;
        var crafting = (CraftableServerConfig.CraftingRules) read(state, UiField.CRAFTING);
        var scan = (org.craftablemc.craftable.config.EnvironmentScanSettings) read(state, UiField.SCAN);
        return crafting != null && scan != null && crafting.maxPlanSteps() == steps && scan.horizontalRadius() == radius;
    }

    static boolean rulesContainRecipe(Minecraft mc, String id) throws Exception {
        if (!(mc.screen instanceof CraftableSettingsScreen)) return false;
        var rules = (CraftableServerConfig.CraftingRules) read(read(mc.screen, UiField.RULES), UiField.CRAFTING);
        return rules != null && rules.directPreparation().allowedRecipes().contains(id);
    }

    static void verifyWorldPage(Minecraft mc, int steps) throws Exception {
        require(read(mc.screen, UiField.PAGE).toString().equals("WORLD"), "World rules opened the wrong settings page");
        require(read(mc.screen, UiField.DRAFT) == null, "Read-only world view accidentally acquired an editable local draft");
        var expected = NativeConfigOption.definitions(CraftableServerConfig.SPEC).stream().filter(NativeConfigOption::scalar).toList();
        var rows = (List<?>) read(mc.screen, UiField.RULE_ROWS);
        require(rows.size() == expected.size(), "Effective world values do not cover the registered scalar definitions");
        var list = (OptionsList) read(mc.screen, UiField.LIST);
        require(((ContainerEventHandler) list).children().size() == (expected.size() + 2) / 2
                    && rowWidgets(list).size() == expected.size() + 1,
                "Read-only rules retained a status/retry/retired partialExecution row among the registered world options and whitelist entry");
        var retry = (Button) read(mc.screen, UiField.RETRY);
        var done = button(mc.screen, CommonComponents.GUI_DONE.getString());
        require(retry != null && retry.active && retry.getTooltip() != null
                && retry.getWidth() == 150 && done.getWidth() == 150 && retry.getY() == done.getY()
                && retry.getX() + retry.getWidth() + 5 == done.getX() && retry.getY() >= list.getBottom()
                && !rowWidgets(list).contains(retry) && !rowWidgets(list).contains(done),
                "Read-only Reload Rules and Done do not share the native footer outside the scrollable content");
        require(mc.screen.children().indexOf(retry) >= 0
                && mc.screen.children().indexOf(retry) < mc.screen.children().indexOf(done),
                "Read-only footer listener registration does not follow Reload Rules / Done");
        require(tooltipText(retry, mc).contains(tooltipText(Tooltip.create(
                Component.translatable("screen.craftable.settings.rules_from_world")), mc)),
                "Read-only authoritative status disappeared instead of moving to the footer retry tooltip");
        String narration = mc.screen.getNarrationMessage().getString();
        require(narration.contains(Integer.toString(steps)) && !narration.contains("diagnosticCacheTicks")
                && !narration.contains(Component.translatable("config.craftable.server.diagnostic_cache_ticks").getString()),
                "Narration uses local/raw/diagnostic values instead of effective rules");
        for (var row : rows) {
            var widget = (AbstractWidget) row;
            require(widget.getTooltip() != null && widget.getWidth() == 150 && !widget.active,
                    "Effective rule lacks half-width read-only presentation or readable tooltip");
            require(mc.font.width(widget.getMessage()) <= 138, "Read-only half-width rule caption is clipped: " + widget.getMessage().getString());
        }
        var definitions = NativeConfigOption.definitions(CraftableServerConfig.SPEC).stream().filter(NativeConfigOption::scalar).toList();
        for (int index = 0; index < definitions.size(); index++)
            if (SettingTooltip.hasNumericRange(definitions.get(index)))
                verifyNumericTooltip((AbstractWidget) rows.get(index), definitions.get(index), mc, "native read-only");
        verifyWorldWhitelistLastRow(mc);
        String expectedTooltip = Component.translatable("config.craftable.server.max_plan_steps.tooltip").getString();
        var maxStepsRow = rows.stream().map(AbstractWidget.class::cast)
                .filter(row -> tooltipText(row, mc).startsWith(expectedTooltip.substring(0, Math.min(12, expectedTooltip.length()))))
                .findFirst().orElseThrow(() -> new AssertionError("Effective rules lost their max-step tooltip"));
        require(tooltipText(maxStepsRow, mc).startsWith(expectedTooltip.substring(0, Math.min(12, expectedTooltip.length()))),
                "World tooltip retained the previous language");
        var heading = (AbstractWidget) read(mc.screen, UiField.HEADING);
        require(mc.font.width(mc.screen.getTitle()) <= heading.getWidth(), "Language reload clipped the world title to its old width");
    }
}
