package org.craftablemc.craftable.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.recipe.CraftingRecipes.Entry;

/**
 * 有界需求回溯，供普通、详情和数量路径共用；搜索不访问活容器，也不授予提交权限。
 * 每个分支独占数量与未完网格；显式前沿避免配方深度/批次数变为 Java 调用深度，失败不能泄漏预留。
 * Bounded demand backtracking. Every branch owns its counts and unfinished
 * grids. The explicit frontier avoids turning recipe depth or batch size into
 * Java call-stack depth; a failed branch can never leak reservations to another.
 * Ordinary, detail and quantity paths share this solver; it neither reads live containers nor grants commit authority.
 *
 * 所有可变状态局限于所属游戏线程，切片串行续算；同范围闭包可共享，账本/前沿/结果不共享可变状态。
 * Mutable state is confined to the owning game thread and slices resume serially;
 * a scope may share its closure, but not mutable ledger/frontier/result state.
 */
public final class CraftSearch {
    private final org.craftablemc.craftable.recipe.PlanningInput recipes;
    private final CraftRequest request;
    private final int maxPlanSteps;
    private final ResourceLedger initial;
    private SearchBudget budget;
    private final SearchBudget fullBudget;
    private SearchBudget diagnosticBudget;
    private final Function<CraftPlan, CraftingResultCode> validateDelivery;
    private Set<Item> reachable = Set.of();
    private Reachability shared = new Reachability();
    private FullEvidence previousFull;
    private FullEvidence fullEvidence;
    private int fullSearches;
    private int partialSearches;
    private CraftingResultCode failure = CraftingResultCode.MISSING_INGREDIENTS;
    private List<CraftPlan.Missing> bestMissing = List.of();
    private int bestProgress = -1;
    private int bestBindings = -1;
    private CapacityDiagnosis capacityDiagnosis;
    private enum Phase { START, EXISTING, CLOSURE, FULL, PARTIAL, DONE }
    private Phase phase = Phase.START;
    private Entry root;
    private SearchResult completed;
    private final ArrayDeque<Branch> frontier = new ArrayDeque<>();
    private final Set<Object> visited = new HashSet<>();
    private final Set<Object> completedPrefixes = new HashSet<>();
    private boolean partialMode, existingOnly;
    private int initializations;
    private boolean unsupportedRelation;
    private boolean stepBoundChecked;
    private boolean wantsMaterialUpperBound, materialUpperBoundChecked;
    private java.util.OptionalInt materialUpperBound = java.util.OptionalInt.empty();
    // 这是保守的保留状态估算而非 JVM 堆测量；16 MiB 会话中两项续算还须为快照和结果留空间。
    // Conservative retained-state estimate, not a JVM heap measurement. Two
    // continuations must still leave room for snapshot/results in a 16 MiB session.
    private static final long MAX_RETAINED_BYTES = 4L * 1024 * 1024;
    private long frontierBytes, memoBytes;

    public CraftSearch(CraftingRecipes recipes, CraftRequest request, List<ResourceLedger.Source> sources,
            SearchBudget budget, int maxPlanSteps, Function<CraftPlan, CraftingResultCode> validateDelivery) {
        this(recipes.planningInput(), request, sources, budget, maxPlanSteps, validateDelivery);
    }

    /** 调用者冻结世界规则；续算切片和诊断额度均不得改变此限额或补充 CPU 预算。
     * World rules are frozen by the caller. Neither continuation slices nor a
     * diagnostic allowance may change this limit or replenish the CPU budget. */
    public CraftSearch(org.craftablemc.craftable.recipe.PlanningInput recipes, CraftRequest request, List<ResourceLedger.Source> sources,
            SearchBudget budget, int maxPlanSteps, Function<CraftPlan, CraftingResultCode> validateDelivery) {
        this.maxPlanSteps = SearchBudget.requireStepLimit(maxPlanSteps);
        this.recipes = recipes.restrict(request.policy());
        this.request = request;
        this.initial = new ResourceLedger(sources);
        this.budget = budget;
        this.fullBudget = budget;
        this.validateDelivery = validateDelivery;

    }

    /** 调用者只可在同一不可变资源视图中共享此闭包。
     * A caller may share only within one identical immutable resource view. */
    public CraftSearch withReachability(Reachability shared) {
        requireNotStarted(); this.shared = java.util.Objects.requireNonNull(shared); return this;
    }

    /** 可选 MAX 展示证明，计入同一次搜索；既不提供计划，也不证明此请求无法完整合成。
     * Optional MAX presentation certificate, charged to this same search.
     * It neither supplies a plan nor proves this request's full failure. */
    public CraftSearch withMaterialUpperBound() {
        wantsMaterialUpperBound = true;
        return this;
    }
    public java.util.OptionalInt materialUpperBound() { return materialUpperBound; }
    public boolean materialUpperBoundAttempted() { return materialUpperBoundChecked; }
    /** 范围内存在性闭包，不是数量/权限缓存；拥有者须在资源、配方或访问身份变化时更换整个范围。
     * Scope-local presence closure, not a quantity/permission cache; its owner must replace the scope
     * whenever resource, recipe or access identity changes. */
    public static final class Reachability {
        private Set<Item> value;
        private int builds;
        private int attempts;
        private long buildNanos;
        private Set<Item> working;
        private int cursor;
        private boolean changed;
        private SearchBudget budget;
        public int builds() { return builds; }
        public int attempts() { return attempts; }
        public long buildNanos() { return buildNanos; }
        /** 低成本 FULL_ONLY 排除，不是数量充分结论；未知生产语义禁用整个目录的此捷径，须绑定不可变资源身份。
         * Cheap FULL_ONLY exclusion, not a positive quantity verdict. Unknown
         * producer semantics disable this shortcut for the whole catalog. The
         * caller must bind this closure to one immutable resource identity. */
        public FullEvidence excludes(org.craftablemc.craftable.recipe.PlanningInput recipes, CraftRequest request) {
            if (value == null || !recipes.fullySupported() || !request.selections().isEmpty()) return null;
            var entry = recipes.find(request.recipe());
            if (recipes.unavailable(entry) != null) return null;
            for (var demand : entry.requirements()) {
                if (java.util.Arrays.stream(demand.ingredient().getItems()).noneMatch(s -> value.contains(s.getItem())))
                    return new FullEvidence(this, request);
            }
            return null;
        }
        public static Reachability resumable() {
            var result = new Reachability();
            result.budget = SearchBudget.resumable(8_000_000L);
            return result;
        }
    }

