package org.craftablemc.craftable.client;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 不初始化Minecraft或加载配置即可检查提示的共享元数据、样式与调用者所有权，捕获重复页脚或错误取当前值；不证明屏幕排版。
 * Check shared tooltip metadata, styles and caller ownership without Minecraft/config loading, catching duplicate footers/current-value leaks; not screen layout.
 */
class SettingTooltipTest {
    @Test void allRegisteredNumericSettingsShareTheSameSeparatedGrayMetadataFooter() {
        var values = List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC).stream()
                .flatMap(spec -> ConfigSettings.values(spec).stream()).filter(SettingTooltip::hasNumericRange).toList();
        assertFalse(values.isEmpty(), "numeric footer coverage must not be vacuous");
        assertTrue(values.stream().anyMatch(value -> value.getPath().equals(List.of("environment", "diagnosticCacheTicks"))),
                "professional-only numeric settings must use the same helper");
        for (var value : values) {
            var description = SettingTooltip.description(value);
            onlyTranslation(description, value.getSpec().getTranslationKey() + ".tooltip");
            assertFooter(description, value);
            var original = Component.literal("Unchanged explanation").withStyle(ChatFormatting.WHITE);
            var before = original.copy();
            var professional = SettingTooltip.withNumericFooter(original, value);
            assertEquals(before, original, "builder mutated its caller's description");
            assertTrue(professional.getString().startsWith(original.getString() + "\n\n"), "body changed or footer lost its separator");
            assertColor(professional, 0, original.getString().length(), ChatFormatting.WHITE);
            assertFooter(professional, value);
        }
    }

    @Test void unknownNumericSettingUsesItsOwnDomainAndDefaultNotTheCurrentValue() {
        var builder = new ModConfigSpec.Builder();
        var value = builder.translation("config.future.numeric").defineInRange("future.limit", 37, -3, 912);
        var spec = builder.build();
        var current = CommentedConfig.inMemory();
        current.set(value.getPath(), 88);
        assertEquals(88, value.getRaw(current, value.getPath(), value::getDefault));
        assertFalse(spec.isLoaded());
        assertThrows(IllegalStateException.class, value::get);
        var description = SettingTooltip.description(value);
        assertFooter(description, value);
        var range = onlyTranslation(description, "screen.craftable.settings.range");
        var fallback = onlyTranslation(description, "screen.craftable.settings.default_value");
        assertArrayEquals(new Object[]{"-3", "912"}, range.getArgs());
        assertArrayEquals(new Object[]{"37"}, fallback.getArgs());
        assertEquals(88, (int) current.get(value.getPath()), "tooltip builder changed the current value");
    }

    @Test void nonnumericDescriptionsRemainUnmodifiedWithoutASecondDefaultFooter() {
        var values = List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC).stream()
                .flatMap(spec -> ConfigSettings.values(spec).stream())
                .filter(value -> !(value.getDefault() instanceof Number)).toList();
        assertFalse(values.isEmpty());
        assertTrue(values.stream().anyMatch(value -> value.getDefault() instanceof List<?>));
        assertTrue(values.stream().anyMatch(value -> value.getDefault() instanceof Enum<?>));
        assertTrue(values.stream().anyMatch(value -> value.getDefault() instanceof Boolean));
        for (var value : values) {
            assertFalse(SettingTooltip.hasNumericRange(value));
            var original = Component.literal("Original nonnumeric platform tooltip");
            assertSame(original, SettingTooltip.withNumericFooter(original, value));
            var nativeDescription = SettingTooltip.description(value);
            onlyTranslation(nativeDescription, value.getSpec().getTranslationKey() + ".tooltip");
            assertTrue(translations(nativeDescription).stream().noneMatch(contents -> List.of(
                    "screen.craftable.settings.range", "screen.craftable.settings.default_value").contains(contents.getKey())));
        }
    }

    @Test void footerAssertionsAcceptInheritedStylesButRejectDuplicatesAndWrongColor() {
        var builder = new ModConfigSpec.Builder();
        var value = builder.translation("config.future.numeric").defineInRange("future.limit", 7, 1, 9);
        builder.build();
        var range = Component.translatable("screen.craftable.settings.range", "1", "9");
        var fallback = Component.translatable("screen.craftable.settings.default_value", "7");
        // 文本等价但分隔符并入正文、灰色由嵌套父节点继承，用来防止断言绑定某种组件树的拼接细节。
        // Equivalent text merges separators into the body and inherits gray from a nested parent, preventing assertions from binding to tree construction details.
        var nested = Component.literal("Body\n\n").append(Component.empty().withStyle(ChatFormatting.GRAY)
                .append(range).append("\n").append(fallback));
        assertFooter(nested, value);
        assertThrows(AssertionError.class, () -> assertFooter(nested.copy()
                .append(Component.empty().append(range.copy())), value));
        var wrongColor = Component.literal("Body\n\n").append(range.copy().withStyle(ChatFormatting.RED))
                .append("\n").append(fallback.copy().withStyle(ChatFormatting.GRAY));
        assertThrows(AssertionError.class, () -> assertFooter(wrongColor, value));
    }

    private static void assertFooter(Component description, ModConfigSpec.ConfigValue<?> value) {
        var range = onlyTranslation(description, "screen.craftable.settings.range");
        var fallback = onlyTranslation(description, "screen.craftable.settings.default_value");
        var metadata = value.getSpec().getRange();
        assertArrayEquals(new Object[]{String.valueOf(metadata.getMin()), String.valueOf(metadata.getMax())}, range.getArgs());
        assertArrayEquals(new Object[]{String.valueOf(value.getDefault())}, fallback.getArgs());
        String footer = "\n\n" + Component.translatable(range.getKey(), range.getArgs()).getString()
                + "\n" + Component.translatable(fallback.getKey(), fallback.getArgs()).getString();
        String rendered = description.getString();
        assertTrue(rendered.endsWith(footer), "footer must follow the body as separate range/default lines");
        assertColor(description, rendered.length() - footer.length() + 2, rendered.length(), ChatFormatting.GRAY);
    }

    private static TranslatableContents onlyTranslation(Component description, String key) {
        var matches = translations(description).stream().filter(contents -> contents.getKey().equals(key)).toList();
        assertEquals(1, matches.size(), "missing or duplicated tooltip role: " + key);
        return matches.getFirst();
    }

    private static List<TranslatableContents> translations(Component component) {
        var result = new ArrayList<TranslatableContents>();
        if (component.getContents() instanceof TranslatableContents contents) {
            result.add(contents);
            for (var argument : contents.getArgs())
                if (argument instanceof Component nested) result.addAll(translations(nested));
        }
        for (var sibling : component.getSiblings()) result.addAll(translations(sibling));
        return result;
    }

    /**
     * 带样式遍历包含继承颜色，忽略等价的追加/嵌套方式；偏移基于渲染字符而非组件树槽位。
     * Styled visitation includes inherited colors and ignores equivalent append/nesting choices; offsets are rendered characters, not tree slots.
     */
    private static void assertColor(Component component, int start, int end, ChatFormatting expected) {
        var colors = new ArrayList<Integer>();
        component.visit((style, text) -> {
            Integer color = style.getColor() == null ? null : style.getColor().getValue();
            for (int i = 0; i < text.length(); i++) colors.add(color);
            return Optional.empty();
        }, Style.EMPTY);
        String rendered = component.getString();
        assertEquals(rendered.length(), colors.size());
        for (int i = start; i < end; i++) if (rendered.charAt(i) != '\n')
            assertEquals(expected.getColor(), colors.get(i), "tooltip color at rendered offset " + i);
    }
}
