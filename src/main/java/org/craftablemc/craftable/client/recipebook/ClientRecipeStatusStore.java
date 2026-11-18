package org.craftablemc.craftable.client.recipebook;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.SearchResult;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：客户端线程拥有的单资源 scope 最小结论表；不持有可执行计划，也不能授予执行权限。
 * 数学证据与短期授权分离，不变资源续约不重启搜索；DISPLAY 只保留视觉连续性，不能作为求解证据。
 * English: Client-thread-owned minimal conclusions for one resource scope; it stores no executable plans and grants no execution authority.
 * Mathematical evidence is separate from short-lived authority, so unchanged-value renewal does not restart searches;
 * DISPLAY preserves visual continuity only and is never solver evidence. */
public final class ClientRecipeStatusStore {
    public enum Lifecycle { UNKNOWN, PENDING, KNOWN }
    private static final HashMap<CraftRequest, StatusEntry> STATUSES = new HashMap<>();
    // 中文：此表只存显示，不存证明/计划且求解器不读取；资源替换可保留，菜单/重载/连接切换不可保留。
    // Presentation only: never contains proofs/plans and is never read by the solver.
    // Retain across resource replacement, but not menus, reloads or connections.
    private static final HashMap<ResourceLocation, DisplayEntry> DISPLAY = new HashMap<>();
    private static boolean drops = true;
    private static CraftRequest.PartialPolicy policy = CraftRequest.PartialPolicy.EXPLICIT_SAFE;
    private static long revision, authorityRevision, presentationRevision;
    private static boolean local, authorized;
    private static long retainedBytes, displayBytes;
    private static final long MAX_RESULT_BYTES = 2L * 1024 * 1024;
    private ClientRecipeStatusStore() {}

    public static CraftingStatus get(ResourceLocation id, boolean vanillaCraftable) {
        if (local && !authorized) return CraftingStatus.BLOCKED;
        var entry = STATUSES.get(key(id));
        return entry == null ? (vanillaCraftable && !local ? CraftingStatus.CRAFTABLE : CraftingStatus.BLOCKED) : entry.status();
    }

    public static Lifecycle lifecycle(ResourceLocation id) {
        if (local && !authorized) return Lifecycle.PENDING;
        if (!STATUSES.containsKey(key(id)) && DISPLAY.containsKey(id)) return Lifecycle.PENDING;
        return STATUSES.containsKey(key(id)) ? Lifecycle.KNOWN : Lifecycle.UNKNOWN;
    }

    public static CraftingStatus display(ResourceLocation id, boolean vanillaCraftable) {
        var entry = DISPLAY.get(id);
        return entry == null ? get(id, vanillaCraftable) : entry.status();
    }
    public static boolean hasDisplay(ResourceLocation id) { return DISPLAY.containsKey(id); }
    public static CraftingResultCode displayReason(ResourceLocation id) {
        var entry = DISPLAY.get(id);
        return entry == null ? reason(id) : entry.code();
    }

    public static CraftingResultCode reason(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry == null ? CraftingResultCode.MISSING_INGREDIENTS : entry.code();
    }