    /** 范围内证据，不可序列化为“无配方”结论；服务端只复用自己当前已验证资源/容量/访问范围内的证据。
     * 更换范围会使两端的旧证据失效，不能信任客户端标记。
     * Scope-local evidence, never a serializable "no recipe" assertion.
     * The server can reuse only its own scope's evidence, not a client's flag.
     * Its scope belongs to one verified resource/capacity/access identity;
     * a new scope makes old evidence unusable on either side. */
    public static final class FullEvidence {
        private final Reachability scope;
        private final CraftRequest target;
        // 仅按存在性排除的闭包不依赖步骤限额；完整有界搜索的证据则只属于其冻结的精确限额。
        // Presence-only closure exclusion is independent of step limits. A
        // completed bounded search belongs to its exact frozen limit instead.
        private final int stepLimit;
        private FullEvidence(Reachability scope, CraftRequest request) {
            this(scope, request, 0);
        }
        private FullEvidence(Reachability scope, CraftRequest request, int stepLimit) {
            this.scope = scope;
            this.target = request.withPartial(false);
            this.stepLimit = stepLimit == 0 ? 0 : SearchBudget.requireStepLimit(stepLimit);
        }
    }

    public CraftSearch withFullEvidence(FullEvidence evidence) { requireNotStarted(); previousFull = evidence; return this; }
    public FullEvidence fullEvidence() { return fullEvidence; }
    public int fullSearches() { return fullSearches; }
    public int partialSearches() { return partialSearches; }
    public int initializations() { return initializations; }
    public CraftSearch withDiagnosticBudget(SearchBudget value) { requireNotStarted(); diagnosticBudget = value; return this; }
    private void requireNotStarted() {
        if (phase != Phase.START) throw new IllegalStateException("Cannot rebind a running or completed search");
    }

    public SearchResult run() {
        while (completed == null) advance(Long.MAX_VALUE / 2);
        return completed;
    }

    /** 一次续算，同步服务端 run() 也共用。空值表示暂停而非失败；前沿、记忆和闭包游标归本任务/范围所有。
     * 下次切片从原状态继续，不从根重新开始。
     * One continuation, also used by synchronous server run(). Empty means
     * PAUSED, not failure. Frontier, memo sets and closure cursor stay owned by
     * this task/scope; another slice never starts from its root again. */
    public Optional<SearchResult> advance(long nanos) {
        if (completed != null) return Optional.of(completed);
        if (phase == Phase.START) {
            initializations++;
            root = recipes.find(request.recipe());
            var unavailable = recipes.unavailable(root);
            if (unavailable != null) return finish(SearchResult.blocked(unavailable));
            if (previousFull != null && previousFull.scope == shared && shared.value != null
                    && (previousFull.stepLimit == 0 || previousFull.stepLimit == maxPlanSteps)
                    && previousFull.target.equals(request.withPartial(false))) {
                fullEvidence = previousFull;
                reachable = shared.value;
                beginPartial();
            } else {
                fullSearches++;
                beginSearch(Phase.EXISTING);
            }
            if (completed != null) return Optional.of(completed);
        }
        SearchBudget charged = phase == Phase.CLOSURE && shared.budget != null ? shared.budget : budget;
        charged.beginSlice(nanos);
        try {
            if (!charged.alive()) return finish(limited());
            if (!stepBoundChecked && phase == Phase.EXISTING) {
                stepBoundChecked = true;
                if (exceedsStepLimit()) {
                    // 这是表示容量上限，不是材料不足结论，也不授权执行未完成的准备。
                    // This is a representation bound, not a material denial
                    // or permission to execute an unfinished preparation.
                    budget.truncate(); return finish(limited());
                }
            }
            if (phase == Phase.CLOSURE) {
                long start = System.nanoTime();
                boolean ready;
                try { ready = advanceClosure(charged); }
                finally { shared.buildNanos += System.nanoTime() - start; }
                if (charged.truncated()) return finish(limited());
                if (ready) { reachable = shared.value; beginSearch(Phase.FULL); }
                return Optional.empty();
            }
            if (wantsMaterialUpperBound && !materialUpperBoundChecked && phase == Phase.FULL && shared.value != null) {
                materialUpperBoundChecked = true;
                // 临时映射共用求解器的保留内存上限；预留失败仅放弃这项优化。
                // Its transient maps share the solver's retained-memory cap;
                // inability to reserve them merely omits this optimization.
                if (frontierBytes + memoBytes + MaterialUpperBound.MAX_RETAINED_BYTES <= MAX_RETAINED_BYTES)
                    materialUpperBound = MaterialUpperBound.compute(recipes, request, initial, shared.value, charged);
            }
            CraftPlan plan = search();
            if (plan != null) return finish(success(plan));
            if (budget.exhausted()) return finish(limited());
            if (!frontier.isEmpty()) return Optional.empty();
            // 遗漏备选会使否定证明不成立，但后续切片仍可在保留前沿中找到具体完整见证。
            // An omitted alternative prevents a negative proof, not a later
            // slice from finding a concrete full witness in the kept frontier.
            if (budget.truncated()) return finish(limited());
            if (phase == Phase.EXISTING) {
                failure = CraftingResultCode.MISSING_INGREDIENTS;
                bestMissing = List.of(); bestProgress = bestBindings = -1;
                phase = Phase.CLOSURE;
            } else if (phase == Phase.FULL && failure == CraftingResultCode.MISSING_INGREDIENTS) {
                // 结构/时间截断绝不产生否定证据；只有完整排除全量路线后才可进入部分准备。
                // Structural/time truncation never produces negative evidence.
                fullEvidence = new FullEvidence(shared, request, maxPlanSteps);
                beginPartial();
            } else if (phase == Phase.PARTIAL && capacityDiagnosis != null) {
                return finish(new SearchResult(capacityDiagnosis.code(), Optional.empty(), capacityDiagnosis.missing(),
                        true, visitedStates()));
            } else return finish(new SearchResult(failure, Optional.empty(), bestMissing, true, visitedStates()));
            return Optional.ofNullable(completed);
        } finally { charged.endSlice(); }
    }

