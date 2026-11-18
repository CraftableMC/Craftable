package org.craftablemc.craftable.execution;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 用虚拟时钟检查准入、扫描、搜索与复验共用剩余额度，防止服务层在阶段切换时重新获得整份预算。
 * Use a virtual clock to catch renewed full budgets between admission, scanning, search and validation; all stages share the remainder.
 */
class CraftingServiceBudgetTest {
    @Test void scanAndValidationSpendTheSameAdmittedAllowance() {
        var clock = new AtomicLong();
        // 入场仅余1毫秒，新的扫描在构造搜索期限前已花费0.8毫秒；期限必须扣除此成本。
        // Entry has only 1 ms left; fresh scanning spends 0.8 ms before constructing the search deadline, which must deduct that cost.
        clock.addAndGet(800_000L);
        var budget = CraftingService.planningBudget(() -> 1_000_000L - clock.get(), clock::get);
        assertNotNull(budget);
        assertEquals(200_000L, budget.remainingNanos());
        clock.addAndGet(150_000L);
        assertTrue(budget.alive());
        // 复验只能使用剩余0.05毫秒，不能另开8毫秒期限。
        // Validation gets the remaining 0.05 ms, not another 8 ms deadline.
        clock.addAndGet(50_000L);
        assertFalse(budget.alive());
        assertTrue(budget.truncated());
    }

    @Test void exhaustedAdmissionCannotStartAnotherSearch() {
        assertNull(CraftingService.planningBudget(() -> 0, () -> 0));
        assertNull(CraftingService.planningBudget(() -> -1, () -> 0));
    }

    @Test void directServiceDefaultsAndLargerSuppliersKeepTheEightMillisecondCap() {
        assertEquals(8_000_000L, CraftingService.planningBudget(null, () -> 0).remainingNanos());
        assertEquals(8_000_000L, CraftingService.planningBudget(() -> 16_000_000L, () -> 0).remainingNanos());
    }
}
