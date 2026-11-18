package org.craftablemc.craftable.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 用虚拟时钟核对共享tick账本、后台保留额度与独立限流，防止逐请求预算续杯或暂停世界阻塞设置读取；不代替权限校验。
 * Use virtual time to catch per-request budget refills, lost foreground reserves and paused-world settings lockout; admission is not permission validation.
 */
class CraftableRequestLimiterTest {
    @org.junit.jupiter.api.Test void settingsRetryUsesMonotonicTimeEvenWhenTheWorldIsPaused() {
        var player = java.util.UUID.randomUUID();
        try {
            org.junit.jupiter.api.Assertions.assertTrue(CraftableRequestLimiter.allowSettings(player, 1_000_000_000L));
            org.junit.jupiter.api.Assertions.assertFalse(CraftableRequestLimiter.allowSettings(player, 1_050_000_000L));
            org.junit.jupiter.api.Assertions.assertTrue(CraftableRequestLimiter.allowSettings(player, 1_100_000_000L));
            // 设置读取不能消耗或补充玩法请求的限流状态，两个时钟域必须保持独立。
            // A settings read neither spends nor replenishes gameplay admission; the two clock domains must remain independent.
            org.junit.jupiter.api.Assertions.assertTrue(CraftableRequestLimiter.allowCreate(player, 100));
            org.junit.jupiter.api.Assertions.assertFalse(CraftableRequestLimiter.allowCreate(player, 100));
        } finally { CraftableRequestLimiter.clear(player); }
    }
    @Test
    void activeRequestsBorrowOnlyTheSharedTicksRemainingTime() {
        var clock = new AtomicLong();
        var frame = new CraftableRequestLimiter.Frame(clock::get);
        var player = UUID.randomUUID();
        try (var first = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertEquals(8_000_000L, first.remaining());
            clock.addAndGet(8_000_000L);
        }
        try (var second = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            clock.addAndGet(7_000_000L);
        }
        try (var third = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertEquals(1_000_000L, third.remaining());
            // 发现资源也属于本次准入成本，在求解器开始前就必须从同一份额度中扣除。
            // Resource discovery belongs to admitted work and must spend the same allowance before the solver starts.
            clock.addAndGet(800_000L);
            assertEquals(200_000L, third.remaining());
            clock.addAndGet(200_000L);
            assertEquals(0, third.remaining());
        }
        try (var denied = CraftableRequestLimiter.planning(frame, 100, player, true)) {
            assertFalse(denied.allowed());
        }
        try (var nextTick = CraftableRequestLimiter.planning(frame, 101, player, true)) {
            assertEquals(8_000_000L, nextTick.remaining());
        }
    }

    @Test
    void backgroundExhaustionLeavesTheReservedActiveAllowance() {
        var clock = new AtomicLong();
        var frame = new CraftableRequestLimiter.Frame(clock::get);
        try (var first = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            clock.addAndGet(6_000_000L);
        }
        try (var second = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            assertEquals(2_000_000L, second.remaining());
            clock.addAndGet(2_000_000L);
        }
        try (var background = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), false)) {
            assertFalse(background.allowed());
        }
        try (var active = CraftableRequestLimiter.planning(frame, 100, UUID.randomUUID(), true)) {
            assertEquals(8_000_000L, active.remaining());
        }
    }

    @Test
    void statusRequestsAreLimitedPerPlayer() {
        UUID player = UUID.randomUUID();

        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertFalse(CraftableRequestLimiter.allowStatus(player, 103));
        assertTrue(CraftableRequestLimiter.allowStatus(player, 104));
    }

    @Test
    void aClockResetDoesNotLockAPlayerOut() {
        UUID player = UUID.randomUUID();

        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 5));
    }

    @Test
    void clearingAPlayerReleasesBothLimits() {
        UUID player = UUID.randomUUID();
        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));

        CraftableRequestLimiter.clear(player);

        assertTrue(CraftableRequestLimiter.allowStatus(player, 100));
        assertTrue(CraftableRequestLimiter.allowCreate(player, 100));
    }
}