    private void beginPartial() {
        if (request.partial() && request.policy() != CraftRequest.PartialPolicy.NEVER) {
            partialSearches++;
            // 独立诊断额度仅能在完整排除全量路线后选择，绝不向全量阶段借出时间/状态数。
            // A separate allowance can only be selected AFTER complete full
            // exclusion. It never lends time/states to the full phase.
            if (diagnosticBudget != null) budget = diagnosticBudget;
            beginSearch(Phase.PARTIAL);
        } else finish(new SearchResult(failure, Optional.empty(), bestMissing, true, visitedStates()));
    }

    private Optional<SearchResult> finish(SearchResult result) {
        completed = result;
        phase = Phase.DONE;
        clearSearchState();
        return Optional.of(result);
    }

    /** 释放当前搜索阶段的保留状态；不重置累计预算、范围闭包、诊断或已产生的证据。
     * Release this phase's retained search state without resetting cumulative budgets, the scope closure,
     * diagnostics or already produced evidence. */
    private void clearSearchState() {
        frontier.clear(); visited.clear(); completedPrefixes.clear();
        frontierBytes = memoBytes = 0;
    }

    /** 终止本任务并释放前沿；不撤销共享闭包，也不影响其他任务，不得复用其未完成结果。
     * Stop this task and release its frontier; do not revoke the shared closure or affect other tasks,
     * and never reuse its unfinished result. */
    public void cancel() {
        if (completed == null) finish(limited());
    }

    private SearchResult success(CraftPlan plan) {
        return new SearchResult(plan.partial() ? CraftingResultCode.PARTIAL_CREATED : CraftingResultCode.CREATED,
                Optional.of(plan), plan.missing(), !plan.partial() || !budget.truncated(), visitedStates());
    }

    private SearchResult limited() {
        return new SearchResult(unsupportedRelation ? CraftingResultCode.UNSUPPORTED_RECIPE : CraftingResultCode.SEARCH_BUDGET_EXCEEDED, Optional.empty(),
                capacityDiagnosis == null ? bestMissing : capacityDiagnosis.missing(), false, visitedStates());
    }

    public int visitedStates() { return fullBudget.states() + (budget == fullBudget ? 0 : budget.states()); }

    private void beginSearch(Phase next) {
        phase = next;
        partialMode = next == Phase.PARTIAL;
        existingOnly = next == Phase.EXISTING;
        if (partialMode) {
            // 完整搜索遇到首个缺口即停；未完成的诊断不能优先于部分阶段对全部需求的核算。
            // Full search stops at its first deficit. Its unfinished diagnostic
            // cannot outrank the whole-demand accounting of the partial phase.
            bestMissing = List.of(); bestProgress = bestBindings = -1;
        }
        clearSearchState();
        State start = new State(initial.copy());
        for (int i = 0; i < request.batches(); i++) start.tasks.addLast(new Root(root));
        push(start);
    }

