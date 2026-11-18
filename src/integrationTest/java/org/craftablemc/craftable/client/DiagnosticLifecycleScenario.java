package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.planner.SearchResult;

/** 中文：在实际已授权冻结值上验证生产状态表/详情转换；仅夹具续算使用逻辑预算，不伪造租约、资源、令牌或另建调度器/求解器。
 * English: Production status-store/detail transitions over the active, authorized
 * frozen values. Only fixture continuations receive logical budgets: no fake
 * lease, world resources, execution token, alternate scheduler or solver. */
final class DiagnosticLifecycleScenario {
    private static final ResourceLocation ROD = ResourceLocation.withDefaultNamespace("fishing_rod");
    private static final long SEQUENCE = Long.MAX_VALUE - 42;
    private enum Phase {
        VERIFY_ORIGINAL_POLICY,
        VERIFY_DISABLED_POLICY,
        VERIFY_POLICY_RESTORATION,
        COMPLETE
    }
    private static Phase phase = Phase.VERIFY_ORIGINAL_POLICY;
    private static DirectPreparationPolicy.Mode originalPersonal;
    private static Object originalValues;

    /** 中文：个人策略切换交给真实客户端调度器；不能通过注入私有策略、伪造租约或替代规划器把输入/就绪状态强行对齐。
     * English: Yield to the actual client scheduler for personal-policy transitions.
     * Its input/readiness must never be made consistent by injecting private
     * policy fields, a fabricated lease, or another planning implementation. */
    static boolean verify() throws Exception {
        if (phase == Phase.COMPLETE) return true;
        require(ClientBrowsePlanner.ready(), "Diagnostic lifecycle needs the real authorized snapshot");
        var snapshot = (BrowsingSnapshot) get(ClientBrowsePlanner.class, null, "snapshot");
        if (phase == Phase.VERIFY_ORIGINAL_POLICY) {
            originalValues = snapshot.contentIdentity();
            originalPersonal = CraftableClientConfig.partialMode();
            verifyAuthorized(snapshot, false);
            personalValue().set(DirectPreparationPolicy.Mode.DISABLED);
            if (originalPersonal != DirectPreparationPolicy.Mode.DISABLED)
                require(!ClientBrowsePlanner.ready(), "Personal disable did not retire the original diagnostic input");
            advance(Phase.VERIFY_DISABLED_POLICY); return false;
        }
        require(snapshot.contentIdentity().equals(originalValues), "Personal diagnostic mode changed server resource values");
        if (phase == Phase.VERIFY_DISABLED_POLICY) {
            require(CraftableClientConfig.partialMode() == DirectPreparationPolicy.Mode.DISABLED
                    && get(ClientBrowsePlanner.class, null, "policy") == CraftRequest.PartialPolicy.NEVER,
                    "NEVER fixture did not wait for the production-owned personal disable binding");
            verifyAuthorized(snapshot, true);
            personalValue().set(originalPersonal);
            advance(Phase.VERIFY_POLICY_RESTORATION); return false;
        }
        require(CraftableClientConfig.partialMode() == originalPersonal,
                "Diagnostic lifecycle did not restore the original personal mode through the real scheduler");
        advance(Phase.COMPLETE);
        System.out.println("DIAGNOSTIC_LIFECYCLE PASS fullMissing/diagnosticUnknown=separate promotedContinuation=shared retry=terminal never=readOnly witness=none personalDisableAndRestore=realSchedulerRebind noInjectedPolicy");
        return true;
    }

    private static void advance(Phase next) {
        phase = next;
        System.out.println("DIAGNOSTIC_LIFECYCLE phase=" + phase);
    }

    private static void verifyAuthorized(BrowsingSnapshot snapshot, boolean never) throws Exception {
        Object values = snapshot.contentIdentity();
        require(snapshot.sources().stream().anyMatch(s -> s.stack().is(Items.OAK_LOG))
                && snapshot.sources().stream().noneMatch(s -> s.stack().is(Items.STRING)),
                "Diagnostic lifecycle fixture lost its useful preparation/missing-string boundary");
        var scheduler = save(ClientBrowsePlanner.class, "front", "background", "detail", "retainedRequest",
                "retainedResult", "closure", "policy", "searches");
        var verdicts = save(ClientRecipeStatusStore.class, "STATUSES", "DISPLAY", "drops", "policy", "revision",
                "authorityRevision", "presentationRevision", "local", "authorized", "retainedBytes", "displayBytes");
        try {
            set(ClientBrowsePlanner.class, null, "front", null);
            set(ClientBrowsePlanner.class, null, "background", null);
            resetDetails();
            var closure = new CraftSearch.Reachability();
            set(CraftSearch.Reachability.class, closure, "budget", logical(SearchBudget.MAX_STATES));
            set(ClientBrowsePlanner.class, null, "closure", closure);
            verifyPromotedBoundedDiagnostic(snapshot);
            if (never) verifyNeverReadOnly(snapshot);
            require(snapshot.contentIdentity().equals(values), "Diagnostic fixture changed resource values");
        } finally {
            restore(ClientBrowsePlanner.class, scheduler);
            restore(ClientRecipeStatusStore.class, verdicts);
        }
    }

