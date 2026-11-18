package org.craftablemc.craftable.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.workstation.WorkstationCapability;
import org.craftablemc.craftable.workstation.WorkstationEndpoint;

/**
 * 单次服务端扫描的环境视图：列表结构不可变，但端点仍是活句柄，不是可持久化的纯值。
 * 仅主线程访问，不落盘、不发给客户端；浏览同步必须另投影为 BrowsingSnapshot 副本。
 * Server-authoritative single-scan view: immutable list structure, but endpoints remain live handles rather than persistent values.
 * Server-thread-only, never persisted or sent to clients; browse sync projects separate BrowsingSnapshot copies.
 */
public record EnvironmentSnapshot(
        ResourceKey<Level> dimension,
        BlockPos origin,
        EnvironmentScanSettings scanSettings,
        long capturedGameTime,
        long generation,
        List<ContainerEndpoint> endpoints,
        List<WorkstationEndpoint> workstations) {

    public EnvironmentSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(scanSettings, "scanSettings");
        origin = origin.immutable();
        endpoints = List.copyOf(endpoints);
        workstations = List.copyOf(workstations);
        requireUniqueIds(endpoints.stream().map(ContainerEndpoint::id).toList(), "resource endpoint");
        requireUniqueIds(workstations.stream().map(WorkstationEndpoint::id).toList(), "workstation endpoint");
    }

    public Optional<ContainerEndpoint> endpoint(String id) {
        return endpoints.stream().filter(endpoint -> endpoint.id().equals(id)).findFirst();
    }

    public boolean supports(WorkstationCapability capability) {
        return workstations.stream().anyMatch(endpoint -> endpoint.capability() == capability);
    }

    private static void requireUniqueIds(List<String> ids, String label) {
        HashSet<String> unique = new HashSet<>();
        for (String id : ids) {
            if (!unique.add(id)) {
                throw new IllegalArgumentException("Duplicate " + label + " id: " + id);
            }
        }
    }
}