    private CraftPlan search() {
        while (!frontier.isEmpty() && budget.canContinue() && budget.enter()) {
            Branch next = frontier.pop();
            frontierBytes -= next.retainedBytes();
            State state = next.materialize();
            if (state.tasks.isEmpty()) {
                if (state.rejected != null) { remember(state); continue; }
                if (state.steps.isEmpty() || state.ledger.delivery(true).isEmpty()) {
                    remember(state);
                    continue;
                }
                var plan = new CraftPlan(request.recipe(), request.batches(), state.completed,
                        state.steps, state.ledger.extractions(), state.ledger.delivery(true),
                        state.ledger.delivery(false), mergeMissing(state.missing),
                        state.steps.stream().allMatch(s -> recipes.allowDirectPreparation(recipes.find(s.recipe().id()))));
                // 对现有物品重新装箱不算新合成；此精确核算须与见证重放共用。
                // A reboxed existing item is not a newly craftable result.
                // Keep this exact accounting check shared with witness replay.
                if (!plan.hasMaterialChange()) continue;
                CraftingResultCode invalid = validateDelivery.apply(plan);
                if (invalid == null) {
                    // 容量可以促使选择另一条真实路线，但不能把故意遗漏材料变成更好的准备。
                    // Capacity may select another real route, but cannot make
                    // deliberately omitted materials a better preparation.
                    if (partialMode && capacityDiagnosis != null
                            && addsMissing(plan.missing(), capacityDiagnosis.missing())) continue;
                    return plan;
                }
                if (partialMode && (invalid == CraftingResultCode.NO_OUTPUT_SPACE
                        || invalid == CraftingResultCode.DROP_LIMIT_EXCEEDED)) {
                    if (capacityDiagnosis == null || addsMissing(capacityDiagnosis.missing(), plan.missing()))
                        capacityDiagnosis = new CapacityDiagnosis(invalid, plan.missing());
                }
                failure = invalid;
                continue;
            }
            Task task = state.tasks.removeFirst();
            if (task instanceof Root r) {
                // 只合并等价的完整前缀，不合并未完成原料绑定；避免混合木材批次排列指数重放耗尽库存。
                // 部分准备仍保留更严格、依赖历史的身份键。
                // Collapse equivalent *complete* prefixes, not unfinished
                // ingredient assignments. Mixed wood batch permutations must
                // not replay the same exhausted inventory exponentially.
                // Partial preparation keeps its stricter history-sensitive key.
                if (!partialMode && state.grids.isEmpty() && state.active.isEmpty()) {
                    if (!rememberIdentity(completedPrefixes,
                            () -> List.of(state.completed, state.steps.size(), state.ledger.batchBoundaryIdentity(), state.heatIdentity()),
                            state.ledger.identityBytes(), state)) continue;
                }
                expandRecipe(state, r.recipe, "0", 0, null);
                push(state);
            } else if (task instanceof Finish finish) {
                Grid grid = state.grids.get(finish.grid);
                if (!grid.missing && finish.recipe.furnace() != null && !fuelReady(state, grid, finish)) continue;
                state.grids.remove(finish.grid);
                state.active.remove(finish.recipe.id());
                if (grid.missing) {
                    if (!partialMode) continue;
                    if (grid.parent != null && state.steps.size() == grid.startSteps
                            && java.util.Arrays.stream(grid.taken).allMatch(java.util.Objects::isNull)) {
                        // 没有真实输入或加工为此生产者提供起点时，应解释父需求，而非任意转换。
                        // 例如应缺两个锭而非九个无来源粒；已有四个粒的未完成生产者仍应缺五个粒。
                        // No actual input or processing seeds this producer.
                        // Explain the parent demand instead of an arbitrary
                        // conversion (two ingots, not nine unseeded nuggets).
                        // A seeded incomplete producer keeps its input deficit:
                        // four bound nuggets genuinely need five more nuggets.
                        state.ledger = grid.before.copy();
                        state.bindings = grid.startBindings;
                        state.missing.subList(grid.startMissing, state.missing.size()).clear();
                        state.missing.add(missing(grid.parent));
                    } else {
                        for (var taken : grid.taken) if (taken != null) state.ledger.reserveFrontier(taken);
                    }
                    state.results.put(finish.grid, -1);
                } else {
                    if (state.steps.size() >= maxPlanSteps) { budget.truncate(); continue; }
                    List<ItemStack> inputs = grid.items();
                    List<Integer> origins = java.util.Arrays.stream(grid.taken).map(state.ledger::origin).toList();
                    if (finish.recipe.furnace() != null) {
                        inputs = new ArrayList<>(inputs);
                        origins = new ArrayList<>(origins);
                        for (var fuel : grid.fuels) {
                            inputs.add(fuel.stack());
                            origins.add(state.ledger.origin(fuel));
                        }
                    }
                    CraftPlan.Step step = recipes.assemble(finish.recipe, finish.path, inputs);
                    if (step == null) { failure = CraftingResultCode.UNSUPPORTED_RECIPE; continue; }
                    if (!budget.alive()) break;
                    // 趁真实批次引用仍在时记录；事后按物品 ID 重建会错归共享余量、混合批次或已消耗返还物。
                    // Capture the actual lot references while they still exist.
                    // Reconstructing by item ID later would misattribute shared
                    // surplus, mixed batches or a consumed crafting remainder.
                    step = new CraftPlan.Step(step.recipe(), step.path(), step.gridSize(), step.inputs(),
                            step.output(), step.remainders(), origins);
                    int origin = state.steps.size();
                    state.steps.add(step);
                    int lot = state.ledger.produce(step.output(), finish.root, false, finish.path, origin);
                    state.results.put(finish.grid, lot);
                    for (ItemStack remainder : step.remainders()) state.ledger.produce(remainder, false, true, finish.path, origin);
                    if (finish.root) state.completed++;
                }
                push(state);
            } else if (task instanceof Supply supply) {
                Integer lot = state.results.remove(supply.producer);
                var taken = state.ledger.producedChoice(lot == null ? -1 : lot, supply.need.ingredient);
                if (taken == null) {
                    if (!partialMode) continue;
                    state.grids.get(supply.need.grid).missing = true;
                } else {
                    bind(state, supply.need, taken);
                }
                push(state);
            } else if (task instanceof Need originalNeed) {
                var pinnedId = request.selections().get(originalNeed.path);
                var selected = pinnedId == null ? null : recipes.find(pinnedId);
                if (pinnedId != null && (selected == null || !originalNeed.ingredient.test(selected.output()))) {
                    failure = CraftingResultCode.UNSUPPORTED_RECIPE;
                    continue;
                }
                // 选择白桦木板生产者也限定此 OR 原料为白桦木板，否则其他分支的橡木余量会绕过明确选择。
                // A chosen birch-plank producer also selects birch planks as
                // this OR-ingredient's item. Otherwise another branch's oak
                // surplus could silently bypass the user's explicit choice.
                Need need = selected == null ? originalNeed : new Need(Ingredient.of(selected.output()),
                        originalNeed.grid, originalNeed.slot, originalNeed.path, originalNeed.depth, originalNeed.producersOnly);
                var existing = need.producersOnly ? List.<ResourceLedger.Taken>of() : state.ledger.choices(need.ingredient);
                // 保留具体首选续算；备用分支从未改动前像复制，无法保留额外备用也不能丢弃首条路线。
                // Keep the concrete preferred continuation in place. Backups
                // must be copied from the untouched preimage, but inability to
                // retain another backup must never discard this first route.
                Consumer<State> preferred = null;
                List<Consumer<State>> branches = new ArrayList<>();
                for (var taken : existing) {
                    if (preferred == null) { preferred = s -> bind(s, need, taken); continue; }
                    if (!canFork(state, branches.size())) break;
                    branches.add(s -> bind(s, need, taken));
                }
                var parent = state.grids.get(need.grid).recipe;
                boolean allowProducers = !existingOnly && !recipes.requiresExistingProcessingInput(parent);
                boolean deferProducers = allowProducers && !partialMode && !existing.isEmpty();
                if (deferProducers && (!recipes.producing(need.ingredient).isEmpty() || recipes.hasUnsupportedProducer(need.ingredient))
                        && canFork(state, branches.size()))
                    branches.add(s -> s.tasks.addFirst(new Need(need.ingredient, need.grid, need.slot, need.path, need.depth, true)));
                // 不为每个已绑定余量槽立即排序全部生产者；备用分支持有相同前像，仅下游库存失败时排序。
                // Ranking every producer of every already-bound surplus slot
                // is wasteful. The fallback owns the same preimage and ranks
                // them only if existing stock really fails downstream.
                List<Entry> candidates = !allowProducers || deferProducers ? new ArrayList<>() : new ArrayList<>(recipes.producing(need.ingredient));
                if (allowProducers && !deferProducers && recipes.hasUnsupportedProducer(need.ingredient)) {
                    // 省略未知谓词不得证明不可能并授权部分消耗；保留的具体全量见证仍可成功，但否定未被证明。
                    // Omitting unknown predicates must not prove impossibility
                    // and thereby authorize partial consumption. A known full
                    // witness may still succeed; absence remains unproven.
                    unsupportedRelation = true;
                    budget.truncate();
                }
                // 完整合成已被排除后，准备可补缺口，但不能替换已有中间物来伪造“有生产”的前沿。
                // Once full completion was disproved, preparation may fill a
                // deficit but may not replace already-available intermediates
                // just to manufacture a superficially "productive" frontier.
                if (partialMode && !existing.isEmpty()) candidates.clear();
                if (pinnedId != null) candidates.removeIf(e -> !e.id().equals(pinnedId));
                if (!candidates.isEmpty()) {
                    boolean automatic = pinnedId == null && request.selections().keySet().stream()
                            .noneMatch(path -> path.startsWith(need.path + "."));
                    candidates.removeIf(e -> state.active.contains(e.id()) || recipes.isExactInverse(e, parent)
                            || recipes.requiresExistingProcessingInput(e) && !state.ledger.has(e.requirements().getFirst().ingredient())
                            || automatic && recipes.cannotImproveDemand(e, need.ingredient, parent));
                }
                candidates.sort(Comparator.<Entry>comparingInt(e -> estimatedMissing(e, state.ledger))
                        .thenComparing(e -> e.id().toString()));
                boolean permissionBlocked = false;
                CraftingResultCode blockedReason = null;
                int considered = 0;
                for (Entry candidate : candidates) {
                    // 可达性属于候选的输入而非仅其产物：经木板可达木棍不代表已有竹子起点。
                    // 无起点且锁定的转换不能成为拒绝另一条已解锁目标有效准备的理由。
                    // Reachability belongs to a candidate's inputs, not merely
                    // its output (sticks reachable via planks do not seed bamboo).
                    // An unseeded, locked conversion is not a reason to reject
                    // an otherwise unlocked target's useful preparation.
                    if (!candidate.requirements().stream().allMatch(r -> java.util.Arrays.stream(r.ingredient().getItems())
                            .anyMatch(s -> reachable.contains(s.getItem())))) continue;
                    // 固定原料既无生产者也无可能返还来源时，库存耗尽后此分支无法补足；只排除此具体生产者。
                    // 其他预留方式仍在前沿中，不能连带排除。
                    // A fixed raw input with neither producer nor possible
                    // return cannot be supplied by this branch after its stock
                    // runs out. This excludes only that concrete producer;
                    // alternate reservations still live in the frontier.
                    if (!partialMode && candidate.requirements().stream().anyMatch(r -> !state.ledger.has(r.ingredient())
                            && recipes.producing(r.ingredient()).isEmpty()
                            && !recipes.hasUnsupportedProducer(r.ingredient()) && !recipes.mayReturn(r.ingredient()))) continue;
                    var denied = recipes.unavailable(candidate);
                    if (denied != null) {
                        // 无已绑定炉料的假设路线不应掩盖已有配方的真实缺料。
                        // A hypothetical furnace route without its input in the ledger must not eclipse a concrete material deficit.
                        if (candidate.furnace() != null && denied == CraftingResultCode.MISSING_WORKSTATION
                                && !state.ledger.has(candidate.requirements().getFirst().ingredient())) continue;
                        permissionBlocked = true;
                        blockedReason = denied;
                        continue;
                    }
                    if (!reachable.contains(candidate.output().getItem())) continue;
                    if (++considered > SearchBudget.MAX_CANDIDATES || need.depth >= SearchBudget.MAX_DEPTH) {
                        budget.truncate();
                        break;
                    }
                    if (preferred == null) {
                        preferred = s -> expandRecipe(s, candidate, need.path, need.depth, need);
                        continue;
                    }
                    if (!canFork(state, branches.size())) break;
                    branches.add(s -> expandRecipe(s, candidate, need.path, need.depth, need));
                }
                if ((partialMode && existing.isEmpty()) || preferred == null) {
                    var rejected = preferred == null && (permissionBlocked || state.ledger.hasProtected(need.ingredient))
                            ? state.ledger.hasProtected(need.ingredient) ? CraftingResultCode.PROTECTED_INGREDIENTS : blockedReason
                            : null;
                    Consumer<State> markMissing = s -> {
                        s.grids.get(need.grid).missing = true;
                        s.missing.add(missing(need));
                        if (rejected != null) s.rejected = rejected;
                    };
                    // 部分诊断须完成全部同级及根需求；中途失败尚无最终物品或数量结论。
                    // Partial diagnostics must finish all sibling/root demands;
                    // an intermediate failure has neither final items nor counts.
                    if (preferred == null) {
                        if (partialMode) preferred = markMissing;
                        else { markMissing.accept(state); remember(state); continue; }
                    } else if (canFork(state, branches.size())) {
                        branches.add(markMissing);
                    }
                }
                // 单材料 FULL 余量槽不必逐次复制完整历史；省略优化键只会增加状态，不会删除备选或授予证明。
                // 多材料绑定和部分准备仍用完整键；完整批次前缀另行记忆。
                // Avoid copying the entire history for every single-material
                // FULL surplus slot. Omitting this optimization key may spend
                // extra states, never remove an alternative or grant a proof.
                // Multi-material assignments/partial preparation keep the full
                // key; complete batch prefixes are memoized independently.
                if (!branches.isEmpty() && (partialMode || existing.size() > 1)
                        && !rememberIdentity(visited, () -> state.identity(need),
                        state.ledger.identityBytes() + state.tasks.size() * 128L
                                + state.grids.size() * 256L + state.steps.size() * 128L, state)) continue;
                // 所有延后备选共享不可变前像，仅复制和展开实际访问的分支，不预先展开每个现有原料槽的全部生产者。
                // All deferred alternatives share one immutable preimage.
                // Clone/expand only the branch actually visited, rather than
                // all producers of every already-available ingredient slot.
                choose(state, preferred, branches);
            }
        }
        return null;
    }