    private static void verifyPromotedBoundedDiagnostic(BrowsingSnapshot snapshot) throws Exception {
        var policy = (CraftRequest.PartialPolicy) get(ClientBrowsePlanner.class, null, "policy");
        boolean drops = (boolean) get(ClientBrowsePlanner.class, null, "drops");
        var request = new CraftRequest(ROD, 1, false, drops, policy, Map.of());
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.defaults(drops, policy);
        ClientRecipeStatusStore.authorizeLocal(true);
        var hidden = invoke("task", new Class<?>[] { ResourceLocation.class, boolean.class }, ROD, false);
        set(ClientBrowsePlanner.class, null, "background", hidden);
        var full = search(hidden);
        logicalFull(full);
        ClientBrowsePlanner.preview(SEQUENCE, request, "");
        var promoted = invoke("detailTask", new Class<?>[0]);
        require(promoted != null && search(promoted) == full && get(ClientBrowsePlanner.class, null, "background") == null,
                "Detail did not transfer the existing hidden continuation");
        var missing = full.run();
        require(missing.code() == CraftingResultCode.MISSING_INGREDIENTS && full.fullEvidence() != null,
                "Real promoted FULL_ONLY did not prove the missing string: " + missing.code());
        finish(promoted, missing);
        require(get(ClientBrowsePlanner.class, null, "detail") != null
                && get(ClientBrowsePlanner.class, null, "retainedResult") == null,
                "Full failure skipped its diagnostic upgrade");
        var diagnostic = invoke("detailTask", new Class<?>[0]);
        var partial = search(diagnostic);
        partial.withDiagnosticBudget(logical(1));
        var unknown = partial.run();
        require(unknown.code() == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                && partial.fullSearches() == 0 && partial.partialSearches() == 1,
                "Diagnostic upgrade repeated full work or escaped its own allowance");
        finish(diagnostic, unknown);
        require(ClientRecipeStatusStore.fullReason(request) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.diagnosticReason(request) == CraftingResultCode.SEARCH_BUDGET_EXCEEDED
                && !ClientRecipeStatusStore.exhausted(request) && !ClientRecipeStatusStore.needsDiagnostic(ROD),
                "Diagnostic unknown contaminated the full verdict or re-queued hover work");
        require(ClientRecipeStatusStore.displayReason(ROD) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.get(ROD, false) == CraftingStatus.BLOCKED,
                "Recipe-book presentation lost its proven missing-material conclusion");
        long searches = ClientBrowsePlanner.searches();
        require(invoke("detailTask", new Class<?>[0]) == null
                && ((SearchResult) get(ClientBrowsePlanner.class, null, "retainedResult")).code()
                        == CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                "Completed unknown was not retained for detail projection");
        require(get(ClientBrowsePlanner.class, null, "detail") == null, "Unknown detail did not finish publishing");
        // 中文：驱逐唯一详情结果后重访同一意图，检查最小诊断终态仍能阻止新预算。
        // Evict the one permitted detail result, then revisit the exact intent.
        // Its minimal diagnostic terminal must still prevent a new allowance.
        resetDetails();
        ClientRecipeStatusStore.authorizeLocal(false); ClientRecipeStatusStore.authorizeLocal(true);
        ClientBrowsePlanner.preview(SEQUENCE + 1, request, "0");
        require(invoke("detailTask", new Class<?>[0]) == null && ClientBrowsePlanner.searches() == searches
                && get(ClientBrowsePlanner.class, null, "detail") == null,
                "Reopening/renewing an exhausted diagnostic manufactured a fresh search");
        require(snapshot.sources().stream().noneMatch(s -> s.stack().is(Items.STRING)), "Detail mutated frozen resources");
    }

