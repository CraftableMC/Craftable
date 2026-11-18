package org.craftablemc.craftable.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/**
 * 注册配置的身份与未保存UI值的桥接，不另定义设置、默认值、校验器或持久化文件。
 * Bridges registered config identities and unsaved UI values without defining another setting, default, validator or backing file.
 * 草稿由编辑界面的线程拥有；调用方必须检查世界归属/连接权限，本类不能授予服务器配置编辑权。
 * Drafts are owned by the editor thread; callers must check world/connection permissions because this class grants no server-config edit authority.
 */
public final class ConfigSettings {
    private ConfigSettings() {}

    /** 只遍历注册元数据，不读取或加载配置文件。 / Enumerate registered metadata without reading/loading a config file. */
    public static List<ConfigValue<?>> values(ModConfigSpec spec) {
        var result = new ArrayList<ConfigValue<?>>();
        collect(Objects.requireNonNull(spec, "spec").getValues(), result);
        result.sort((left, right) -> {
            var a = left.getPath();
            var b = right.getPath();
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                int comparison = a.get(i).compareTo(b.get(i));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(a.size(), b.size());
        });
        return List.copyOf(result);
    }

    private static void collect(UnmodifiableConfig config, List<ConfigValue<?>> result) {
        for (var entry : config.entrySet()) {
            Object value = entry.getRawValue();
            if (value instanceof ConfigValue<?> registered) result.add(registered);
            else if (value instanceof UnmodifiableConfig section) collect(section, result);
        }
    }

    /**
     * NeoForge的有界数值可能声明为Object.class；先取注册范围类型，再回退到默认值类型。
     * NeoForge may expose Object.class for bounded numbers; prefer the registered range type before the default's runtime type.
     */
    public static Class<?> type(ConfigValue<?> value) {
        var metadata = Objects.requireNonNull(value, "value").getSpec();
        Class<?> declared = metadata.getClazz();
        if (declared != null && declared != Object.class) return declared;
        var range = metadata.getRange();
        if (range != null) return range.getClazz();
        Object fallback = Objects.requireNonNull(value.getDefault(), "Config default");
        return fallback instanceof Enum<?> enumeration ? enumeration.getDeclaringClass() : fallback.getClass();
    }

    /**
     * 以ConfigValue对象身份隔离可编辑集合；baseline用于脏值判定，编辑proposed不会即时写回注册值。
     * Isolate editable values by ConfigValue identity; baseline detects edits while editing proposed values does not immediately write registered values.
     * 取消只需丢弃草稿；专业页已保存的更改不属于此草稿的撤销范围。
     * Cancel discards the draft; saved professional-editor changes are outside its rollback scope.
     */
    public static final class Draft {
        private final ModConfigSpec spec;
        private final List<ConfigValue<?>> selected;
        private final Map<ConfigValue<?>, Object> baseline = new IdentityHashMap<>();
        private final Map<ConfigValue<?>, Object> proposed = new IdentityHashMap<>();

        public Draft(ModConfigSpec spec) {
            this(spec, values(spec));
        }