    private void choose(State state, Consumer<State> preferred, List<Consumer<State>> alternatives) {
        State preimage = alternatives.isEmpty() ? null : state.copy();
        preferred.accept(state);
        // 先为首选路线保留空间，略过备选时只能保留已证明的成功，不能证明无解。
        // Reserve the preferred route first. Omitted alternatives invalidate a denial, not proven success.
        if (!makeRoom(state)) return;
        int kept = 0;
        long room = MAX_RETAINED_BYTES - frontierBytes - memoBytes - state.retainedBytes();
        while (kept < alternatives.size() && kept < SearchBudget.MAX_FRONTIER - frontier.size() - 1) {
            long bytes = new Branch(preimage, alternatives.get(kept)).retainedBytes();
            if (bytes > room) break;
            room -= bytes;
            kept++;
        }
        if (kept < alternatives.size()) budget.truncate();
        for (int slot = kept - 1; slot >= 0; slot--) retain(new Branch(preimage, alternatives.get(slot)));
        retain(new Branch(state, null));
    }

    private boolean rememberIdentity(Set<Object> identities, Supplier<Object> identity, long bytes, State current) {
        if (frontierBytes + memoBytes + current.retainedBytes() + bytes > MAX_RETAINED_BYTES) {
            // 记忆是优化而非具体路线完成的前提；连注定被拒的大键分配也应避免。
            // Memoization is an optimization, not a prerequisite for completing
            // a concrete route. Avoid even allocating a large rejected key.
            budget.truncate(); return true;
        }
        Object key = identity.get();
        if (!identities.add(key)) return false;
        memoBytes += bytes;
        return true;
    }

