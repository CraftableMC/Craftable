package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerPlayer;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.environment.EnvironmentSnapshot;
import org.craftablemc.craftable.environment.EnvironmentSnapshotService;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.ResourceLedger;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.planner.SearchResult;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/**
 * 服务端主线程的合成门面：收紧请求、重建当前材料/配方并经唯一事务提交整链。
 * 预览只发布显示值和短期指纹令牌；确认仍须重新发现与复验，不复用可执行计划。
 * Server-thread crafting facade: restrict requests, rebuild current inputs/recipes, then commit one chain.
 * Previews publish display values and short-lived fingerprint tokens, never reusable executable plans.
 */
public final class CraftingService {
    // 仅主线程拥有；防止同步钩子重入同一玩家，不是跨线程锁。
    // Server-thread-owned reentrancy guard for synchronous hooks, not a cross-thread lock.
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final boolean WITNESS_TIMING_ENABLED = Boolean.getBoolean("craftable.witnessTiming");
    // 单调结构计数器不保留计划或逐请求遥测，不能充当授权状态。
    // Monotonic structural counters; no plans or per-request telemetry retained, and no authority is granted.
    static long activeFullSearches, activePartialSearches, witnessValidations;
    private CraftingService() {}

    /**
     * 普通 C 只请求一次完整合成；部分意图必须链接服务端已记录的同目标缺料结果。
     * 序号先消费，结果再记录，因此重放或第一次成功都不能变成部分准备授权。
     * Plain C requests one full craft; partial intent must link the server's preceding missing result.
     * Consume the sequence before recording the outcome: replay or a successful first press cannot arm preparation.
     */
    public static Outcome attempt(ServerPlayer player, net.minecraft.resources.ResourceLocation recipe,
            long sequence, long precedingPress, boolean allowDrops, CraftRequest.PartialPolicy policy,
            LongSupplier remainingNanos) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!CraftingSessions.acceptSequence(player, sequence)) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        boolean partial = CraftingSessions.partialGesture(player, recipe, precedingPress);
        Outcome outcome = create(player, new CraftRequest(recipe, 1, partial, allowDrops, policy, java.util.Map.of()),
                false, null, null, null, remainingNanos);
        CraftingSessions.recordAttempt(player, recipe, sequence, outcome.code(), partial);
        return outcome;
    }

    public static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence, LongSupplier remainingNanos) {
        return preview(player, request, choicePath, witness, sequence, null, remainingNanos);
    }

    // 功能夹具可注入其既有预算；网络/公开入口始终受生产 admission 上限约束。
    // Functional fixtures may inject their existing budget; network/public entries retain the production admission cap.
    static Draft preview(ServerPlayer player, CraftRequest request, String choicePath,
            CraftPlan.Witness witness, long sequence, SearchBudget testBudget, LongSupplier remainingNanos) {
        long timingStart = WITNESS_TIMING_ENABLED ? System.nanoTime() : 0;
        if (!validContext(player)) return new Draft(new UUID(0, 0),
                org.craftablemc.craftable.planner.PlanView.failed(CraftingResultCode.INVALID_CONTEXT),
                new org.craftablemc.craftable.planner.PlanView.Choices(List.of(), false));
        CraftingSessions.discardOffer(player);
        if (witness != null && (!CraftingSessions.acceptSequence(player, sequence)
                || !CraftingSessions.acceptsWitness(player, witness)))
            return failedDraft(CraftingResultCode.ENVIRONMENT_CHANGED);
        try {
            if (admissionExhausted(remainingNanos))
                return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var snapshot = EnvironmentSnapshotService.fresh(player);
            long timingScan = WITNESS_TIMING_ENABLED ? System.nanoTime() : 0;
            var allowance = testBudget == null ? planningBudget(remainingNanos, System::nanoTime) : testBudget;
            if (allowance == null)
                return failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var prepared = witness == null ? prepare(player, request, snapshot, allowance)
                    : prepareWitness(player, request, snapshot, witness, allowance);
            long timingPrepare = WITNESS_TIMING_ENABLED ? System.nanoTime() : 0;
            if (prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return timedDraft(failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                        "after-prepare", timingStart, timingScan, timingPrepare, 0, 0);
            var available = new ArrayList<net.minecraft.world.item.ItemStack>();
            for (var source : sources(snapshot)) {
                var stack = source.stack();
                if (!CraftingRecipes.protectedStack(stack)) available.add(stack);
            }
            var view = org.craftablemc.craftable.planner.PlanView.from(prepared.recipes(), prepared.request(),
                    prepared.result(), prepared.delivery().drops(), available);
            long timingView = WITNESS_TIMING_ENABLED ? System.nanoTime() : 0;
            var choices = view.choices(prepared.recipes(), prepared.request(), choicePath);
            long timingChoices = WITNESS_TIMING_ENABLED ? System.nanoTime() : 0;
            if (prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return timedDraft(failedDraft(CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                        "after-view", timingStart, timingScan, timingPrepare, timingView, timingChoices);
            // 显示不完整不能授权未披露的计划；部分诊断还须显式意图和有效策略。
            // Incomplete display cannot authorize an undisclosed plan; partial diagnostics also need intent and policy.
            UUID token = view.complete() && (!view.primary().isEmpty())
                    && (prepared.result().plan().filter(CraftPlan::partial).isEmpty()
                        || request.partial() && prepared.request().policy() != CraftRequest.PartialPolicy.NEVER)
                    ? CraftingSessions.offer(player, prepared, witness != null) : new UUID(0, 0);
            return timedDraft(new Draft(token, view, choices), "return",
                    timingStart, timingScan, timingPrepare, timingView, timingChoices);
        } catch (IllegalArgumentException rejected) {
            return failedDraft(CraftingResultCode.ENVIRONMENT_CHANGED);
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Plan preview failed for {}", request.recipe(), exception);
            return new Draft(new UUID(0, 0), org.craftablemc.craftable.planner.PlanView.failed(CraftingResultCode.INTERNAL_ERROR),
                    new org.craftablemc.craftable.planner.PlanView.Choices(List.of(), false));
        }
    }

    public static Outcome confirm(ServerPlayer player, UUID token, CraftPlan.Witness witness, long sequence,
            LongSupplier remainingNanos) {
        return confirm(player, token, witness, sequence, null, remainingNanos);
    }

    /**
     * 序号准入通过后先取走令牌，再校验规则/世代/见证；取走后的失败不能恢复令牌。
     * create 随后重新扫描、重建路线并比较完整指纹，不把已审阅成本当作当前库存。
     * After sequence admission, take the token before validating rules/generation/witness; failures after taking cannot restore it.
     * create then rescans, rebuilds and compares the full fingerprint; reviewed costs are not current inventory.
     */
    static Outcome confirm(ServerPlayer player, UUID token, CraftPlan.Witness witness, long sequence,
            SearchBudget testBudget, LongSupplier remainingNanos) {
        if (!validContext(player)) {
            CraftingSessions.discardOffer(player);
            return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        }
        if (witness != null && !CraftingSessions.acceptSequence(player, sequence))
            return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        var confirmation = CraftingSessions.take(player, token);
        if (confirmation == null) return Outcome.failed(CraftingResultCode.CONFIRMATION_EXPIRED);
        if (!confirmation.rules().equals(CraftableServerConfig.craftingRules())
                || !CraftingRecipes.isCurrentGeneration(player.getServer(), confirmation.recipeGeneration()))
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        if (confirmation.witness() != (witness != null)
                || witness != null && !CraftingSessions.acceptsWitness(player, witness))
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        return create(player, confirmation.request(), true, testBudget, confirmation, witness, remainingNanos);
    }

    // 确定性 GameTest 用充足期限验证功能，不证明性能；生产入口仍受原上限约束，性能测试使用真实上限。
    // Deterministic GameTests supply a generous deadline; production callers
    // always use the bounded entry above. Performance tests exercise real caps.
    static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget) {
        return create(player, request, confirmed, budget, null, null, null);
    }

    private static Outcome create(ServerPlayer player, CraftRequest request, boolean confirmed, SearchBudget budget,
            CraftingSessions.Confirmation confirmation, CraftPlan.Witness witness, LongSupplier remainingNanos) {
        if (!validContext(player)) return Outcome.failed(CraftingResultCode.INVALID_CONTEXT);
        if (!ACTIVE.add(player.getUUID())) return Outcome.failed(CraftingResultCode.REQUEST_THROTTLED);
        try {
            if (admissionExhausted(remainingNanos)) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var snapshot = EnvironmentSnapshotService.fresh(player);
            // 新鲜扫描已消耗的时间不可补回；搜索、真实配方验证与容量共用剩余额度。
            // Discovery time is not refunded; search, real recipe validation and capacity share the remaining allowance.
            var allowance = budget == null ? planningBudget(remainingNanos, System::nanoTime) : budget;
            if (allowance == null) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            Prepared prepared = witness == null ? prepare(player, request, snapshot, allowance)
                    : prepareWitness(player, request, snapshot, witness, allowance);
            boolean incompletePartial = !prepared.result().completeSearch()
                    && prepared.result().plan().filter(CraftPlan::partial).isPresent();
            // 完整路线已被充分排除后，只读部分诊断仍可能超时；普通 C 保留已证明的缺料结论，
            // 但不能把未完成的诊断数量当作执行许可，也不能将已证明缺料退化为未知。
            // Read-only preparation may time out after complete exclusion.
            // Plain C still has a proven material failure; do not turn it into
            // uncertainty or let unfinished diagnostic counts authorize work.
            if (confirmation == null && !request.partial() && prepared.fullMissing()
                    && (prepared.result().code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED || incompletePartial))
                return Outcome.failed(CraftingResultCode.MISSING_INGREDIENTS);
            // 只有部分准备的产物放不下时，缺料诊断仍成立；普通 C 不授权执行这份部分准备。
            // Material diagnostics remain valid when only their preparation
            // output will not fit. Plain C does not authorize that preparation.
            if (confirmation == null && !request.partial() && prepared.fullMissing()
                    && (prepared.result().code() == CraftingResultCode.NO_OUTPUT_SPACE
                        || prepared.result().code() == CraftingResultCode.DROP_LIMIT_EXCEEDED))
                return new Outcome(CraftingResultCode.MISSING_INGREDIENTS, null, prepared.result().missing(), List.of());
            // 遗漏其它候选不抹去已保留的完整成功见证；不完整诊断前沿却不能授权部分准备，
            // 即使 EXPLICIT_SAFE 直接路径不经过 offer，也必须遵守这一边界。
            // A kept full witness proves success despite omitted alternatives.
            // An incomplete diagnostic frontier must not authorize preparation,
            // including direct EXPLICIT_SAFE actions that do not use offer().
            if (incompletePartial || prepared.result().code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    || prepared.result().plan().isPresent() && exhausted(allowance, remainingNanos))
                return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            if (confirmation != null && !confirmation.matches(prepared)) {
                return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            }
            var result = prepared.result;
            if (result.plan().isEmpty()) return new Outcome(result.code(), null, result.missing(), List.of());
            CraftPlan plan = result.plan().get();
            if (plan.partial()) {
                if (!request.partial() || prepared.request.policy() == CraftRequest.PartialPolicy.NEVER) {
                    return new Outcome(CraftingResultCode.MISSING_INGREDIENTS, null, plan.missing(), List.of());
                }
                if (!confirmed && (prepared.request.policy() == CraftRequest.PartialPolicy.CONFIRM || !plan.safePartial())) {
                    return new Outcome(CraftingResultCode.CONFIRMATION_REQUIRED, plan, plan.missing(), prepared.delivery.drops());
                }
            }
            if (!validContext(player) || !prepared.rules.equals(CraftableServerConfig.craftingRules())
                    || plan.steps().size() > prepared.rules.maxPlanSteps()
                    || prepared.recipes.generation() != new CraftingRecipes(player, workbench(player, snapshot)).generation()) {
                return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            }
            // 在事务变更前拒绝超额，不在扣料中途按时间取消；admission 仍计入完整事务时长。
            // Reject excess work before mutation, never time-cancel midway; admission still charges the whole transaction.
            if (exhausted(allowance, remainingNanos)) return Outcome.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            var code = CraftingTransaction.execute(player, plan, snapshot, prepared.delivery);
            return new Outcome(code, plan, plan.missing(),
                    code == CraftingResultCode.CREATED || code == CraftingResultCode.PARTIAL_CREATED
                            ? prepared.delivery.drops() : List.of());
        } catch (IllegalArgumentException rejected) {
            return Outcome.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
        } catch (RuntimeException exception) {
            Craftable.LOGGER.error("Crafting chain request failed for {} and {}", player.getUUID(), request.recipe(), exception);
            return Outcome.failed(CraftingResultCode.INTERNAL_ERROR);
        } finally {
            ACTIVE.remove(player.getUUID());
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    /** admission 用尽不可制造新搜索额度。 / Exhausted admission cannot manufacture a new solver allowance. */
    static SearchBudget planningBudget(LongSupplier remainingNanos, LongSupplier clock) {
        long nanos = remainingNanos == null ? 8_000_000L : Math.min(8_000_000L, remainingNanos.getAsLong());
        return nanos <= 0 ? null : new SearchBudget(clock, nanos, SearchBudget.MAX_STATES);
    }

    private static boolean admissionExhausted(LongSupplier remainingNanos) {
        return remainingNanos != null && remainingNanos.getAsLong() <= 0;
    }

    private static boolean exhausted(SearchBudget budget, LongSupplier remainingNanos) {
        return !budget.alive() || admissionExhausted(remainingNanos);
    }

    private static Draft failedDraft(CraftingResultCode code) {
        return new Draft(new UUID(0, 0), org.craftablemc.craftable.planner.PlanView.failed(code),
                new org.craftablemc.craftable.planner.PlanView.Choices(List.of(), false));
    }

    /**
     * 以新鲜扫描将不透明引用绑定到真实来源；见证只提供路线，不能决定配方产出或来源权限。
     * 重建结果与服务端搜索共用审阅/事务链；无效见证不得触发替代搜索。
     * Bind opaque references to real sources through a fresh scan; a witness supplies a route, not outputs or authority.
     * The rebuilt result shares search's review/transaction path; invalid witnesses never trigger alternative search.
     */
    static Prepared prepareWitness(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot world,
            CraftPlan.Witness witness, SearchBudget budget) {
        var browsing = CraftingSessions.refreshBrowsing(player, world);
        if (!CraftingSessions.acceptsWitness(player, witness) || requested.partial())
            throw new IllegalArgumentException("Changed witness authority");
        var rules = CraftableServerConfig.craftingRules();
        var effective = restrictRequest(requested, rules);
        if (effective.batches() > rules.maxBatches()) throw new IllegalArgumentException("Batch limit");
        var recipes = new CraftingRecipes(player, workbench(player, world), rules.directPreparation().restrict(effective.policy()));
        witnessValidations++;
        CraftPlan plan;
        try { plan = CraftWitnessValidator.validate(recipes, effective, witness, browsing.sources(), budget, rules.maxPlanSteps()); }
        catch (IllegalArgumentException rejected) {
            if (budget.alive()) throw rejected;
            return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        }
        var bindings = CraftingSessions.witnessBindings(player, world);
        var extractions = new ArrayList<CraftPlan.Extraction>(plan.extractions().size());
        for (var extraction : plan.extractions()) {
            var source = bindings.get(extraction.endpointId());
            if (source == null) throw new IllegalArgumentException("Changed witness source");
            var expected = source.stack();
            if (!net.minecraft.world.item.ItemStack.matches(expected, extraction.expected()))
                throw new IllegalArgumentException("Changed witness source");
            extractions.add(new CraftPlan.Extraction(source.endpointId(), source.slot(), extraction.count(), expected));
        }
        plan = new CraftPlan(plan.target(), plan.requestedBatches(), plan.completedBatches(), plan.steps(), extractions,
                plan.primary(), plan.surplus(), plan.missing(), plan.safePartial());
        var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, world, effective.allowDrops());
        var result = delivery.failure() == null
                ? new SearchResult(CraftingResultCode.CREATED, java.util.Optional.of(plan), List.of(), true, 0)
                : SearchResult.blocked(delivery.failure());
        if (!budget.alive()) {
            return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        }
        return new Prepared(result, effective, recipes, rules, delivery);
    }

    /** 显式开启的冷路径计时只使用基本类型；不创建观察者、不额外读取 supplier 或预初始化，决策后才输出。
     * Opt-in primitive-only cold-path timing. No observer object, supplier
     * reads or initialization/preflight; output happens after the decision. */
    private static Draft timedDraft(Draft draft, String checkpoint, long start, long scan,
            long prepare, long view, long choices) {
        if (WITNESS_TIMING_ENABLED) Craftable.LOGGER.warn(
                "CRAFT_REVIEW_TIMING checkpoint={} code={} complete={} token={} scanNs={} prepareNs={} viewNs={} choicesNs={} totalNs={}",
                checkpoint, draft.view().code(), draft.view().complete(), !draft.token().equals(new UUID(0, 0)),
                scan - start, prepare - scan, view == 0 ? -1 : view - prepare,
                choices == 0 ? -1 : choices - view, System.nanoTime() - start);
        return draft;
    }

    /**
     * 搜索与见证统一使用世界收紧后的请求；具体配方白名单仍由同一 recipes 策略约束。
     * 同步方法只读已捕获的 rules，不二次读取配置而拼接不同规则版本。
     * Search and witnesses share world-restricted requests; recipes applies the same per-recipe whitelist policy.
     * Read only the captured rules here, rather than combining configuration values from separate reads.
     */
    private static CraftRequest restrictRequest(CraftRequest requested, CraftableServerConfig.CraftingRules rules) {
        return new CraftRequest(requested.recipe(), requested.batches(), requested.partial(),
                requested.allowDrops() && rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW,
                requested.policy().restrict(rules.directPreparation().executionPolicy()), requested.selections());
    }

    static Prepared prepare(ServerPlayer player, CraftRequest requested, EnvironmentSnapshot snapshot,
            SearchBudget budget) {
        var rules = CraftableServerConfig.craftingRules();
        CraftRequest effective = restrictRequest(requested, rules);
        var recipes = new CraftingRecipes(player, workbench(player, snapshot), rules.directPreparation().restrict(effective.policy()));
        if (effective.batches() > rules.maxBatches()) {
            return new Prepared(SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), effective,
                    recipes, rules, MainInventoryInsertion.Delivery.failed(CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
        }
        var sources = sources(snapshot);
        // FULL_ONLY 也可计算只读部分诊断，以报告完整缺料而非首次遇到的一项；
        // 提交前仍另验显式意图，部分预览不隐含授予普通 C 执行许可。
        // Even FULL_ONLY obtains read-only preparation diagnostics. Intent is
        // checked again before commit; a partial preview is never an implicit C
        // authorization. This also reports the whole diamond deficit, not just
        // whichever missing ingredient happened to be visited first.
        var diagnostic = new CraftRequest(effective.recipe(), effective.batches(), true, effective.allowDrops(),
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, effective.selections());
        var mainSlots = MainInventoryInsertion.copyMainInventory(player.getInventory());
        int inventoryMaximum = player.getInventory().getMaxStackSize();
        var playerEndpoints = snapshot.endpoints().stream().filter(e -> e.kind() == org.craftablemc.craftable.environment.EndpointKind.PLAYER)
                .map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var endpointIds = snapshot.endpoints().stream().map(e -> e.id()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        // 服务端否定证明仅在新鲜扫描匹配全部动态输入（含空余容量和策略）后复用；
        // 不接受客户端失败声明，只保留一份证明且最长 40 tick。
        // A server-only negative proof is reusable only after this fresh scan
        // matches ALL dynamic inputs, including empty capacity and policy.
        // No client failure assertion enters this store; one proof, 40 ticks.
        Object identity = List.of(recipes.accessIdentity(), rules, diagnostic.withPartial(false), snapshot.dimension(),
                snapshot.scanSettings(), sources.stream().map(s -> List.of(s.endpointId(), s.slot(),
                        CraftPlan.stackKeys(List.of(s.stack())))).toList(),
                CraftPlan.stackKeys(mainSlots), inventoryMaximum, playerEndpoints, endpointIds);
        var previous = CraftingSessions.failure(player, identity);
        var scope = previous == null ? new CraftSearch.Reachability() : previous.scope();
        var search = new CraftSearch(recipes, diagnostic, sources, budget, rules.maxPlanSteps(),
                plan -> MainInventoryInsertion.simulate(mainSlots, inventoryMaximum, plan, playerEndpoints, endpointIds, effective.allowDrops()).failure())
                .withReachability(scope).withFullEvidence(previous == null ? null : previous.evidence());
        SearchResult result = search.run();
        activeFullSearches += search.fullSearches(); activePartialSearches += search.partialSearches();
        if (previous == null) CraftingSessions.rememberFailure(player, identity, scope, search.fullEvidence());
        if (result.plan().isPresent()) {
            boolean valid = recipes.validate(result.plan().get());
            var code = !budget.alive() ? CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                    : !valid ? CraftingResultCode.UNSUPPORTED_RECIPE : null;
            if (code != null) return new Prepared(SearchResult.blocked(code), effective, recipes, rules,
                    MainInventoryInsertion.Delivery.failed(code), search.fullEvidence() != null);
        }
        var delivery = result.plan().map(plan ->
                MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, effective.allowDrops()))
                .orElseGet(() -> MainInventoryInsertion.Delivery.failed(result.code()));
        return new Prepared(result, effective, recipes, rules, delivery, search.fullEvidence() != null);
    }

    private static List<ResourceLedger.Source> sources(EnvironmentSnapshot snapshot) {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (var endpoint : snapshot.endpoints()) {
            for (int slot = endpoint.firstSlot(); slot < endpoint.firstSlot() + endpoint.slotCount(); slot++) {
                var stack = endpoint.container().getItem(slot);
                if (!stack.isEmpty()) sources.add(new ResourceLedger.Source(endpoint.id(), slot, stack));
            }
        }
        return sources;
    }

    /** 客户端 MAX 展示共用的值类型；不提供服务端搜索入口或执行许可。
     * Shared client MAX display value, not a server search entry or execution authority. */
    public record Maximum(int lowerBound, boolean proven, int cap, boolean limited, boolean pending) {}

    /**
     * 输入层的模式过滤不替代此服务端检查；菜单本身仍须有效。调用者应已在主线程。
     * Client input mode gates do not replace this server check; the menu must also remain valid. Call on the server thread.
     */
    public static boolean validContext(ServerPlayer player) {
        return org.craftablemc.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                && !player.isDeadOrDying()
                && (player.containerMenu == player.inventoryMenu
                    || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu)
                && player.containerMenu.stillValid(player)
                && player.serverLevel().getServer().isSameThread();
    }

    private static boolean workbench(ServerPlayer player, EnvironmentSnapshot snapshot) {
        return snapshot.supports(WorkstationCapability.CRAFTING_3X3)
                || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;
    }

    record Prepared(SearchResult result, CraftRequest request, CraftingRecipes recipes,
            CraftableServerConfig.CraftingRules rules, MainInventoryInsertion.Delivery delivery, boolean fullMissing) {
        Prepared(SearchResult result, CraftRequest request, CraftingRecipes recipes,
                CraftableServerConfig.CraftingRules rules, MainInventoryInsertion.Delivery delivery) {
            this(result, request, recipes, rules, delivery, false);
        }
    }

    public record Draft(UUID token, org.craftablemc.craftable.planner.PlanView view,
            org.craftablemc.craftable.planner.PlanView.Choices choices) {}

    public record Outcome(CraftingResultCode code, CraftPlan plan, List<CraftPlan.Missing> missing,
            List<net.minecraft.world.item.ItemStack> drops) {
        public Outcome {
            missing = List.copyOf(missing);
            drops = CraftPlan.copies(drops);
        }
        @Override public List<net.minecraft.world.item.ItemStack> drops() { return CraftPlan.copies(drops); }
        static Outcome failed(CraftingResultCode code) { return new Outcome(code, null, List.of(), List.of()); }
    }
}