        /** 只允许读、重置和保存所选注册对象，拒绝异源或重复对象。 / Read/reset/save only selected registered identities; reject foreign or duplicated objects. */
        public Draft(ModConfigSpec spec, List<ConfigValue<?>> selected) {
            this.spec = Objects.requireNonNull(spec, "spec");
            requireLoaded();
            var registered = new IdentityHashMap<ConfigValue<?>, Boolean>();
            values(spec).forEach(value -> registered.put(value, Boolean.TRUE));
            this.selected = List.copyOf(selected);
            for (var value : this.selected) {
                if (!registered.containsKey(value) || baseline.containsKey(value))
                    throw new IllegalArgumentException("Selected value is foreign or duplicated: " + value.getPath());
                Object current = frozen(value.getRaw());
                validate(value, current);
                baseline.put(value, current);
                proposed.put(value, current);
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T get(ConfigValue<T> value) {
            requireSelected(value);
            return (T) proposed.get(value);
        }

        /** 草稿也必须先校验；ConfigValue.set本身不负责校验。 / Validate even draft changes first; ConfigValue.set does not validate. */
        public void set(ConfigValue<?> value, Object candidate) {
            requireSelected(value);
            validate(value, candidate);
            proposed.put(value, frozen(candidate));
        }

        public boolean dirty(ConfigValue<?> value) {
            requireSelected(value);
            return !Objects.equals(baseline.get(value), proposed.get(value));
        }

        public boolean dirty() {
            return selected.stream().anyMatch(this::dirty);
        }

        /** 只重置草稿所选值，全部校验后才更新草稿，不写注册配置。 / Validate all selected defaults before resetting the draft; never write registered config here. */
        public void resetDefaults() {
            var defaults = new IdentityHashMap<ConfigValue<?>, Object>();
            for (var value : selected) {
                Object candidate = frozen(value.getDefault());
                validate(value, candidate);
                defaults.put(value, candidate);
            }
            proposed.putAll(defaults);
        }

        /**
         * 仅吸收未被本页编辑的专业页/重载新值；保留显式本地草稿，避免返回页面时覆盖用户意图。
         * Absorb professional-editor/reload changes only for untouched values; retain explicit local edits when returning to the page.
         */
        public boolean refreshUnchanged() {
            requireLoaded();
            var refreshed = new IdentityHashMap<ConfigValue<?>, Object>();
            for (var value : selected) if (!dirty(value)) {
                Object current = frozen(value.getRaw());
                validate(value, current);
                refreshed.put(value, current);
            }
            boolean changed = refreshed.entrySet().stream()
                    .anyMatch(entry -> !Objects.equals(proposed.get(entry.getKey()), entry.getValue()));
            // 成批校验完成后才推进两份草稿视图；无效外部值不能留下半刷新的baseline。
            // Advance both draft views only after batch validation; invalid external values must not leave a partly refreshed baseline.
            baseline.putAll(refreshed);
            proposed.putAll(refreshed);
            return changed;
        }

        public void validateAll() {
            requireLoaded();
            for (var value : selected) validate(value, proposed.get(value));
        }

        /**
         * 首次写入前校验所有候选值，只写脏值；持久化与重载沿用注册spec，不另建文件或回滚专业页保存。
         * Validate all candidates before the first mutation and write only edited values; persistence/reload use the registered spec,
         * never a parallel file or rollback of professional-editor saves.
         * 调用方须在进入此方法前重新检查编辑权限；这里不处理跨线程同步或磁盘写入的事务回滚。
         * Callers must recheck edit permission before entering; this method provides neither cross-thread synchronization nor disk-write transaction rollback.
         */
        public void save() {
            requireLoaded();
            refreshUnchanged();
            validateAll();
            var changed = selected.stream().filter(this::dirty).toList();
            if (changed.isEmpty()) return;
            // 只写本页显式改过的值；保存全部所选值会悄悄覆盖其他页面或重载已经提交的更改。
            // Write only explicit edits here; saving every selected value would silently overwrite another page's or reload's committed changes.
            for (var value : changed) write(value, proposed.get(value));
            spec.save();
            for (var value : changed) baseline.put(value, proposed.get(value));
        }

        /** 仅含所选值、按选择顺序排列的不可变快照。 / Immutable snapshot of only selected values, in selection order. */
        public Map<ConfigValue<?>, Object> snapshot() {
            var copy = new LinkedHashMap<ConfigValue<?>, Object>();
            for (var value : selected) copy.put(value, proposed.get(value));
            return Collections.unmodifiableMap(copy);
        }

        private void requireLoaded() {
            if (!spec.isLoaded()) throw new IllegalStateException("Cannot edit an unloaded config");
        }

        private void requireSelected(ConfigValue<?> value) {
            if (!proposed.containsKey(value)) throw new IllegalArgumentException("Value is not selected by this draft");
        }

        private static void validate(ConfigValue<?> value, Object candidate) {
            var metadata = value.getSpec();
            if (candidate == null || !type(value).isInstance(candidate) || !metadata.test(candidate))
                throw new IllegalArgumentException("Invalid config value: " + value.getPath());
        }

        /**
         * 当前注册列表只含不可变ID字符串；复制外层列表，防止配置/编辑器别名修改baseline或未保存草稿。
         * Registered lists currently contain immutable ID strings; copy the outer list so config/editor aliases cannot change baseline or unsaved values.
         */
        private static Object frozen(Object value) {
            return value instanceof List<?> list ? List.copyOf(list) : value;
        }

        @SuppressWarnings("unchecked")
        private static <T> void write(ConfigValue<T> value, Object candidate) {
            value.set((T) candidate);
        }
    }
}