    private boolean canFork(State state, int staged) {
        if (frontierBytes + memoBytes + state.retainedBytes() * (staged + 2L) > MAX_RETAINED_BYTES) {
            budget.truncate(); return false;
        }
        return true;
    }

    private void push(State state) {
        if (makeRoom(state)) retain(new Branch(state, null));
    }

    private boolean makeRoom(State state) {
        long bytes = state.retainedBytes();
        while (!frontier.isEmpty() && (frontier.size() >= SearchBudget.MAX_FRONTIER
                || frontierBytes + memoBytes + bytes > MAX_RETAINED_BYTES)) {
            frontierBytes -= frontier.removeLast().retainedBytes();
            budget.truncate();
        }
        if (memoBytes + bytes > MAX_RETAINED_BYTES) {
            // 配方完成后当前保留状态可能增长；释放优化键以守住内存硬上限，累计 CPU/状态/深度限制仍负责终止。
            // The retained current state may grow after a recipe finishes.
            // Releasing optimization keys preserves the hard memory bound;
            // the same cumulative CPU/state/depth limits still terminate it.
            visited.clear(); completedPrefixes.clear(); memoBytes = 0;
            budget.truncate();
        }
        if (frontier.size() >= SearchBudget.MAX_FRONTIER || frontierBytes + memoBytes + bytes > MAX_RETAINED_BYTES) {
            budget.truncate(); return false;
        }
        return true;
    }

    private void retain(Branch branch) {
        frontier.push(branch);
        frontierBytes += branch.retainedBytes();
    }

    private record Branch(State preimage, Consumer<State> choice) {
        long retainedBytes() {
            // 对共享不可变前像重复计费以避免低估；物化前还须预留生产者网格、账本与任务展开空间。
            // Repeatedly charge the shared immutable preimage. Also reserve a
            // producer grid/ledger/task expansion before it is materialized.
            return preimage.retainedBytes() + (choice == null ? 0 : 4096L + preimage.ledger.retainedBytes());
        }
        State materialize() {
            if (choice == null) return preimage;
            State state = preimage.copy();
            choice.accept(state);
            return state;
        }
    }

    private void expandRecipe(State state, Entry recipe, String path, int depth, Need parent) {
        int id = state.nextGrid++;
        Grid grid = new Grid(recipe);
        grid.parent = parent;
        grid.before = state.ledger.copy();
        grid.startSteps = state.steps.size();
        grid.startMissing = state.missing.size();
        grid.startBindings = state.bindings;
        state.grids.put(id, grid);
        state.active.add(recipe.id());
        if (parent != null) state.tasks.addFirst(new Supply(parent, id));
        state.tasks.addFirst(new Finish(recipe, id, path, parent == null));
        var requirements = new ArrayList<>(recipe.requirements());
        // 在无关中间生产者回溯前发现固定原料缺口，仅改变访问顺序，不证明数量或存在性；返还物仍可能补足需求。
        // Detect a fixed raw-material deficit before backtracking through
        // unrelated intermediate producers. This changes only visit order;
        // it is not a quantity/presence proof (remainders may supply a need).
        var producerless = new HashSet<Integer>();
        if (phase == Phase.FULL) for (var r : requirements) {
            if (recipes.producing(r.ingredient()).isEmpty() && !recipes.hasUnsupportedProducer(r.ingredient())
                    && !recipes.mayReturn(r.ingredient()))
                producerless.add(r.slot());
        }
        requirements.sort(Comparator.<CraftingRecipes.Requirement>comparingInt(r -> r.ingredient().getItems().length)
                .thenComparingInt(r -> producerless.contains(r.slot()) ? 0 : 1));
        for (int i = requirements.size() - 1; i >= 0; i--) {
            var requirement = requirements.get(i);
            state.tasks.addFirst(new Need(requirement.ingredient(), id, requirement.slot(),
                    path + "." + requirement.slot(), depth + 1));
        }
    }

    private void bind(State state, Need need, ResourceLedger.Taken taken) {
        state.ledger.take(taken);
        state.grids.get(need.grid).taken[need.slot] = taken;
        state.bindings++;
    }

    /** 每种炉型在同一条路线内共用余火，燃料从材料账本实际预留。
     * Heat stays within one route and furnace type; fuel is reserved from the material ledger. */
    private boolean fuelReady(State state, Grid grid, Finish finish) {
        if (state.cookingBatches >= recipes.maxFurnaceBatches()) {
            state.rejected = CraftingResultCode.FURNACE_BATCH_LIMIT;
            remember(state);
            return false;
        }
        var furnace = finish.recipe.furnace();
        if (!furnace.requiresFuel()) {
            state.cookingBatches++;
            return true;
        }
        if (state.heat == null) state.heat = new java.util.EnumMap<>(org.craftablemc.craftable.workstation.WorkstationCapability.class);
        int heat = state.heat.getOrDefault(furnace.station(), 0);
        if (heat >= furnace.ticks()) {
            state.heat.put(furnace.station(), heat - furnace.ticks());
            state.cookingBatches++;
            return true;
        }
        if (grid.fuels.size() == 8) {
            budget.truncate();
            return false;
        }
        var options = state.ledger.choices(recipes.fuels(furnace));
        if (options.isEmpty()) {
            state.rejected = CraftingResultCode.MISSING_FUEL;
            remember(state);
            return false;
        }
        int deficit = furnace.ticks() - heat;
        options.sort(Comparator.comparingInt(taken -> {
            int ticks = recipes.fuelTicks(furnace, taken.stack());
            return ticks >= deficit ? ticks - deficit : 32_000 + deficit - ticks;
        }));
        var alternatives = new ArrayList<Consumer<State>>();
        for (int i = 1; i < options.size() && canFork(state, alternatives.size()); i++)
            alternatives.add(burnFuel(finish, options.get(i)));
        choose(state, burnFuel(finish, options.getFirst()), alternatives);
        return false;
    }

