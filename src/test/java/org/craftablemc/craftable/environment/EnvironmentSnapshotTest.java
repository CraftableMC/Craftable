package org.craftablemc.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.testsupport.SizedContainerStub;
import org.craftablemc.craftable.workstation.WorkstationCapability;
import org.craftablemc.craftable.workstation.WorkstationEndpoint;
import org.junit.jupiter.api.Test;

/**
 * 检查快照集合所有权与端点ID唯一性，防止调用者后续改表或重复身份使资源/工作站绑定歧义；容器内容仍由执行时复验。
 * Check snapshot collection ownership/endpoint-ID uniqueness against caller mutations or ambiguous resource/workstation bindings; execution revalidates contents.
 */
class EnvironmentSnapshotTest {
    @Test
    void defensivelyCopiesItsEndpointList() {
        List<ContainerEndpoint> endpoints = new ArrayList<>();
        endpoints.add(new ContainerEndpoint(
                "one", EndpointKind.BLOCK, BlockPos.ZERO, new SizedContainerStub(1), 0, 1));

        List<WorkstationEndpoint> workstations = new ArrayList<>();
        workstations.add(new WorkstationEndpoint(
                "player:test:crafting_2x2", WorkstationCapability.CRAFTING_2X2, null));
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                Level.OVERWORLD,
                BlockPos.ZERO,
                new EnvironmentScanSettings(8, 4, 5, true),
                42,
                1,
                endpoints,
                workstations);
        endpoints.clear();
        workstations.clear();

        assertEquals(1, snapshot.endpoints().size());
        assertEquals(1, snapshot.workstations().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.endpoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.workstations().clear());
    }

    @Test
    void rejectsDuplicateResourceEndpointIds() {
        ContainerEndpoint first = new ContainerEndpoint(
                "duplicate", EndpointKind.BLOCK, BlockPos.ZERO, new SizedContainerStub(1), 0, 1);
        ContainerEndpoint second = new ContainerEndpoint(
                "duplicate", EndpointKind.BLOCK, BlockPos.ZERO.above(), new SizedContainerStub(1), 0, 1);

        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(
                Level.OVERWORLD,
                BlockPos.ZERO,
                new EnvironmentScanSettings(8, 4, 5, true),
                42,
                1,
                List.of(first, second),
                List.of()));
    }

    @Test
    void rejectsDuplicateWorkstationEndpointIds() {
        WorkstationEndpoint first = new WorkstationEndpoint(
                "duplicate", WorkstationCapability.CRAFTING_3X3, BlockPos.ZERO);
        WorkstationEndpoint second = new WorkstationEndpoint(
                "duplicate", WorkstationCapability.CRAFTING_3X3, BlockPos.ZERO.above());

        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(
                Level.OVERWORLD,
                BlockPos.ZERO,
                new EnvironmentScanSettings(8, 4, 5, true),
                42,
                1,
                List.of(),
                List.of(first, second)));
    }
}
