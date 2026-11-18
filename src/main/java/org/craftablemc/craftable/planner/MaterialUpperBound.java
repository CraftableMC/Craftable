package org.craftablemc.craftable.planner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.recipe.PlanningInput;

/** 可选且仅用于 MAX 的守恒证明，不是计划或完整失败证明。对每个可达普通配方校验固定两层权重；
 * 校验失败即保持原搜索的未知结果，不构造路线、不递归传播数量，也不新增 CPU 额度。
 * 放松选择/权限后，上限对同资源/目录范围内更严格的意图仍是必要条件。
 * Optional MAX-only conservation certificate, never a plan or full-failure
 * proof. Fixed two-layer weights are checked against every reachable ordinary
 * recipe; a failed check simply leaves the existing bounded search unknown.
 * There is no route construction, recursive quantity propagation or new CPU
 * allowance. Pins/permissions are relaxed, so the ceiling remains necessary
 * for every more restrictive intent in the identical resource/catalog scope. */
public final class MaterialUpperBound {
    // 所属求解器从既有 4 MiB 额度预留，包含临时闭包/配方/池集合和有界权重映射，不另增预算。
    // The owning solver reserves this inside its existing 4 MiB allowance,
    // covering transient closure/recipe/pool sets and the bounded weight map.
    static final long MAX_RETAINED_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ITEMS = 4096;
    private static final int MAX_RECIPES = 4096;
    private static final int MAX_OPTIONS = 128;
    private static final int MAX_OPTION_VISITS = 131_072;
    private static final int MAX_WEIGHT = 64;

    private MaterialUpperBound() {}