    private Consumer<State> burnFuel(Finish finish, ResourceLedger.Taken fuel) {
        var furnace = finish.recipe.furnace();
        return state -> {
            state.ledger.take(fuel);
            state.grids.get(finish.grid).fuels.add(fuel);
            state.heat.merge(furnace.station(), recipes.fuelTicks(furnace, fuel.stack()), Math::addExact);
            state.tasks.addFirst(finish);
        };
    }

    /** 仅为必要操作数下界：每个根需一次操作，固定重复材料至少再需 ceil(缺口/最大产量) 次。
     * 返还物/未知生产者会禁用该下界，重叠材料组只各自检查不相加；通过不证明材料或交付可行。
     * Necessary operation count only. Each root needs one operation; a fixed
     * repeated ingredient needs at least ceil(shortfall / largest output) more.
     * Returns/unknown producers disable the bound, and overlapping ingredient
     * groups are checked separately, never added. No route is constructed and
     * passing this test proves neither materials nor delivery feasibility. */
    private boolean exceedsStepLimit() {
        if (request.batches() > maxPlanSteps) return true; // 每个根批次至少执行一次。 / Every root batch executes at least once.
        // 此下界至多为一次根操作加每个根槽一个直接生产者；小请求无需在此枚举生产者或扫描来源。
        // 实际搜索仍核算全部上游步骤，通过此检查本身不授予任何权限。
        // This particular lower bound cannot exceed one root operation plus
        // one direct producer per root slot. Small requests need no producer
        // enumeration/source scan here; the actual search still enforces all
        // upstream steps and passing this check authorizes nothing.
        if ((long) request.batches() * (1 + root.requirements().size()) <= maxPlanSteps) return false;
        var groups = new LinkedHashMap<Object, Ingredient>();
        var counts = new HashMap<Object, Integer>();
        for (var demand : root.requirements()) {
            Object key = Set.copyOf(java.util.Arrays.stream(demand.ingredient().getItems()).map(ResourceLedger.Key::of).toList());
            groups.putIfAbsent(key, demand.ingredient());
            counts.merge(key, 1, Math::addExact);
        }
        for (var group : groups.entrySet()) {
            var ingredient = group.getValue();
            if (recipes.hasUnsupportedProducer(ingredient) || recipes.mayReturn(ingredient)) continue;
            int largest = recipes.producing(ingredient).stream().mapToInt(e -> e.output().getCount()).max().orElse(0);
            if (largest == 0) continue;
            long shortfall = (long) counts.get(group.getKey()) * request.batches() - initial.quantity(ingredient);
            long operations = request.batches() + (Math.max(0, shortfall) + largest - 1) / largest;
            if (operations > maxPlanSteps) return true;
        }
        return false;
    }

    private int estimatedMissing(Entry entry, ResourceLedger ledger) {
        // 一粒铁粒不能让九粒合锭排到现成矿石前。只在材料完全同类时用整批数量来排序。
        // One nugget cannot make a batch of nine appear as cheap as smelting available ore. Count identical slots jointly.
        var requirements = entry.requirements();
        if (requirements.size() > 1) {
            var first = requirements.getFirst().ingredient();
            var items = first.getItems();
            if (items.length == 1 && requirements.stream().allMatch(r -> {
                var options = r.ingredient().getItems();
                return options.length == 1 && ItemStack.isSameItem(items[0], options[0]);
            })) return requirements.size() - ledger.availableUpTo(first, requirements.size());
        }
        int result = 0;
        for (var requirement : requirements) if (!ledger.has(requirement.ingredient())) result++;
        return result;
    }

    private boolean advanceClosure(SearchBudget allowance) {
        // 存在性松弛可排除无种子的转换环，但不证明数量足够；精确数量仍由账本求解。
        // 它也避免把缺钻石解释为无从启动的钻石块往返所需九颗钻石。
        // Presence-only relaxation can reject unseeded conversion cycles, but
        // never claims sufficient quantities. Exact quantities are still solved
        // by the ledger. It also keeps missing diamonds as diamonds instead of
        // explaining an impossible diamond-block round trip as nine diamonds.
        if (shared.value != null) return true;
        if (shared.working == null) {
            shared.working = initial.availableItems();
            shared.attempts++;
        }
        Set<Item> result = shared.working;
        while (true) {
            while (shared.cursor < recipes.entries().size()) {
                if (!allowance.canContinue()) return false;
                Entry entry = recipes.entries().get(shared.cursor++);
                // 松弛忽略权限以保留对真实被锁中间物的诊断；实际展开时逐配方校验，此集合从不授权。
                // This relaxation ignores permissions so a genuinely needed
                // locked intermediate can still be diagnosed by search. Every
                // expanded recipe is checked there; this set never authorizes it.
                shared.changed |= recipes.addReachableOutputs(entry, result);
            }
            if (!shared.changed) {
                shared.value = Set.copyOf(result);
                shared.working = null;
                shared.builds++;
                return true;
            }
            shared.changed = false;
            shared.cursor = 0;
        }
    }

    private void remember(State state) {
        int progress = state.completed * maxPlanSteps + state.steps.size();
        // 先比较真实生产、再比较实际绑定输入；可达闭包不能代证材料存在，并列时保留首条路线而非最后失败。
        // Prefer real production, then actual assigned inputs. Never use the
        // reachability closure as evidence that a candidate's materials exist.
        // Stable ties keep the first route rather than the last visited failure.
        if (progress > bestProgress || progress == bestProgress && state.bindings > bestBindings) {
            bestProgress = progress;
            bestBindings = state.bindings;
            bestMissing = mergeMissing(state.missing);
            if (state.rejected != null) failure = state.rejected;
        }
    }

