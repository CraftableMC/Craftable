package org.craftablemc.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.junit.jupiter.api.Test;

/**
 * 固定只读诊断缓存的上下文和年龄边界，防止移动、时钟回退或禁用缓存后复用旧快照；这些命中不代表可执行新鲜度。
 * Pin diagnostic-cache context/age boundaries against reuse after movement, clock rollback or cache disablement; cache hits do not establish execution freshness.
 */
class EnvironmentSnapshotCacheKeyTest {
    private static final EnvironmentScanSettings SETTINGS = new EnvironmentScanSettings(8, 4, 5, true);

    @Test
    void reusesOnlyTheSameContextWithinItsAgeBudget() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                Level.OVERWORLD, BlockPos.ZERO, SETTINGS, 100, 7, List.of(), List.of());
        EnvironmentSnapshotCacheKey same = new EnvironmentSnapshotCacheKey(
                Level.OVERWORLD.location(), BlockPos.ZERO, SETTINGS);
        EnvironmentSnapshotCacheKey moved = new EnvironmentSnapshotCacheKey(
                Level.OVERWORLD.location(), BlockPos.ZERO.above(), SETTINGS);

        assertTrue(same.canReuse(snapshot, 105));
        assertFalse(same.canReuse(snapshot, 106));
        assertFalse(same.canReuse(snapshot, 99));
        assertFalse(moved.canReuse(snapshot, 101));
    }

    @Test void zeroDisablesDiagnosticReuseEvenWithinTheCaptureTick() {
        var disabled = new EnvironmentScanSettings(8, 4, 0, true);
        var snapshot = new EnvironmentSnapshot(Level.OVERWORLD, BlockPos.ZERO, disabled, 100, 7, List.of(), List.of());
        assertFalse(new EnvironmentSnapshotCacheKey(Level.OVERWORLD.location(), BlockPos.ZERO, disabled).canReuse(snapshot, 100));
        assertFalse(new EnvironmentSnapshotCacheKey(Level.OVERWORLD.location(), BlockPos.ZERO, disabled).canReuse(snapshot, 101));
    }
}
