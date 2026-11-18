package org.craftablemc.craftable.planner;

import java.util.function.LongSupplier;

/**
 * 协作式搜索限额；单次 Minecraft 配方调用不能被抢占。状态由所属游戏线程维护，不可并行切片。
 * Cooperative search limits; an individual Minecraft recipe call cannot be preempted.
 * The owning game thread maintains this state and must not run overlapping slices.
 */
public final class SearchBudget {
    public static final int DEFAULT_MAX_STEPS = 128;
    /** 固定表示上限，不是可变世界规则或额外 CPU 额度。
     * Fixed representation bound, not a mutable world rule or extra CPU allowance. */
    public static final int HARD_MAX_STEPS = 256;
    public static final int MAX_DEPTH = 12;
    public static final int MAX_STATES = 2048;
    public static final int MAX_CANDIDATES = 16;
    public static final int MAX_FRONTIER = 128;
    private final LongSupplier clock;
    private final long deadline;
    private final int maximumStates;
    private int states;
    private boolean truncated;
    private boolean exhausted;
    private boolean resumable;
    private long remaining;
    private long started;
    private long sliceDeadline;
    private boolean active;

    public SearchBudget(long nanos) {
        this(System::nanoTime, nanos, MAX_STATES);
    }

    public SearchBudget(LongSupplier clock, long nanos, int maximumStates) {
        if (nanos <= 0 || maximumStates <= 0) throw new IllegalArgumentException("Invalid search budget");
        this.clock = clock;
        this.deadline = clock.getAsLong() + nanos;
        this.maximumStates = maximumStates;
    }

    /** 分配搜索或重放状态前校验世界拥有的限额；调用者须在整个操作中冻结返回值。
     * Validate a world-owned limit before allocating a search or replay state.
     * The caller freezes the returned value for the entire operation. */
    public static int requireStepLimit(int maxPlanSteps) {
        if (maxPlanSteps < 1 || maxPlanSteps > HARD_MAX_STEPS)
            throw new IllegalArgumentException("Invalid plan step limit");
        return maxPlanSteps;
    }

    public boolean enter() {
        if (!alive() || ++states > maximumStates) {
            truncated = exhausted = true;
            return false;
        }
        return true;
    }

    public boolean alive() {
        boolean alive = (resumable ? remaining > (active ? clock.getAsLong() - started : 0)
                : clock.getAsLong() < deadline) && states < maximumStates;
        if (!alive) truncated = exhausted = true;
        return alive;
    }

    public void truncate() { truncated = true; }
    public boolean truncated() { return truncated; }
    /** 丢弃分支会使否定证明不完整，但不耗尽剩余额度，也不使保留的具体成功路线失效。
     * A dropped branch makes a negative proof incomplete, but does not spend
     * the remaining allowance or invalidate a retained positive route. */
    public boolean exhausted() { return exhausted; }
    public int states() { return states; }

    /** CPU 额度跨暂停保留；tick 间等待不计费，新切片不会补充目标的总额度。
     * CPU allowance survives pauses; waiting between ticks spends no CPU and
     * starting a new slice never replenishes the target's total allowance. */
    public static SearchBudget resumable(LongSupplier clock, long nanos, int states) {
        var result = new SearchBudget(clock, nanos, states);
        result.resumable = true;
        result.remaining = nanos;
        return result;
    }

    public static SearchBudget resumable(long nanos) {
        return resumable(System::nanoTime, nanos, MAX_STATES);
    }

    void beginSlice(long nanos) {
        if (active || nanos <= 0) throw new IllegalStateException("Invalid or overlapping search slice");
        started = clock.getAsLong();
        sliceDeadline = started + Math.min(nanos, Long.MAX_VALUE / 2);
        active = true;
    }

    void endSlice() {
        if (active && resumable) remaining = Math.max(0, remaining - Math.max(0, clock.getAsLong() - started));
        active = false;
    }

    // 仅在取出任务/闭包条目前检查切片边界；状态内部用 alive()，避免暂停丢失半绑定网格。
    // Only check a slice boundary BEFORE removing a task/closure entry. Calls
    // inside one state use alive(), so a pause cannot discard a half-bound grid.
    boolean canContinue() { return alive() && clock.getAsLong() < sliceDeadline; }
    public long remainingNanos() {
        return resumable ? Math.max(0, remaining - (active ? Math.max(0, clock.getAsLong() - started) : 0))
                : Math.max(0, deadline - clock.getAsLong());
    }
}