    private static CraftPlan.Missing missing(Need need) {
        return new CraftPlan.Missing(need.path, java.util.Arrays.stream(need.ingredient.getItems())
                .limit(16).map(s -> s.copyWithCount(1)).toList(), 1);
    }

    private static List<CraftPlan.Missing> mergeMissing(List<CraftPlan.Missing> source) {
        Map<Object, CraftPlan.Missing> result = new LinkedHashMap<>();
        for (var item : source) {
            var identity = item.alternatives().stream().map(ResourceLedger.Key::of).toList();
            var old = result.get(identity);
            result.put(identity, old == null ? item : new CraftPlan.Missing(old.path(), old.alternatives(),
                    Math.addExact(old.count(), item.count())));
        }
        return List.copyOf(result.values());
    }

    /** 相同缺口严格增加（也可能多出材料）才可比较；不同原料选择仍不可比，因为其余量可能更适合容量。
     * Strictly more of the same shortages, possibly with extra materials.
     * Different ingredient choices remain incomparable: they can still be a
     * legitimate route whose surplus fits when the preferred route does not. */
    private static boolean addsMissing(List<CraftPlan.Missing> candidate, List<CraftPlan.Missing> baseline) {
        var next = missingCounts(candidate);
        var previous = missingCounts(baseline);
        boolean additional = next.size() > previous.size();
        for (var entry : previous.entrySet()) {
            int count = next.getOrDefault(entry.getKey(), 0);
            if (count < entry.getValue()) return false;
            additional |= count > entry.getValue();
        }
        return additional;
    }

    private static Map<Object, Integer> missingCounts(List<CraftPlan.Missing> missing) {
        var counts = new HashMap<Object, Integer>();
        for (var item : missing) counts.merge(Set.copyOf(item.alternatives().stream().map(ResourceLedger.Key::of).toList()),
                item.count(), Math::addExact);
        return counts;
    }

    private record CapacityDiagnosis(CraftingResultCode code, List<CraftPlan.Missing> missing) {}

    private sealed interface Task permits Root, Need, Finish, Supply {}
    private record Root(Entry recipe) implements Task {}
    private record Need(Ingredient ingredient, int grid, int slot, String path, int depth, boolean producersOnly) implements Task {
        Need(Ingredient ingredient, int grid, int slot, String path, int depth) { this(ingredient, grid, slot, path, depth, false); }
    }
    private record Finish(Entry recipe, int grid, String path, boolean root) implements Task {}
    private record Supply(Need need, int producer) implements Task {}

    private static final class Grid {
        final Entry recipe;
        final ResourceLedger.Taken[] taken;
        final List<ResourceLedger.Taken> fuels = new ArrayList<>();
        boolean missing;
        Need parent;
        ResourceLedger before;
        int startSteps;
        int startMissing;
        int startBindings;
        Grid(Entry recipe) {
            this.recipe = recipe;
            taken = new ResourceLedger.Taken[recipe.gridSize() * recipe.gridSize()];
        }
        Grid copy() {
            Grid copy = new Grid(recipe);
            System.arraycopy(taken, 0, copy.taken, 0, taken.length);
            copy.fuels.addAll(fuels);
            copy.missing = missing;
            copy.parent = parent;
            copy.before = before;
            copy.startSteps = startSteps;
            copy.startMissing = startMissing;
            copy.startBindings = startBindings;
            return copy;
        }
        List<ItemStack> items() {
            return java.util.Arrays.stream(taken).map(t -> t == null ? ItemStack.EMPTY : t.stack().copy()).toList();
        }
        Object identity() {
            return List.of(missing, java.util.Arrays.stream(taken).map(t -> t == null ? List.of()
                    : List.of(t.source(), t.lot(), ResourceLedger.Key.of(t.stack()))).toList(), fuels);
        }
    }

    private static final class State {
        ResourceLedger ledger;
        final ArrayDeque<Task> tasks = new ArrayDeque<>();
        final Map<Integer, Grid> grids = new HashMap<>();
        final Map<Integer, Integer> results = new HashMap<>();
        final Set<ResourceLocation> active = new HashSet<>();
        final List<CraftPlan.Step> steps = new ArrayList<>();
        final List<CraftPlan.Missing> missing = new ArrayList<>();
        int nextGrid;
        int completed;
        int bindings;
        int cookingBatches;
        java.util.EnumMap<org.craftablemc.craftable.workstation.WorkstationCapability, Integer> heat;
        CraftingResultCode rejected;

        State(ResourceLedger ledger) { this.ledger = ledger; }
        long retainedBytes() {
            long bytes = 256L + ledger.retainedBytes() + tasks.size() * 128L + steps.size() * 2048L
                    + missing.size() * 2048L + results.size() * 64L;
            // 对共享前像重复计费以免低估。 / Count shared preimages repeatedly rather than undercounting them.
            for (var grid : grids.values()) bytes += 512L + (grid.before == null ? 0 : grid.before.retainedBytes());
            return bytes;
        }
        State copy() {
            State copy = new State(ledger.copy());
            copy.tasks.addAll(tasks);
            grids.forEach((id, grid) -> copy.grids.put(id, grid.copy()));
            copy.results.putAll(results);
            copy.active.addAll(active);
            copy.steps.addAll(steps);
            copy.missing.addAll(missing);
            copy.nextGrid = nextGrid;
            copy.completed = completed;
            copy.bindings = bindings;
            copy.cookingBatches = cookingBatches;
            if (heat != null) copy.heat = new java.util.EnumMap<>(heat);
            copy.rejected = rejected;
            return copy;
        }
        Object identity(Need current) {
            var gridKeys = new java.util.TreeMap<Integer, Object>();
            grids.forEach((id, grid) -> gridKeys.put(id, grid.identity()));
            return List.of(current, List.copyOf(tasks), ledger.identity(), gridKeys, Set.copyOf(active), heatIdentity(),
                    completed, bindings, steps.stream().map(s -> List.of(s.recipe().id(), s.path())).toList(), Map.copyOf(results));
        }
        Object heatIdentity() { return heat == null ? List.of() : List.of(Map.copyOf(heat), cookingBatches); }
    }
}