    public static void clear() {
        local = authorized = false;
        STATUSES.clear();
        DISPLAY.clear();
        retainedBytes = displayBytes = 0;
        revision++; authorityRevision++; presentationRevision++;
    }
    public static long revision() { return revision; }
    public static long authorityRevision() { return authorityRevision; }
    public static long presentationRevision() { return presentationRevision; }
    public static void beginLocal() { clear(); local = true; }
    public static void beginRefresh() {
        // 中文：资源变化撤销全部执行证据（仅容量变化也影响其他目标），但可保留旧按钮颜色作为显示。
        // English: Resource changes retire all execution evidence, since capacity can affect unrelated targets, but may retain old button colors.
        STATUSES.clear(); retainedBytes = 0;
        local = true;
        authorizeLocal(false);
    }
    public static void authorizeLocal(boolean value) {
        if (local && authorized != value) { authorized = value; revision++; authorityRevision++; }
    }
    public static void defaults(boolean allowDrops, CraftRequest.PartialPolicy partialPolicy) {
        drops = allowDrops; policy = partialPolicy;
    }
    private static CraftRequest key(ResourceLocation id) { return new CraftRequest(id, 1, false, drops, policy, java.util.Map.of()); }
    public static boolean computed(ResourceLocation id) { return computed(key(id)); }
    public static boolean canRecord(ResourceLocation id) { return canRecord(key(id)); }
    public static boolean computed(CraftRequest request) { return STATUSES.containsKey(request.withPartial(false)); }
    public static boolean canRecord(CraftRequest request) {
        return computed(request) || STATUSES.size() < 16_384
                && retainedBytes + displayBytes + weight(request) + displayWeight(request.recipe()) <= MAX_RESULT_BYTES;
    }
    private static long displayWeight(ResourceLocation id) { return 128L + 2L * id.toString().length(); }
    private static long weight(CraftRequest request) {
        // 中文：扩展身份含约束字符串，不能只计配方 ID；插入前在既有 2MiB 结果预留内计费。
        // Extended identities include constraint strings, not only a recipe ID.
        // Account them before insertion inside the existing 2 MiB result reserve.
        return 256L + 2L * request.recipe().toString().length() + request.selections().entrySet().stream()
                .mapToLong(e -> 128L + 2L * (e.getKey().length() + e.getValue().toString().length())).sum();
    }
    public static boolean exhausted(CraftRequest request) {
        var code = reason(request);
        return code == CraftingResultCode.SEARCH_BUDGET_EXCEEDED || code == CraftingResultCode.UNSUPPORTED_RECIPE;
    }
    public static CraftingResultCode reason(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry == null ? null : entry.code();
    }
    /** 中文：过滤和 MAX 只用完整合成结论；有界材料诊断是独立已完成工作，不覆盖完整结论。
     * English: Filtering and MAX use full-only conclusions; bounded preparation diagnostics are separate completed work, not replacement conclusions. */
    public static CraftingResultCode fullReason(CraftRequest request) { return reason(request); }
    public static CraftingResultCode diagnosticReason(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry == null ? null : entry.diagnosticCode();
    }
    public static boolean diagnosed(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false));
        return entry != null && entry.diagnosed();
    }
    public static boolean diagnosticExhausted(CraftRequest request) {
        var code = diagnosticReason(request);
        return code == CraftingResultCode.SEARCH_BUDGET_EXCEEDED || code == CraftingResultCode.UNSUPPORTED_RECIPE;
    }
    public static boolean needsDiagnostic(ResourceLocation id) {
        var entry = STATUSES.get(key(id));
        return entry != null && !entry.diagnosed() && entry.evidence() != null && entry.status() != CraftingStatus.CRAFTABLE;
    }
    public static CraftSearch.FullEvidence evidence(ResourceLocation id) {
        return evidence(key(id));
    }
    public static CraftSearch.FullEvidence evidence(CraftRequest request) {
        var entry = STATUSES.get(request.withPartial(false)); return entry == null ? null : entry.evidence();
    }
    public static void completeLocal(ResourceLocation id, SearchResult result, CraftSearch.FullEvidence evidence, boolean diagnosed) {
        completeLocal(id, result.status(), result.code(), evidence, diagnosed);
    }
    static void completeLocal(ResourceLocation id, CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, boolean diagnosed) {
        put(key(id), status, code, evidence, diagnosed);
    }
    public static void completeLocal(CraftRequest request, SearchResult result, CraftSearch.FullEvidence evidence, boolean diagnosed) {
        put(request.withPartial(false), result.status(), result.code(), evidence, diagnosed);
    }
    /** 中文：保留完整结论与诊断结论各自的含义，并按受限身份大小计费；未知不是重试凭据。
     * English: Preserve full and diagnostic conclusions separately, charging bounded identity storage; unknown is not permission to retry. */
    private static void put(CraftRequest request, CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, boolean diagnosed) {
        if (!local || !canRecord(request)) return;
        // 中文：全目录结果表不留计划/前沿；有界未知也是已完成工作，不能据此重试。
        // Plans/frontiers are never retained in the catalog-wide result store.
        // A bounded/unknown conclusion is still completed work, not a retry cue.
        var previous = STATUSES.get(request);
        StatusEntry next;
        if (diagnosed) {
            // 中文：诊断进入准备前可能已完成完整阶段；即使后段受限仍保留证明，不能降级已有完整成功。
            // A diagnostic may also have completed its full phase before
            // entering preparation. Preserve the proof even when that later
            // phase is bounded, and never downgrade an existing full success.
            var fullStatus = previous == null ? (status == CraftingStatus.PARTIAL ? CraftingStatus.BLOCKED : status) : previous.status();
            var fullCode = previous == null ? (status == CraftingStatus.PARTIAL ? CraftingResultCode.SEARCH_BUDGET_EXCEEDED : code) : previous.code();
            var fullEvidence = previous == null ? evidence : previous.evidence();
            if (fullStatus != CraftingStatus.CRAFTABLE && fullCode != CraftingResultCode.CREATED) {
                if (code == CraftingResultCode.CREATED) {
                    fullStatus = CraftingStatus.CRAFTABLE; fullCode = code; fullEvidence = null;
                } else if (evidence != null) {
                    fullStatus = CraftingStatus.BLOCKED; fullCode = CraftingResultCode.MISSING_INGREDIENTS; fullEvidence = evidence;
                }
            }
            next = new StatusEntry(fullStatus, fullCode, fullEvidence, status, code, true);
        } else {
            next = new StatusEntry(status, code, evidence, null, null, false);
        }
        if (!STATUSES.containsKey(request)) retainedBytes += weight(request);
        if (!next.equals(STATUSES.put(request, next))) revision++;
        if (request.equals(key(request.recipe()))) {
            if (!DISPLAY.containsKey(request.recipe())) displayBytes += displayWeight(request.recipe());
            // 中文：准备结果用于说明，不证明完整合成；NEVER 可读取详情但不允许部分操作。
            // Preparation is useful presentation, not proof of a complete
            // craft. NEVER permits reading it in details, not a partial action.
            boolean preparation = next.status() != CraftingStatus.CRAFTABLE && next.code() != CraftingResultCode.CREATED
                    && next.diagnosticStatus() == CraftingStatus.PARTIAL && request.policy() != CraftRequest.PartialPolicy.NEVER;
            var display = new DisplayEntry(preparation ? CraftingStatus.PARTIAL : next.status(),
                    preparation ? next.diagnosticCode() : next.code());
            if (!display.equals(DISPLAY.put(request.recipe(), display))) presentationRevision++;
        }
    }
    private record DisplayEntry(CraftingStatus status, CraftingResultCode code) {}
    private record StatusEntry(CraftingStatus status, CraftingResultCode code,
            CraftSearch.FullEvidence evidence, CraftingStatus diagnosticStatus,
            CraftingResultCode diagnosticCode, boolean diagnosed) {}
}