    private static void verifyNeverReadOnly(BrowsingSnapshot snapshot) throws Exception {
        resetDetails();
        require(get(ClientBrowsePlanner.class, null, "policy") == CraftRequest.PartialPolicy.NEVER
                && ClientBrowsePlanner.ready(), "NEVER diagnostic must use a real ready personal-policy binding");
        boolean drops = (boolean) get(ClientBrowsePlanner.class, null, "drops");
        var request = new CraftRequest(ROD, 1, false, drops, CraftRequest.PartialPolicy.NEVER, Map.of());
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.defaults(drops, request.policy());
        ClientRecipeStatusStore.authorizeLocal(true);
        // 中文：生产闭包捷径须与后续只读诊断使用相同 SAFE 数学身份，才能安全复用结论。
        // The production closure shortcut must issue exactly the same SAFE
        // mathematical identity as the subsequent read-only diagnostic.
        require(Boolean.FALSE.equals(invoke("needsSearch", new Class<?>[] { ResourceLocation.class }, ROD))
                && ClientRecipeStatusStore.evidence(request) != null,
                "Known closure exclusion did not publish a full proof under NEVER");
        var target = (CraftRequest) get(CraftSearch.FullEvidence.class, ClientRecipeStatusStore.evidence(request), "target");
        require(target.policy() == CraftRequest.PartialPolicy.EXPLICIT_SAFE && !target.partial(),
                "Closure proof used a different mathematical identity from diagnostics");
        ClientBrowsePlanner.preview(SEQUENCE + 2, request, "");
        var diagnostic = invoke("detailTask", new Class<?>[0]);
        var search = search(diagnostic);
        search.withDiagnosticBudget(logical(SearchBudget.MAX_STATES));
        var result = search.run();
        require(result.code() == CraftingResultCode.PARTIAL_CREATED && result.plan().orElseThrow().partial()
                && search.fullSearches() == 0 && search.partialSearches() == 1,
                "NEVER prevented read-only preparation or repeated the full proof: " + result.code());
        var intent = (CraftRequest) get(diagnostic.getClass(), diagnostic, "request");
        require(intent.policy() == CraftRequest.PartialPolicy.NEVER, "Diagnostic computation promoted player execution policy");
        finish(diagnostic, result);
        require(ClientRecipeStatusStore.fullReason(request) == CraftingResultCode.MISSING_INGREDIENTS
                && ClientRecipeStatusStore.diagnosticReason(request) == CraftingResultCode.PARTIAL_CREATED
                && ClientRecipeStatusStore.display(ROD, false) == CraftingStatus.BLOCKED,
                "Read-only preparation enabled a partial recipe-book action under NEVER");
        require(ClientBrowsePlanner.witness(request) == null, "Read-only preparation exported execution authority");
        require(invoke("detailTask", new Class<?>[0]) == null
                && ((SearchResult) get(ClientBrowsePlanner.class, null, "retainedResult")).plan().orElseThrow().partial(),
                "NEVER detail did not retain/project its read-only preparation");
        require(snapshot == get(ClientBrowsePlanner.class, null, "snapshot"), "Diagnostic replaced authorized values");
    }

    private static void resetDetails() throws Exception {
        set(ClientBrowsePlanner.class, null, "detail", null);
        set(ClientBrowsePlanner.class, null, "retainedRequest", null);
        set(ClientBrowsePlanner.class, null, "retainedResult", null);
    }
    @SuppressWarnings("unchecked")
    private static net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<DirectPreparationPolicy.Mode> personalValue() {
        return (net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<DirectPreparationPolicy.Mode>)
                CraftableClientConfig.SPEC.getValues().get(java.util.List.of("crafting", "partialPolicy"));
    }
    static void abort() {
        if (originalPersonal != null) personalValue().set(originalPersonal);
    }
    private static SearchBudget logical(int states) { return SearchBudget.resumable(() -> 0L, 1_000_000_000L, states); }
    private static void logicalFull(CraftSearch search) throws Exception {
        var budget = logical(SearchBudget.MAX_STATES);
        set(CraftSearch.class, search, "fullBudget", budget);
        set(CraftSearch.class, search, "budget", budget);
    }
    private static CraftSearch search(Object task) throws Exception {
        require(task != null, "Expected a production detail task");
        return (CraftSearch) get(task.getClass(), task, "search");
    }
    private static void finish(Object task, SearchResult result) throws Exception {
        invoke("finishDetail", new Class<?>[] { task.getClass(), SearchResult.class }, task, result);
    }
    private static Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = ClientBrowsePlanner.class.getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(null, arguments);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Class<?> type, Object instance, String name) throws Exception { return field(type, name).get(instance); }
    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception { field(type, name).set(instance, value); }
    private static Map<String, Object> save(Class<?> type, String... names) throws Exception {
        var saved = new LinkedHashMap<String, Object>();
        for (String name : names) {
            var value = get(type, null, name);
            saved.put(name, value instanceof Map<?, ?> map ? new HashMap<>(map) : value);
        }
        return saved;
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void restore(Class<?> type, Map<String, Object> values) throws Exception {
        for (var entry : values.entrySet()) {
            var existing = get(type, null, entry.getKey());
            if (existing instanceof Map map) { map.clear(); map.putAll((Map) entry.getValue()); }
            else set(type, null, entry.getKey(), entry.getValue());
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
