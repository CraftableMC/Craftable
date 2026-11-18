package org.craftablemc.craftable.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 中文：两种设置界面共用的注册元数据说明，不另定义范围/默认值，不读已加载值或修改页面草稿。
 * English: Shared registered-metadata descriptions for both settings surfaces, defining no extra range/default and reading no loaded value or editor draft. */
final class SettingTooltip {
    private SettingTooltip() {}

    static Component description(ModConfigSpec.ConfigValue<?> value) {
        var key = java.util.Objects.requireNonNull(value.getSpec().getTranslationKey(), "Config title is required");
        return withNumericFooter(Component.translatable(key + ".tooltip"), value);
    }

    static boolean hasNumericRange(ModConfigSpec.ConfigValue<?> value) {
        return value.getDefault() instanceof Number && value.getSpec().getRange() != null;
    }

    static Component withNumericFooter(Component description, ModConfigSpec.ConfigValue<?> value) {
        if (!hasNumericRange(value)) return description;
        var range = value.getSpec().getRange();
        // 中文：范围/默认值单独成行；紧凑边界不重复平台前缀或正文范围。
        // Keep the short range/default rows separate from the descriptive body.
        // Unlike Range.toString(), these compact bounds do not add a second
        // platform-specific prefix or repeat a range embedded in a comment.
        return description.copy().append("\n\n")
                .append(Component.translatable("screen.craftable.settings.range",
                        String.valueOf(range.getMin()), String.valueOf(range.getMax())).withStyle(ChatFormatting.GRAY))
                .append("\n")
                .append(Component.translatable("screen.craftable.settings.default_value",
                        String.valueOf(value.getDefault())).withStyle(ChatFormatting.GRAY));
    }
}
