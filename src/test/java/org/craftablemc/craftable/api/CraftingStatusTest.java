package org.craftablemc.craftable.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * 此枚举是前端三态契约，不应把“待计算/未知”等生命周期细节混入可合成性状态。
 * This enum is the three-state frontend contract; pending/unknown lifecycle details must not become craftability states.
 */
class CraftingStatusTest {
    @Test
    void exposesExactlyTheThreeFrontendStates() {
        assertEquals(
                EnumSet.of(CraftingStatus.CRAFTABLE, CraftingStatus.PARTIAL, CraftingStatus.BLOCKED),
                EnumSet.allOf(CraftingStatus.class));
    }
}
