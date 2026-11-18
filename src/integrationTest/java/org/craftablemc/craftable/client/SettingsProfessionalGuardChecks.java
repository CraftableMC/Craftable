package org.craftablemc.craftable.client;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;

import static org.craftablemc.craftable.client.SettingsUiAccess.*;

/** 中文：从真实专业控件的注册值写点和顶层保存倒推权限，防止名单专用检查掩盖布尔/整数入口。
 * 仅注入旧页面身份，不把它冒充真实 LAN 发布或多人权限测试。
 * English: Checks registered-value writes and top-level saves from real professional controls so list-only coverage cannot hide boolean/integer entries.
 * Injects an obsolete screen identity only; this is not evidence of real LAN publication or multiplayer permission coverage. */
final class SettingsProfessionalGuardChecks {
    private SettingsProfessionalGuardChecks() {}

    static void verifyNavigation(Minecraft mc) throws Exception {
        var parent = mc.screen;
        var connection = read(parent, UiField.CONNECTION);
        try {
            // 中文：未访问的子页会新建 Section；过期父页不能借子页重新捕获当前身份而恢复写权限。
            // An unvisited subsection creates a Section; an expired parent must not regain authority by recapturing current identity in that child.
            writeField(parent, UiField.CONNECTION, null);
            clickSection(parent, "craftable.configuration.environment");
            require(mc.screen == parent, "Expired professional parent navigated to a stale subsection");
        } finally {
            writeField(parent, UiField.CONNECTION, connection);
            mc.setScreen(parent);
        }
    }

    static void verifyScalars(Minecraft mc, String section) throws Exception {
        var screen = mc.screen;
        var connection = read(screen, UiField.CONNECTION);
        var values = ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                .filter(value -> value.getPath().getFirst().equals(section))
                .filter(value -> value.getDefault() instanceof Boolean || value.getDefault() instanceof Integer).toList();
        var before = new LinkedHashMap<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>, Object>();
        values.forEach(value -> before.put(value, value.getRaw()));
        try {
            for (var value : values) {
                var control = professionalControl(screen, value.getSpec().getTranslationKey());
                require(control.active, "Owned professional scalar is unavailable: " + value.getPath());
                changeControl(control, value.getRaw(), value.getSpec().getRange());
                Object edited = value.getRaw();
                require(!edited.equals(before.get(value)), "Legal professional scalar edit was rejected: " + value.getPath());

                // 中文：先重放合法编辑的闭包；拒绝操作可能也进入 NeoForge 历史，先测它会遮住真正的旧 Undo。
                // Replay the legal edit's closure first: a refused operation may also enter NeoForge history and hide that earlier Undo.
                writeField(screen, UiField.CONNECTION, null);
                var manager = read(screen, UiField.UNDO_MANAGER);
                invokePublic(manager, "undo");
                require(value.getRaw().equals(edited), "Expired scalar Undo callback changed registered value: " + value.getPath());
                // 中文：令 Redo 本来能改变值，避免因“写入值恰好等于现值”而掩盖缺失的门禁。
                // Give Redo a value it would change, so an equal-value write cannot conceal a missing guard.
                restoreValue(value, before.get(value));
                invokePublic(manager, "redo");
                require(value.getRaw().equals(before.get(value)), "Expired scalar Redo callback changed registered value: " + value.getPath());
                restoreValue(value, edited);
                // 中文：直接重放保留控件的回调，刻意绕过 active；真正写入门禁不能依赖上一帧的禁用样式。
                // Replay a retained widget callback past active intentionally: the write barrier cannot depend on last frame's disabled styling.
                changeControl(control, edited, value.getSpec().getRange());
                require(value.getRaw().equals(edited), "Expired professional scalar callback changed registered value: " + value.getPath());
                screen.tick();
                require(!control.active, "Expired professional scalar remained visibly editable: " + value.getPath());

                writeField(screen, UiField.CONNECTION, connection);
                restoreValue(value, before.get(value));
                mc.setScreen(screen);
                // 中文：已初始化的缓存页返回时只重新布局；先走真实 tick 恢复权限，再检查下一控件。
                // Returning to an initialized cached page only relayouts it; run its real tick to restore permissions before inspecting the next control.
                screen.tick();
            }
            if (!values.isEmpty()) verifyExpiredSave(mc, screen, values.getFirst());
        } finally {
            writeField(screen, UiField.CONNECTION, connection);
            before.forEach(SettingsUiAccess::restoreValue);
            mc.setScreen(screen);
            screen.tick();
        }
    }

