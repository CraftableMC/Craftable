package org.craftablemc.craftable.execution;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.environment.ContainerEndpoint;
import org.craftablemc.craftable.environment.EndpointKind;
import org.craftablemc.craftable.environment.EnvironmentSnapshot;

/** 固定来源快照隔离事务/预览算法，不代证环境发现和动态访问权；后两者由真实扫描夹具负责。
 * Fixed-source snapshots isolate transaction/preview algorithms, not discovery
 * or dynamic access authority, which belong to real-scan fixtures. */
final class TransactionTestSupport {
    private TransactionTestSupport() {}

    static EnvironmentSnapshot snapshot(ServerPlayer player, SimpleContainer source) {
        return snapshot(player, new ContainerEndpoint("fixture", EndpointKind.ENDER_CHEST, null,
                source, 0, source.getContainerSize()));
    }

    static EnvironmentSnapshot snapshot(ServerPlayer player, ContainerEndpoint... endpoints) {
        return new EnvironmentSnapshot(player.level().dimension(), player.blockPosition(),
                new EnvironmentScanSettings(4, 2, 0, true), player.level().getGameTime(), 1, List.of(endpoints), List.of());
    }
}
