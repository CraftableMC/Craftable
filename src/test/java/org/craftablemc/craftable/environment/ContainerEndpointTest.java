package org.craftablemc.craftable.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.craftablemc.craftable.testsupport.SizedContainerStub;
import org.junit.jupiter.api.Test;

/**
 * 用只提供容量的容器替身检查显式子范围，捕获越界槽位暴露；不初始化物品或把范围合法误当权限合法。
 * Use a size-only container stub to catch exposed out-of-range slots without initializing items; valid ranges do not imply valid permissions.
 */
class ContainerEndpointTest {
    @Test
    void acceptsAnExplicitSubrange() {
        ContainerEndpoint endpoint = new ContainerEndpoint(
                "player:test:inventory", EndpointKind.PLAYER, null, new SizedContainerStub(41), 0, 36);

        assertEquals(36, endpoint.slotCount());
    }

    @Test
    void rejectsRangesOutsideTheContainer() {
        SizedContainerStub container = new SizedContainerStub(9);

        assertThrows(IllegalArgumentException.class, () -> new ContainerEndpoint(
                "bad", EndpointKind.BLOCK, null, container, 3, 7));
    }
}