    /** 调用者须在已计费切片内使用当前完整存在性闭包及初始账本；发证前再核对源包含关系和配方闭包。
     * 上限仅排除更大的 MAX 数量，不能授权执行、诊断缺料数量或进入部分准备。
     * The caller must use its current complete presence closure and initial
     * ledger, within the already charged search slice. The closure is also
     * checked for source inclusion and recipe closure before issuing a bound.
     * A ceiling excludes larger MAX quantities only; it cannot authorize
     * execution, diagnose missing counts, or enter partial preparation. */
    public static OptionalInt compute(PlanningInput input, CraftRequest request,
            ResourceLedger initial, Set<Item> reachable, SearchBudget budget) {
        if (reachable == null || reachable.size() > MAX_ITEMS || !input.fullySupported()
                || input.entries().size() > MAX_RECIPES || !budget.alive()) return OptionalInt.empty();
        var root = input.find(request.recipe());
        if (root == null || root.requirements().size() > 9) return OptionalInt.empty();
        var checks = new Checks(budget);
        if (initial.initialPotential(Map.of(), reachable, budget).isEmpty()) return OptionalInt.empty();

        // 必须闭合而非只扫描部分可达关系，否则遗漏上游生产者会伪造材料不足。
        // Closedness, not a partial reachability scan, is essential: otherwise
        // an omitted upstream producer could counterfeit material scarcity.
        var closed = new HashSet<>(reachable);
        var runnable = new HashSet<net.minecraft.resources.ResourceLocation>();
        for (var entry : input.entries()) {
            if (!checks.recipe(entry)) return OptionalInt.empty();
            if (input.addReachableOutputs(entry, closed)) return OptionalInt.empty();
            if (entry.requirements().stream().allMatch(d -> java.util.Arrays.stream(d.ingredient().getItems())
                    .anyMatch(s -> reachable.contains(s.getItem())))) runnable.add(entry.id());
        }
        var groups = new LinkedHashMap<Set<Item>, Ingredient>();
        for (var demand : root.requirements()) {
            if (!checks.options(demand.ingredient())) return OptionalInt.empty();
            var items = new HashSet<Item>();
            for (var option : demand.ingredient().getItems()) items.add(option.getItem());
            if (items.isEmpty()) return OptionalInt.empty();
            groups.putIfAbsent(Set.copyOf(items), demand.ingredient());
        }

        int ceiling = Integer.MAX_VALUE;
        boolean proved = false;
        for (var group : groups.entrySet()) {
            if (!budget.alive()) return OptionalInt.empty();
            var weights = new HashMap<Item, Integer>();
            group.getKey().forEach(item -> weights.put(item, 1));
            var producers = input.producing(group.getValue());
            // 证明须检查完整关系而非求解器截断后的路线候选，绝不遗漏替代生产者。
            // A certificate examines the complete relation, not the solver's
            // capped route-choice list. Never omit an alternative producer.
            if (producers.size() > MAX_RECIPES) continue;
            var upstream = new HashSet<Item>();
            int largestUnit = 1;
            boolean bounded = true;
            for (var producer : producers) {
                if (!budget.alive()) return OptionalInt.empty();
                if (!runnable.contains(producer.id())) continue;
                int slots = producer.requirements().size();
                if (slots == 0) { bounded = false; break; }
                long unit = ((long) producer.output().getCount() + slots - 1) / slots;
                if (unit > MAX_WEIGHT) { bounded = false; break; }
                largestUnit = Math.max(largestUnit, (int) unit);
                for (var demand : producer.requirements()) {
                    if (!checks.options(demand.ingredient())) return OptionalInt.empty();
                    for (var option : demand.ingredient().getItems()) upstream.add(option.getItem());
                }
                if (upstream.size() + weights.size() > MAX_ITEMS) { bounded = false; break; }
            }
            if (!bounded) continue;
            int unit = largestUnit;
            upstream.forEach(item -> weights.putIfAbsent(item, unit));
            // 正数量返还容器需要更复杂的证明；此处明确拒绝，而非假设权重为零或忽略可用返还物。
            // Any positive returned container would need a more elaborate
            // certificate. This deliberately refuses it instead of assigning
            // an implicit zero or overlooking a useful crafting remainder.
            boolean returns = false;
            for (var item : weights.keySet()) {
                if (!budget.alive()) return OptionalInt.empty();
                if (input.mayReturn(Ingredient.of(item))) { returns = true; break; }
            }
            if (returns || weights.getOrDefault(root.output().getItem(), 0) != 0) continue;

            boolean conserved = true;
            for (var entry : input.entries()) {
                if (!budget.alive()) return OptionalInt.empty();
                if (!runnable.contains(entry.id())) continue;
                long output = (long) weights.getOrDefault(entry.output().getItem(), 0) * entry.output().getCount();
                if (output == 0) continue;
                long consumed = 0;
                for (var demand : entry.requirements()) {
                    if (!checks.options(demand.ingredient())) return OptionalInt.empty();
                    consumed += minimum(demand.ingredient(), weights);
                }
                if (output > consumed) { conserved = false; break; }
            }
            if (!conserved) continue;
            long perRoot = 0;
            for (var demand : root.requirements()) perRoot += minimum(demand.ingredient(), weights);
            if (perRoot <= 0) continue;
            var available = initial.initialPotential(weights, reachable, budget);
            if (available.isEmpty()) return OptionalInt.empty();
            ceiling = Math.min(ceiling, (int) Math.min(Integer.MAX_VALUE, available.getAsLong() / perRoot));
            proved = true;
        }
        return proved && budget.alive() ? OptionalInt.of(ceiling) : OptionalInt.empty();
    }

    /** OR 槽可选最低权重合法项，AND 槽相加；连不可达项也取最小值只会放宽证明。
     * An OR slot may choose its cheapest legal option; AND slots add. Using
     * the minimum over even unreachable options only weakens the certificate. */
    private static int minimum(Ingredient ingredient, Map<Item, Integer> weights) {
        int result = Integer.MAX_VALUE;
        for (var option : ingredient.getItems()) result = Math.min(result, weights.getOrDefault(option.getItem(), 0));
        return result == Integer.MAX_VALUE ? 0 : result;
    }

    private static final class Checks {
        final SearchBudget budget;
        int visits;
        Checks(SearchBudget budget) { this.budget = budget; }
        boolean options(Ingredient ingredient) {
            int count = ingredient.getItems().length;
            visits += count;
            return count <= MAX_OPTIONS && visits <= MAX_OPTION_VISITS && budget.alive();
        }
        boolean recipe(CraftingRecipes.Entry entry) {
            if (!budget.alive() || entry.requirements().isEmpty() || entry.requirements().size() > 9) return false;
            for (var demand : entry.requirements()) if (!options(demand.ingredient())) return false;
            return true;
        }
    }
}
