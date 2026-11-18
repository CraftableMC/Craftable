package org.craftablemc.craftable.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.config.EnvironmentScanSettings;

/**
 * 只读诊断缓存的纯身份/年龄策略；时钟回退或 TTL=0 均拒绝复用，不授予材料新鲜度。
 * Pure identity/age policy for diagnostic caching; clock reversal or TTL=0 rejects reuse, without granting material freshness.
 */
record EnvironmentSnapshotCacheKey(
        ResourceLocation dimension,
        BlockPos origin,
        EnvironmentScanSettings settings) {
    EnvironmentSnapshotCacheKey {
        origin = origin.immutable();
    }

    boolean canReuse(EnvironmentSnapshot snapshot, long currentGameTime) {
        long age = currentGameTime - snapshot.capturedGameTime();
        return dimension.equals(snapshot.dimension().location())
                && settings.diagnosticCacheTicks() > 0
                && origin.equals(snapshot.origin())
                && settings.equals(snapshot.scanSettings())
                && age >= 0
                && age <= settings.diagnosticCacheTicks();
    }
}
