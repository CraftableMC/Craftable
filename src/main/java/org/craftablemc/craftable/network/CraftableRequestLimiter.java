package org.craftablemc.craftable.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 服务端主线程拥有的包频率与共享耗时准入，保护有界但非廉价的世界扫描/规划。
 * Server-main-thread packet-rate and shared-time admission protecting bounded but non-trivial world scans/planning.
 * 玩家时间戳随退出清理；每服务器帧以弱键分离，不排队保存任务或资源状态。
 * Logout clears player timestamps; weak server keys isolate frames without queued work or retained resource state.
 * 频率准入不是权限/材料校验，耗时租约也不能替代规划器内部各预算上限。
 * Rate admission is not authority/input validation, and a time lease never replaces the planner's independent budget limits.
 */
final class CraftableRequestLimiter {
    private static final Map<UUID, Long> LAST_STATUS_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_CREATE_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_OPEN_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_DETAIL_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_SETTINGS_REQUEST = new HashMap<>();
    private static final Map<net.minecraft.server.MinecraftServer, Frame> FRAMES = new java.util.WeakHashMap<>();

    // 后台预览/MAX最多使用共享tick额度的一半；为显式C/确认预留其余部分，不建立工作队列。
    // Background preview/MAX use at most half the shared tick allowance; reserve the rest for explicit C/confirm without a work queue.
    static Lease planning(net.minecraft.server.MinecraftServer server, UUID player, boolean active) {
        Frame frame = FRAMES.computeIfAbsent(server, ignored -> new Frame());
        return planning(frame, server.getTickCount(), player, active);
    }

    /** 显式帧/时钟的同一准入规则供确定性验证；不扩大真实请求预算。 / The same admission policy with explicit frame/clock for deterministic checks, not larger production budgets. */
    static Lease planning(Frame frame, long tick, UUID player, boolean active) {
        // 新tick只重置耗时账本，不能重置轮询历史，否则固定包顺序会反复抢走后台首轮。
        // A new tick resets elapsed-time accounting, not turn history; clearing both would let fixed packet order repeatedly steal the first background turn.
        if (frame.tick != tick) { frame.tick = tick; frame.total = frame.background = 0; }
        long remaining = Math.min(8_000_000L, Math.max(0, 16_000_000L - frame.total));
        if (!active) remaining = Math.min(remaining, Math.max(0, 8_000_000L - frame.background));
        if (!active && !frame.turns.acquire(player, tick, remaining > 0)) remaining = 0;
        return new Lease(frame, active, remaining);
    }

    /**
     * 只保留公平准入元数据，无排队任务或世界状态；最近等待的玩家先于持续抢先者获得下个后台轮次。
     * Fair-admission metadata only, without queued work/world state; a recently waiting player gets the next background turn before a repeat first-arriver.
     */
    static final class BackgroundTurns {
        private final Map<UUID, Turn> players = new HashMap<>();
        boolean acquire(UUID player, long tick, boolean budgetAvailable) {
            // 静默或时钟重置后的记录必须退出竞争，避免已离开页面的玩家永久保留尚未兑现的优先权。
            // Quiet records and records from before a clock reset leave contention so departed viewers cannot retain unserved priority forever.
            players.entrySet().removeIf(e -> tick - e.getValue().seen > 8 || tick < e.getValue().seen);
            var current = players.computeIfAbsent(player, ignored -> new Turn());
            current.seen = tick;
            if (!budgetAvailable || players.values().stream().anyMatch(t -> t != current && t.granted < current.granted))
                return false;
            current.granted = tick;
            return true;
        }
        void remove(UUID player) { players.remove(player); }
        private static final class Turn { long seen; long granted = Long.MIN_VALUE; }
    }

    /** 每服务器当前tick的实际耗时账本，所有租约关闭后累计而非按申请额度预扣。 / Per-server current-tick elapsed-time ledger, charged on lease close rather than requested allowance. */
    static final class Frame {
        final LongSupplier clock;
        long tick = Long.MIN_VALUE;
        long total;
        long background;
        final BackgroundTurns turns = new BackgroundTurns();
        Frame() { this(System::nanoTime); }
        Frame(LongSupplier clock) { this.clock = clock; }
    }

    /**
     * try-with-resources中的一次耗时准入；按单调时钟读取剩余额度，close只记账一次，异常耗时也计入共享额度。
     * One try-with-resources time admission; a monotonic clock measures remaining allowance, close charges once, and exceptional elapsed time is charged too.
     */
    static final class Lease implements AutoCloseable {
        final Frame frame;
        final boolean active;
        final long start;
        final long allowance;
        private boolean closed;
        Lease(Frame frame, boolean active, long allowance) {
            this.frame = frame; this.active = active; this.allowance = allowance;
            start = frame.clock.getAsLong();
        }
        long remaining() { return closed ? 0 : Math.max(0, allowance - Math.max(0, frame.clock.getAsLong() - start)); }
        boolean allowed() { return allowance > 0; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            long elapsed = Math.max(0, frame.clock.getAsLong() - start);
            frame.total += elapsed;
            if (!active) frame.background += elapsed;
        }
    }

    private CraftableRequestLimiter() {}

    static boolean allowStatus(UUID playerId, long gameTime) {
        return allow(LAST_STATUS_REQUEST, playerId, gameTime, 4);
    }

    static boolean allowCreate(UUID playerId, long gameTime) {
        return allow(LAST_CREATE_REQUEST, playerId, gameTime, 1);
    }

    static boolean allowDetail(UUID playerId, long gameTime) {
        return allow(LAST_DETAIL_REQUEST, playerId, gameTime, 4);
    }

    static boolean allowSettings(UUID playerId, long monotonicNanos) {
        // 单人选项页会暂停世界时钟；只读设置重试用单调纳秒，不能借此改变合成限流。
        // Options pause a singleplayer game clock; read-only settings retries use monotonic nanos without changing crafting admission.
        return allow(LAST_SETTINGS_REQUEST, playerId, monotonicNanos, 100_000_000L);
    }

    /** 退出时清理全部该玩家元数据，不能带入下一连接。 / Remove all player metadata on logout so it cannot carry into the next connection. */
    static void clear(UUID playerId) {
        LAST_OPEN_REQUEST.remove(playerId);
        LAST_STATUS_REQUEST.remove(playerId);
        LAST_CREATE_REQUEST.remove(playerId);
        LAST_DETAIL_REQUEST.remove(playerId);
        LAST_SETTINGS_REQUEST.remove(playerId);
        FRAMES.values().forEach(frame -> frame.turns.remove(playerId));
    }

    static boolean allowOpen(UUID playerId, long gameTime) {
        return allow(LAST_OPEN_REQUEST, playerId, gameTime, 10);
    }

    private static boolean allow(Map<UUID, Long> requests, UUID playerId, long gameTime, long interval) {
        Long previous = requests.get(playerId);
        // 拒绝不推进时间戳；时钟回退则接受并重建基线，不能让旧世界时间把玩家永久锁在冷却中。
        // Refusal does not advance the timestamp; clock rollback admits/rebases instead of letting an old world time lock the player in cooldown.
        if (previous != null && gameTime >= previous && gameTime - previous < interval) {
            return false;
        }
        requests.put(playerId, gameTime);
        return true;
    }
}
