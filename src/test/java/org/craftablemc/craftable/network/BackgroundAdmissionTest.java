package org.craftablemc.craftable.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定包到达顺序并模拟首个请求耗尽切片，捕获第二玩家永久饥饿；同时检查退出/静默玩家不能永久占住轮次。
 * Fix packet order and simulate the first request consuming the slice to catch second-player starvation; departed/quiet players must release turns.
 */
class BackgroundAdmissionTest {
    @Test void fixedPacketOrderCannotStarveSecondPlayer() {
        var turns = new CraftableRequestLimiter.BackgroundTurns();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        int a = 0, b = 0;
        for (long tick = 0; tick < 400; tick += 4) {
            boolean firstAccepted = turns.acquire(first, tick, true);
            if (firstAccepted) a++;
            // 最坏情况下，先到页面耗尽全部后台切片；后到者本轮无额度，但下轮不能再次被抢先。
            // Worst case: the first page consumes the whole background slice; the later viewer has no allowance now but must not lose every next turn.
            if (turns.acquire(second, tick, !firstAccepted)) b++;
        }
        assertEquals(50, a);
        assertEquals(50, b);
    }

    @Test void departedOrInactivePlayerCannotRetainTurnIndefinitely() {
        var turns = new CraftableRequestLimiter.BackgroundTurns();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        assertTrue(turns.acquire(first, 0, true));
        assertFalse(turns.acquire(second, 0, false));
        assertFalse(turns.acquire(first, 4, true));
        assertTrue(turns.acquire(first, 12, true));
        assertFalse(turns.acquire(second, 12, false));
        turns.remove(second);
        assertTrue(turns.acquire(first, 16, true));
    }
}
