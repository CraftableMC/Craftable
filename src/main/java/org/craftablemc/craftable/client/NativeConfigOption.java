package org.craftablemc.craftable.client;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;

/** 中文：注册配置到原版控件的表现适配器，不另定义取值域或默认值，也不直接保存配置。
 * 回调只写页面草稿，使用与 NeoForge 专业页相同的 ValueSpec。
 * English: A registered-config-to-native-widget presentation adapter, defining no extra domains/defaults or direct persistence.
 * Callbacks update only the page draft, using the same ValueSpecs as NeoForge's professional editor. */
final class NativeConfigOption<T> {
    private final ModConfigSpec.ConfigValue<T> configValue;
    private final OptionInstance<T> option;

    private NativeConfigOption(ModConfigSpec.ConfigValue<T> value, OptionInstance<T> option) {
        this.configValue = value;
        this.option = option;
    }

    ModConfigSpec.ConfigValue<T> configValue() { return configValue; }
    OptionInstance<T> option() { return option; }

    static List<ModConfigSpec.ConfigValue<?>> definitions(ModConfigSpec spec) {
        // 中文：只有诊断 TTL 有意限于专业页，其他新注册设置应同时出现在两种编辑器。
        // Only diagnostic TTL is intentionally professional-only.
        // Newly registered settings otherwise appear in both editors.
        return ConfigSettings.values(spec).stream()
                .filter(value -> !value.getPath().equals(List.of("environment", "diagnosticCacheTicks")))
                .sorted(java.util.Comparator.<ModConfigSpec.ConfigValue<?>>comparingInt(NativeConfigOption::order)
                        .thenComparing(value -> String.join(".", value.getPath())))
                .toList();
    }

    private static int order(ModConfigSpec.ConfigValue<?> value) {
        var order = List.of("recipeBookEnhancements", "unlockedOnly", "detailedFailureFeedback", "partialPolicy",
                "allowSurplusDrops", "doublePressMillis", "horizontalRadius", "verticalRadius", "includeEnderChest",
                "directPreparationMode", "surplusDelivery", "maxCraftBatch", "maxPlanSteps", "timeCompression", "maxFurnaceBatches",
                "directPreparationAllowedRecipes");
        int index = order.indexOf(value.getPath().getLast());
        return index < 0 ? order.size() : index;
    }

    /** 中文：列表使用有界配方编辑器，仍保留相同注册身份。
     * English: List values use the bounded recipe editor, with the same registered identities. */
    static boolean scalar(ModConfigSpec.ConfigValue<?> value) { return !List.class.isAssignableFrom(ConfigSettings.type(value)); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static NativeConfigOption<?> create(ModConfigSpec.ConfigValue<?> value, ConfigSettings.Draft draft) {
        var spec = value.getSpec();
        Class<?> type = ConfigSettings.type(value);
        String key = java.util.Objects.requireNonNull(spec.getTranslationKey(), "Config title is required");
        if (type == Boolean.class) {
            var typed = (ModConfigSpec.ConfigValue<Boolean>) value;
            return bind(typed, OptionInstance.createBoolean(key,
                    OptionInstance.cachedConstantTooltip(SettingTooltip.description(value)),
                    typed.getDefault(), v -> draft.set(value, v)), draft);
        } else if (type == Integer.class && spec.getRange() != null) {
            var typed = (ModConfigSpec.ConfigValue<Integer>) value;
            var range = spec.<Integer>getRange();
            return bind(typed, new OptionInstance<Integer>(key,
                    OptionInstance.cachedConstantTooltip(SettingTooltip.description(value)),
                    (caption, v) -> CommonComponents.optionNameValue(caption, display(value, v)),
                    new OptionInstance.IntRange(range.getMin(), range.getMax()),
                    typed.getDefault(), v -> draft.set(value, v)), draft);
        } else if (type.isEnum()) {
            return createEnum((ModConfigSpec.ConfigValue) value, draft, key);
        } else {
            throw new IllegalArgumentException("No native control for registered config " + value.getPath());
        }
    }

    private static <E extends Enum<E>> NativeConfigOption<E> createEnum(ModConfigSpec.ConfigValue<E> value,
            ConfigSettings.Draft draft, String key) {
        List<E> candidates = candidates(value.getDefault(), value.getSpec());
        Codec<E> codec = Codec.STRING.xmap(name -> candidates.stream().filter(v -> v.name().equals(name))
                .findFirst().orElseThrow(), Enum::name);
        return bind(value, new OptionInstance<>(key,
                OptionInstance.cachedConstantTooltip(SettingTooltip.description(value)),
                (caption, v) -> display(value, v), new OptionInstance.Enum<>(candidates, codec),
                value.getDefault(), v -> draft.set(value, v)), draft);
    }

    /** 中文：两种界面按同一解释顺序轮换选项，不改变稳定的网络/配置枚举身份。
     * English: Both presentations cycle options in the same explanatory order without changing stable wire/config enum identities. */
    static <E extends Enum<E>> List<E> candidates(E current, ModConfigSpec.ValueSpec metadata) {
        var candidates = Arrays.stream(current.getDeclaringClass().getEnumConstants())
                .filter(metadata::test);
        if (current instanceof DirectPreparationPolicy.Mode) {
            var order = List.of(DirectPreparationPolicy.Mode.DISABLED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION,
                    DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.Mode.ALL_SUPPORTED);
            candidates = candidates.sorted(java.util.Comparator.comparingInt(order::indexOf));
        }
        return candidates.toList();
    }

    private static <T> NativeConfigOption<T> bind(ModConfigSpec.ConfigValue<T> value,
            OptionInstance<T> option, ConfigSettings.Draft draft) {
        option.set(draft.get(value));
        return new NativeConfigOption<>(value, option);
    }

    static Component display(ModConfigSpec.ConfigValue<?> value, Object current) {
        if (current instanceof Boolean enabled) return CommonComponents.optionStatus(enabled);
        if (current instanceof CraftableServerConfig.SurplusDelivery surplus)
            return text("surplus." + surplus.name().toLowerCase(Locale.ROOT));
        if (current instanceof DirectPreparationPolicy.Mode mode)
            return text("direct_mode." + mode.name().toLowerCase(Locale.ROOT));
        if (current instanceof Enum<?> enumeration) return Component.literal(enumeration.name());
        if (value.getPath().getLast().equals("doublePressMillis")) return text("millis", current);
        return Component.literal(String.valueOf(current));
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings." + key, args);
    }
}
