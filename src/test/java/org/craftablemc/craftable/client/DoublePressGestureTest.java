package org.craftablemc.craftable.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 用确定性时间和菜单身份覆盖双击配对边界，防止长按、三连击或跨目标/菜单复用旧意图；不代替服务端关联校验。
 * Use deterministic times/menu identities to catch held keys, triple presses and cross-target/menu pairing; server correlation is tested separately.
 */
class DoublePressGestureTest {
    @Test void repeatNeedsReleaseAndPairDoesNotExtendIntoTriplePress() {
        var gesture = new DoublePressGesture();
        var menu = new Object();
        assertEquals(-1, gesture.press(menu, "pick", 1, 0, 350).orElseThrow());
        assertTrue(gesture.press(menu, "pick", 2, 100, 350).isEmpty());
        gesture.release();
        assertEquals(1, gesture.press(menu, "pick", 3, 300, 350).orElseThrow());
        gesture.release();
        assertEquals(-1, gesture.press(menu, "pick", 4, 310, 350).orElseThrow());
    }

    @Test void menuTargetTimeoutAndCloseBreakPairing() {
        var gesture = new DoublePressGesture();
        var menu = new Object();
        gesture.press(menu, "pick", 1, 0, 350); gesture.release();
        assertEquals(-1, gesture.press(menu, "sword", 2, 100, 350).orElseThrow()); gesture.release();
        assertEquals(-1, gesture.press(new Object(), "sword", 3, 200, 350).orElseThrow()); gesture.release();
        assertEquals(-1, gesture.press(menu, "sword", 4, 900, 350).orElseThrow());
        gesture.clear();
        assertEquals(-1, gesture.press(menu, "sword", 5, 910, 350).orElseThrow());
    }
}
