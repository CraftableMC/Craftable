package org.craftablemc.craftable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 固定扫描值对象的体积与范围约束，防止无界设置进入扫描；本测试不加载区块或证明端点权限。
 * Pin scan-volume/range contracts before settings reach discovery; this test loads no chunks and proves no endpoint permissions.
 */
class EnvironmentScanSettingsTest {
    @Test
    void computesTheBoundedScanVolume() {
        EnvironmentScanSettings settings = new EnvironmentScanSettings(8, 4, 5, true);

        assertEquals(2601, settings.scanVolume());
    }

    @Test
    void rejectsAnUnboundedHorizontalRadius() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentScanSettings(17, 4, 5, true));
    }

    @Test
    void rejectsAnUnboundedCacheLifetime() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentScanSettings(8, 4, 21, true));
    }
}