    /** 中文：有效时捕获的批量 Reset 也会留下撤销闭包，必须在执行闭包时重新核对上下文。
     * English: A valid bulk Reset records undo closures whose execution must recheck the context, not only the originating button. */
    static void verifyResetUndo(Minecraft mc) throws Exception {
        var screen = mc.screen;
        var connection = read(screen, UiField.CONNECTION);
        var before = new LinkedHashMap<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>, Object>();
        ConfigSettings.values(CraftableServerConfig.SPEC).forEach(value -> before.put(value, value.getRaw()));
        var value = serverValue("crafting", "maxPlanSteps");
        int original = value.getRaw();
        try {
            var range = value.getSpec().<Integer>getRange();
            restoreValue(value, original == range.getMin() ? range.getMax() : range.getMin());
            mc.setScreen(screen);
            screen.tick();
            click(screen, ConfigurationScreen.RESET.getString());
            int reset = value.getRaw();
            require(reset == value.getDefault(), "Legal professional Reset did not restore scalar default");
            writeField(screen, UiField.CONNECTION, null);
            var manager = read(screen, UiField.UNDO_MANAGER);
            invokePublic(manager, "undo");
            require(value.getRaw() == reset, "Expired professional bulk-Reset Undo changed registered scalar");
            int changed = reset == range.getMin() ? range.getMax() : range.getMin();
            restoreValue(value, changed);
            invokePublic(manager, "redo");
            require(value.getRaw() == changed, "Expired professional bulk-Reset Redo changed registered scalar");
        } finally {
            writeField(screen, UiField.CONNECTION, connection);
            before.forEach(SettingsUiAccess::restoreValue);
            mc.setScreen(screen);
            screen.tick();
        }
    }

    private static void verifyExpiredSave(Minecraft mc, Screen screen,
            net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> value) throws Exception {
        var top = (Screen) read(screen, UiField.LAST_SCREEN);
        var topConnection = read(top, UiField.CONNECTION);
        var connection = read(screen, UiField.CONNECTION);
        var topChanged = read(top, UiField.CHANGED);
        var childChanged = read(screen, UiField.CHANGED);
        var file = registered(ModConfig.Type.SERVER, CraftableServerConfig.SPEC).getFullPath();
        byte[] diskBefore = Files.readAllBytes(file);
        Object original = value.getRaw();
        try {
            changeControl(professionalControl(screen, value.getSpec().getTranslationKey()), original, value.getSpec().getRange());
            require(!value.getRaw().equals(original), "Save-guard fixture did not make a legal registered edit");
            // 中文：先单独测失效子页不能传播 changed；随后让合法子页真实传播，独立验证顶层保存门禁。
            // First isolate expired-child dirtiness propagation; then let a valid child really propagate it before testing the top-level save guard independently.
            writeField(top, UiField.CHANGED, false);
            writeField(screen, UiField.CONNECTION, null);
            screen.onClose();
            require(mc.screen == top, "Expired professional subsection did not return to its parent");
            require(!(boolean) read(top, UiField.CHANGED), "Expired professional subsection propagated dirtiness");
            writeField(screen, UiField.CONNECTION, connection);
            mc.setScreen(screen);
            screen.onClose();
            require((boolean) read(top, UiField.CHANGED), "Valid professional subsection did not propagate a legal edit");
            writeField(top, UiField.CONNECTION, null);
            top.onClose();
            require(Arrays.equals(diskBefore, Files.readAllBytes(file)), "Expired professional close saved the world config");
        } finally {
            writeField(screen, UiField.CONNECTION, connection);
            writeField(top, UiField.CONNECTION, topConnection);
            writeField(screen, UiField.CHANGED, childChanged);
            writeField(top, UiField.CHANGED, topChanged);
            restoreValue(value, original);
            // 中文：红例可能已写文件；恢复拥有的原值后再保存，不能让失败反例污染后续测试。
            // A red run may already have written the file; restore the owned value before persisting cleanup so later tests are not contaminated.
            if (!Arrays.equals(diskBefore, Files.readAllBytes(file))) CraftableServerConfig.SPEC.save();
            mc.setScreen(screen);
        }
    }

    @SuppressWarnings("unchecked")
    private static void changeControl(AbstractWidget control, Object current,
            net.neoforged.neoforge.common.ModConfigSpec.Range<?> range) {
        if (control instanceof CycleButton<?> cycle) {
            cycle.onPress();
        } else if (control instanceof OptionInstance.OptionInstanceSliderButton<?> slider) {
            var bounds = (net.neoforged.neoforge.common.ModConfigSpec.Range<Integer>) range;
            boolean maximum = current.equals(bounds.getMin());
            slider.onClick(slider.getX() + (maximum ? slider.getWidth() - 4 : 4), slider.getY() + 5);
            slider.applyUnsavedValue();
        } else {
            throw new AssertionError("Expected a real professional boolean/integer widget: " + control.getClass());
        }
    }
}
