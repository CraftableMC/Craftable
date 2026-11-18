package org.craftablemc.craftable.planner;

import java.util.List;
import java.util.Optional;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;

/** 搜索不确定不代表材料不存在；completeSearch 与结果码须一并解释，展示状态不能授予执行权。
 * Search uncertainty is not evidence of absent ingredients; interpret completeSearch with the result code.
 * The display status never grants execution authority. */
public record SearchResult(CraftingResultCode code, Optional<CraftPlan> plan,
        List<CraftPlan.Missing> missing, boolean completeSearch, int visitedStates) {
    public SearchResult {
        missing = List.copyOf(missing);
    }

    public CraftingStatus status() {
        return plan.map(p -> p.partial() ? CraftingStatus.PARTIAL : CraftingStatus.CRAFTABLE)
                .orElse(CraftingStatus.BLOCKED);
    }

    public static SearchResult blocked(CraftingResultCode code) {
        return new SearchResult(code, Optional.empty(), List.of(), true, 0);
    }
}
