package org.craftablemc.craftable.config;

/**
 * 经校验的服务端扫描值，参与环境快照身份；一次扫描持有该值，避免中途反复读取可变配置。
 * Validated server scan values participating in snapshot identity; one scan keeps this value instead of rereading mutable config midway.
 * diagnosticCacheTicks只限制只读诊断缓存，不延长浏览租约或授权提交。
 * diagnosticCacheTicks bounds only read-only diagnostic caching, never browsing leases or commit authority.
 */
public record EnvironmentScanSettings(
        int horizontalRadius,
        int verticalRadius,
        int diagnosticCacheTicks,
        boolean includeEnderChest) {
    public static final int MIN_HORIZONTAL_RADIUS = 1;
    public static final int MAX_HORIZONTAL_RADIUS = 16;
    public static final int MIN_VERTICAL_RADIUS = 0;
    public static final int MAX_VERTICAL_RADIUS = 8;
    public static final int MIN_DIAGNOSTIC_CACHE_TICKS = 0;
    public static final int MAX_DIAGNOSTIC_CACHE_TICKS = 20;

    public EnvironmentScanSettings {
        requireInRange("horizontalRadius", horizontalRadius, MIN_HORIZONTAL_RADIUS, MAX_HORIZONTAL_RADIUS);
        requireInRange("verticalRadius", verticalRadius, MIN_VERTICAL_RADIUS, MAX_VERTICAL_RADIUS);
        requireInRange("diagnosticCacheTicks", diagnosticCacheTicks, MIN_DIAGNOSTIC_CACHE_TICKS, MAX_DIAGNOSTIC_CACHE_TICKS);
    }

    /** 使用long计算有界扫描体积，不访问或加载区块。 / Compute bounded scan volume as long without accessing/loading chunks. */
    public long scanVolume() {
        long diameter = horizontalRadius * 2L + 1L;
        return diameter * diameter * (verticalRadius * 2L + 1L);
    }

    private static void requireInRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
