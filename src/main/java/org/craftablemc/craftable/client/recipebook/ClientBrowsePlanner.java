package org.craftablemc.craftable.client.recipebook;

import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.ClientRequestSequence;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.execution.MainInventoryInsertion;
import org.craftablemc.craftable.network.CraftingDetailPayloads;
import org.craftablemc.craftable.network.CraftingWire;
import org.craftablemc.craftable.planner.*;
import org.craftablemc.craftable.recipe.PlanningInput;

/** 中文：客户端线程上的唯一只读规划调度器，复用值求解器而不读取或修改服务端容器。
 * 静态配方知识可跨菜单复用；动态证据只属于一个不可变资源身份，其授权租约可独立过期。
 * 前后台续算共用每 tick 时间片，预览、MAX 和客户端失败结论均不能授权执行。
 * English: The sole client-thread read-only planning scheduler, reusing the value solver without reading or mutating server containers.
 * Static recipe knowledge survives menus; dynamic evidence belongs to one immutable resource identity with a separately expiring lease.
 * Foreground/background continuations share a per-tick slice; previews, MAX and client failure claims grant no execution authority. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class ClientBrowsePlanner {
    private static final PlanningInput.Catalog CATALOG = new PlanningInput.Catalog();
    private static Object connection, recipeCollection, generation = new Object(), menu;
    private static long recipeVersion = -1, revision, sentAt = Long.MIN_VALUE;
    private static int menuId;
    private static final Turns TURNS = new Turns();
    private static CraftingDetailPayloads.BrowseLease grant;
    private static CraftingWire.SnapshotReceiver receiver;
    private static CraftingWire.SnapshotDecoder decoder;
    private static CraftingWire.SnapshotHeader receiving;
    private static boolean resyncRequested;
    private static BrowsingSnapshot snapshot;
    private static PlanningInput input;
    private static CraftSearch.Reachability closure;
    private static List<ResourceLocation> foreground = List.of(), hidden = List.of(), hovered = List.of();
    private static Task front, background;
    private static ResourceLocation lastCrafted;
    private static boolean drops;
    private static CraftRequest.PartialPolicy policy;
    private static DirectPreparationPolicy.Mode personalMode;
    private static DirectPreparationPolicy boundPreparation;
    private static long searches, slices;
    private static DetailWork detail;
    private static MaximumProjection maximumProjection;
    private static long maximumProjections;
    private static CraftRequest retainedRequest;
    private static SearchResult retainedResult;
    // 中文：同一不可变 scope 内仅保留一个显示用材料上界；较大未知仍是未知，不构成完整失败证明。
    // One presentation-only material ceiling within the same immutable scope.
    // Larger unknown verdicts remain unknown; they are not full-failure proof.
    private static CraftRequest ceilingRequest;
    private static java.util.OptionalInt materialCeiling = java.util.OptionalInt.empty();
    private static boolean ceilingAttempted;
    private static long scopeVersion;
    private static long lastTickNanos, foregroundSlices, hiddenSlices;
    private ClientBrowsePlanner() {}

    /** 中文：复制当前 UI 需求，只调整优先级；移走鼠标或翻页不能为同一身份补充搜索预算。
     * English: Copy current UI demand and adjust priority only; hover/page churn cannot replenish the same identity's search allowance. */
    public static void scope(List<ResourceLocation> visible, List<ResourceLocation> candidates, List<ResourceLocation> hover) {
        foreground = List.copyOf(visible); hidden = List.copyOf(candidates); hovered = List.copyOf(hover);
        // 中文：范围变化可取消无关未完成工作，但玩家稍后返回不能因此获得新预算。
        // A scope change may cancel irrelevant unfinished work, but cannot
        // manufacture a fresh budget for it when the player returns later.
        if (background != null && !hidden.contains(background.id) && !foreground.contains(background.id)) {
            abandon(background); background = null;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        long sliceStart = System.nanoTime();
        try { advanceTick(sliceStart); }
        finally { lastTickNanos = System.nanoTime() - sliceStart; }
    }

    /** 中文：先核对连接/菜单/配方/规则身份，再解码或续算；所有工作共享调用者的时间片起点。
     * English: Check connection/menu/recipe/rule identities before decoding or searching; all work shares the caller's slice start. */
    private static void advanceTick(long sliceStart) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.level == null || mc.player == null) {
            if (connection != null) disconnect();
            return;
        }
        if (connection != mc.getConnection()) { disconnect(); connection = mc.getConnection(); }
        var collection = mc.level.getRecipeManager().getRecipes();
        if (recipeCollection != collection) { recipeCollection = collection; recipesChanged(); }
        boolean open = RecipeBookProjection.modeAllowed() && CraftableClientConfig.recipeBookEnhancementsEnabled()
                && (RecipeBookProjection.component(mc.screen) != null || org.craftablemc.craftable.client.CraftingPlanOverlay.active());
        if (!open) { closeView(); return; }
        if (menu != mc.player.containerMenu) {
            closeView(); menu = mc.player.containerMenu; menuId = mc.player.containerMenu.containerId;
            revision = ClientRequestSequence.next();
            ClientRecipeStatusStore.beginLocal();
        }
        long now = mc.level.getGameTime();
        if (sentAt == Long.MIN_VALUE || now - sentAt >= 8 || now < sentAt) {
            PacketDistributor.sendToServer(new CraftingDetailPayloads.BrowseRequest(menuId, revision, true,
                    resyncRequested ? receiving.session() : snapshot == null ? CraftingDetailPayloads.NO_TOKEN : snapshot.session(),
                    resyncRequested || snapshot == null ? -1 : snapshot.resources()));
            sentAt = now;
        }
        if (receiver != null && receiver.expired(System.nanoTime())) {
            // 中文：只恢复同一份字节，不重开搜索或接收总期限；终态接收器保留小型身份，防止迟到同内容头复活它。
            // Re-sync only the same bytes, never search work or the total receive deadline. Retain terminal identity metadata against late same-content headers.
            if (receiver.canRetry(System.nanoTime())) {
                if (!resyncRequested) { resyncRequested = true; sentAt = Long.MIN_VALUE; }
            } else { receiver.close(); resyncRequested = false; }
        }
        if (decoder != null) {
            try {
                if (!decoder.advance(Math.max(1, 2_000_000L - (System.nanoTime() - sliceStart)))) return;
                var decoded = decoder.result();
                // 中文：发布动态值 scope 前，同时预留有界续算与完整最小结论表的空间。
                // Reserve both bounded continuations and the entire minimal
                // verdict table before publishing this dynamic value scope.
                long estimate = 2L * receiving.bytes() + 256L * decoded.inputs().size()
                        + 128L * decoded.unlocked().size() + 36L * 1024 + 64L * 1024
                        + 2L * 4 * 1024 * 1024 + 16_384L * 128;
                if (estimate > 16L * 1024 * 1024) throw new IllegalStateException("Local browsing memory bound");
                if (grant != null && receiving != null && decoded.session().equals(receiving.session())
                        && decoded.recipes() == receiving.recipes() && decoded.resources() == receiving.resources()
                        && decoded.session().equals(grant.session()) && decoded.resources() == grant.resources()
                        && decoded.recipes() == grant.recipes() && matchesRules(decoded, grant)) {
                    snapshot = decoded; input = null; resetCalculations();
                }
                decoder.close(); decoder = null;
            } catch (RuntimeException failure) {
                decoder.close(); decoder = null;
                Craftable.LOGGER.warn("Rejected browsing snapshot ({})", failure.getClass().getSimpleName());
            }
            return;
        }
        boolean fresh = grant != null && snapshot != null && grant.session().equals(snapshot.session())
                && grant.resources() == snapshot.resources() && matchesRules(snapshot, grant) && now <= grant.expires();
        if (grant == null) { ClientRecipeStatusStore.authorizeLocal(false); return; }
        CATALOG.observe(connection, generation, collection, mc.level.registryAccess());
        if (CATALOG.state() == PlanningInput.Catalog.State.BUILDING) {
            ClientRecipeStatusStore.authorizeLocal(false);
            long remaining = 2_000_000L - (System.nanoTime() - sliceStart);
            if (remaining > 0) CATALOG.advance(remaining);
            return; // 中文：目录和搜索不能在同 tick 各获 2ms。 English: Catalog and search never each receive a 2 ms slice in one tick.
        }
        if (CATALOG.state() != PlanningInput.Catalog.State.READY || snapshot == null || grant == null) return;
        var currentPersonalMode = CraftableClientConfig.partialMode();
        var effectivePolicy = snapshot.directPreparation().executionPolicy().restrict(CraftableClientConfig.partialPolicy());
        var effectivePreparation = snapshot.directPreparation().restrict(effectivePolicy);
        boolean personalChanged = personalMode != currentPersonalMode;
        boolean rebind = input == null || !effectivePreparation.equals(boundPreparation);
        if (rebind) {
            input = CATALOG.bind(snapshot.workbench(), snapshot.limitedCrafting(), snapshot.unlocked(), effectivePreparation);
            boundPreparation = effectivePreparation;
        }
        boolean agrees = input.fingerprint().equals(grant.fingerprint());
        if (!agrees) { ClientRecipeStatusStore.authorizeLocal(false); return; }
        boolean effectiveDrops = snapshot.allowDrops() && CraftableClientConfig.allowSurplusDrops();
        if (closure == null || rebind || personalChanged || drops != effectiveDrops || policy != effectivePolicy) {
            // 中文：个人模式复用服务端唯一白名单；只退役动态判断而保留连接静态目录，切为 DISABLED 后旧橙色不能残留。
            // Personal modes reuse the server's one whitelist. Retire the
            // dynamic judgement, not the connection's static recipe catalog;
            // old orange labels must not survive a new DISABLED preference.
            resetCalculations(!personalChanged); drops = effectiveDrops; policy = effectivePolicy;
            personalMode = currentPersonalMode;
            ClientRecipeStatusStore.defaults(drops, policy);
            closure = CraftSearch.Reachability.resumable();
        }
        // 中文：重置值 scope 会清空结果表，故授权必须在其后，避免新绑定有效快照闪烁一 tick。
        // Resetting a value scope clears its store. Authorize *after* that
        // reset, so a freshly bound valid snapshot does not blink for one tick.
        ClientRecipeStatusStore.authorizeLocal(fresh);
        if (!org.craftablemc.craftable.client.CraftingPlanOverlay.active()) cancelDetails();
        // 中文：低成本目标完成后可继续派发，但共用截止时间、3:1 公平性与累计预算；次数上限也约束快机器的缓存投影/队列扫描。
        // A completed cheap target need not idle until the next game tick.
        // Every dispatch keeps the same deadline and the same 3:1 fairness;
        // continuations keep their cumulative budgets. The count bound also
        // caps cached projections/queue scans on unusually fast machines.
        for (int dispatched = 0; dispatched < 64 && System.nanoTime() - sliceStart < 2_000_000L; dispatched++)
            if (!dispatch(sliceStart)) break;
    }

    /** 中文：按有界 3:1 公平性续算现有任务，缓存投影也占前台回合；不为每个任务重开整段 tick 预算。
     * English: Advance existing tasks with bounded 3:1 fairness, charging cached projections a foreground turn rather than a fresh tick budget. */
    private static boolean dispatch(long sliceStart) {
        if (front == null) {
            // 中文：先复查鼠标目标再处理可见页其余目标，但不能跳过已有隐藏任务回合。
            // Revalidate the target under the pointer before the rest of the
            // visible page, without bypassing the existing hidden-task turn.
            if (lastCrafted != null && detail == null) {
                if ((background == null || !background.id.equals(lastCrafted)) && needsSearch(lastCrafted))
                    front = task(lastCrafted, false);
                lastCrafted = null;
            }
            for (var id : hovered) if (front == null && detail == null && ClientRecipeStatusStore.needsDiagnostic(id)
                    && (background == null || !background.id.equals(id))) { front = task(id, true); break; }
            if (front == null && detail == null) for (var id : foreground) {
                if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
                if ((background == null || !background.id.equals(id)) && needsSearch(id)) { front = task(id, false); break; }
            }
        }
        if (background == null) for (var id : hidden) {
            if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
            if ((front == null || !front.id.equals(id)) && (detail == null || !detail.request.recipe().equals(id))
                    && needsSearch(id)) { background = task(id, false); break; }
        }
        // 中文：移鼠不丢正在运行的前台任务，每第四个合格分片仍给隐藏队头；等待续租的意图不可阻塞同值后台计算，而显示/执行仍需新租约。
        // Keep a running foreground task when the mouse moves. Every fourth
        // eligible slice belongs to the hidden head, even under hover churn.
        // An intent waiting for renewed authority is not runnable foreground
        // work. It must not block same-value hidden mathematics indefinitely;
        // presentation/execution still require the fresh lease.
        boolean detailReady = detail != null && ready();
        boolean hiddenTurn = TURNS.hidden(front != null || detailReady, background != null);
        if (!hiddenTurn && front == null && detailReady) {
            if (System.nanoTime() - sliceStart >= 2_000_000L) return false;
            var previous = detail;
            long projected = maximumProjections;
            front = detailTask();
            if (front == null) {
                // 中文：缓存投影也占前台分片，不能抢隐藏队头预留的第四回合。
                // Cached projection also uses a foreground slice, never the
                // hidden head's reserved fourth turn.
                if (detail == previous && maximumProjections == projected) return false; // 中文：等待不占回合。 English: Waiting consumes no turn.
                TURNS.served(false); slices++; foregroundSlices++; return true;
            }
        }
        var selected = hiddenTurn ? background : front;
        if (selected == null) return false;
        long remaining = 2_000_000L - (System.nanoTime() - sliceStart);
        if (remaining <= 0) return false;
        TURNS.served(hiddenTurn); slices++;
        if (hiddenTurn) hiddenSlices++; else foregroundSlices++;
        java.util.Optional<SearchResult> result;
        do {
            result = selected.search.advance(remaining);
            remaining = 2_000_000L - (System.nanoTime() - sliceStart);
        } while (result.isEmpty() && remaining > 0);
        if (result.isPresent()) {
            if (hiddenTurn) background = null; else front = null;
            if (selected.owner != null) finishDetail(selected, result.get());
            else ClientRecipeStatusStore.completeLocal(selected.request, result.get(), selected.search.fullEvidence(), selected.diagnostic);
        }
        return result.isPresent();
    }

    private static boolean needsSearch(ResourceLocation id) {
        if (ClientRecipeStatusStore.computed(id) || !ClientRecipeStatusStore.canRecord(id)) return false;
        // 中文：每次分配前都检查共享必要条件，不能只检早先 64 个 ID；快速派发不得绕过闭包而对数百排除根做数量搜索。
        // Test the shared necessary condition before *every* allocation, not
        // only an earlier batch of 64 IDs. Faster dispatch must not bypass the
        // closure and turn hundreds of excluded roots into quantity searches.
        var request = new CraftRequest(id, 1, false, drops, policy, Map.of());
        var evidence = closure.excludes(input, mathematical(request, false));
        if (evidence == null) return true;
        ClientRecipeStatusStore.completeLocal(id,
                SearchResult.blocked(org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS), evidence, false);
        return false;
    }

    private static Task task(ResourceLocation id, boolean diagnostic) {
        var request = new CraftRequest(id, 1, diagnostic, drops, policy, Map.of());
        return task(request, diagnostic, null);
    }

    private static Task task(CraftRequest request, boolean diagnostic, DetailWork owner) {
        searches++;
        // 中文：BrowsingSnapshot 每资源 scope 复制一次值；不读取 Level/容器，也不另写客户端分配算法。
        // Values are copied once per resource scope by BrowsingSnapshot. No
        // Level/container reads and no alternate client allocation algorithm.
        var inventory = snapshot.inventory();
        var playerRefs = snapshot.playerReferences();
        var allRefs = snapshot.references();
        var search = new CraftSearch(input, mathematical(request, diagnostic), snapshot.sources(), SearchBudget.resumable(8_000_000L),
                snapshot.maxPlanSteps(),
                plan -> MainInventoryInsertion.simulate(inventory, snapshot.inventoryMaximum(), plan,
                        playerRefs, allRefs, request.allowDrops()).failure()).withReachability(closure);
        requestCeiling(search, owner);
        var evidence = ClientRecipeStatusStore.evidence(request);
        if (diagnostic) search.withFullEvidence(evidence)
                .withDiagnosticBudget(SearchBudget.resumable(8_000_000L));
        return new Task(request.recipe(), diagnostic, search, owner, request);
    }

    /** 中文：NEVER 仍允许只读材料说明，两阶段使用相同数学策略才能复用完整失败证据。
     * 原始意图继续约束显示、路线证据及服务端操作，数学模型不是放宽个人权限的入口。
     * English: NEVER still permits read-only preparation explanations; identical mathematical policies let both phases reuse full-failure evidence.
     * The original intent still controls display, witnesses and server actions; the mathematical model cannot loosen personal permissions. */
    private static CraftRequest mathematical(CraftRequest request, boolean diagnostic) {
        return new CraftRequest(request.recipe(), request.batches(), diagnostic, request.allowDrops(),
                CraftRequest.PartialPolicy.EXPLICIT_SAFE, request.selections());
    }

    public static long scopeVersion() { return scopeVersion; }

    /** 中文：仅供显示的完整 OR 身份，不新建查询或目录。
     * English: Presentation-only full OR identity; no new query or catalog build. */
    public static Map<String, Object> alternativeGroups(PlanView view) {
        return ready() ? view.alternativeGroups(input) : Map.of();
    }

    /** 中文：只导出已发布审阅或匹配的单份完整计划；缺失/部分计划走显式服务端路径。
     * 不为导出重新搜索，也不建立第二份计划缓存；证据仍是不可信的服务端复验输入。
     * English: Export only a published review or matching single retained complete plan; missing/partial plans use the explicit server path.
     * Export neither re-searches nor adds another plan cache, and the witness remains untrusted input for server validation. */
    public static org.craftablemc.craftable.planner.CraftPlan.Witness witness(CraftRequest request) {
        if (!ready() || request.partial()) return null;
        var displayed = org.craftablemc.craftable.client.CraftingPlanOverlay.reviewWitness(effective(request), scopeVersion);
        if (displayed != null) return displayed;
        if (retainedRequest == null || retainedResult == null
                || !effective(request).withPartial(false).equals(retainedRequest.withPartial(false))) return null;
        return boundedWitness(request, retainedResult);
    }

    /** 中文：当前实际显示结果的有界不可信重放描述，不是另一份保留计划或执行授权。
     * English: A bounded, untrusted replay description of the actual displayed result,
     * not another retained plan or an execution authorization. */
    private static CraftPlan.Witness boundedWitness(CraftRequest request, SearchResult result) {
        if (!ready() || request.partial() || result.code() != org.craftablemc.craftable.api.CraftingResultCode.CREATED
                || !result.completeSearch()) return null;
        try {
            var witness = result.plan().filter(plan -> !plan.partial())
                    .map(plan -> org.craftablemc.craftable.planner.CraftPlan.Witness.from(plan, snapshot)).orElse(null);
            return witness != null && CraftingDetailPayloads.fitsWitness(request, witness, Minecraft.getInstance().level.registryAccess())
                    ? witness : null;
        } catch (IllegalArgumentException unsupported) { return null; }
    }
    /** 中文：当前租约、资源、规则及目录身份必须一致才可供 UI 消费；返回 true 仍不是服务端执行授权。
     * English: UI consumption requires matching lease/resource/rule/catalog identities; true still grants no server execution authority. */
    public static boolean ready() {
        var mc = Minecraft.getInstance();
        return input != null && closure != null && snapshot != null && grant != null && mc.level != null
                && personalMode == CraftableClientConfig.partialMode()
                && policy == snapshot.directPreparation().executionPolicy().restrict(CraftableClientConfig.partialPolicy())
                && snapshot.session().equals(grant.session()) && snapshot.resources() == grant.resources()
                && matchesRules(snapshot, grant)
                && mc.level.getGameTime() <= grant.expires() && input.fingerprint().equals(grant.fingerprint());
    }

    /** 中文：从原版按钮组构建有界根卡片，不将其当作递归 OR 需求。
     * 候选仍经 preview(candidateIntent) 判断，执行仍由服务端当前目录校验选中根配方。
     * English: Build bounded root cards from the vanilla button group, not recursive OR demands.
     * Verdicts still use preview(candidateIntent), and execution validates the selected root against the current server catalog. */
    public static PlanView.Choices outputChoices(PlanView view, CraftRequest request, List<ResourceLocation> variants) {
        if (!ready() || view.nodes().stream().noneMatch(n -> n.path().equals("0")))
            return new PlanView.Choices(List.of(), false);
        var sameOutput = view.choices(input, request, "0");
        var ids = new java.util.LinkedHashSet<ResourceLocation>();
        ids.add(request.recipe()); // 中文：卡片截断也须保留所选变体。 English: Selected variant must survive the card cap.
        variants.stream().limit(17).forEach(ids::add);
        sameOutput.candidates().forEach(c -> ids.add(c.recipe()));
        var entries = ids.stream().map(input::find).filter(java.util.Objects::nonNull).toList();
        return new PlanView.Choices(entries.stream().limit(SearchBudget.MAX_CANDIDATES)
                .map(e -> new PlanView.Candidate(e.id(), e.output(), e.gridSize(), input.unavailable(e))).toList(),
                entries.size() > SearchBudget.MAX_CANDIDATES || sameOutput.truncated());
    }

    /** 中文：只登记最新一份 UI 意图，复用现有续算；不另建调度器，也不发送服务端请求。
     * English: Register only the latest UI intent, reusing existing continuations without another scheduler or a server request. */
    public static void preview(long sequence, CraftRequest request, String path) {
        maximumProjection = null; // 中文：旧意图不能钉住尚未投影的 MAX 结果。 English: A replaced intent cannot pin an unprojected MAX result.
        detail = new DetailWork(sequence, effective(request), path, false);
        if (front != null && front.request.withPartial(false).equals(detail.request.withPartial(false)))
            front = new Task(front.id, front.diagnostic, front.search, detail, front.request);
    }
    /** 中文：登记完整合成数量证明需求；材料上界只在同一意图/资源身份复用，不能当作容量单调性证明。
     * English: Register full-crafting quantity proof demand; a material ceiling is reusable only for the same intent/resource identity, not as capacity monotonicity. */
    public static void maximum(long sequence, CraftRequest request) {
        var effective = effective(request).withBatches(1).withPartial(false);
        if (!effective.equals(ceilingRequest)) {
            ceilingRequest = effective; materialCeiling = java.util.OptionalInt.empty(); ceilingAttempted = false;
        }
        maximumProjection = null;
        detail = new DetailWork(sequence, effective, "", true);
    }

    private static void requestCeiling(CraftSearch search, DetailWork work) {
        if (work != null && work.maximum && work.request.equals(ceilingRequest) && !ceilingAttempted && materialCeiling.isEmpty()) {
            search.withMaterialUpperBound();
        }
    }

    private static int maximumSearchCap(DetailWork work) {
        return work.request.equals(ceilingRequest) && materialCeiling.isPresent()
                ? Math.min(snapshot.maxBatches(), materialCeiling.getAsInt()) : snapshot.maxBatches();
    }
    private static CraftRequest effective(CraftRequest request) {
        return new CraftRequest(request.recipe(), request.batches(), request.partial(),
                request.allowDrops() && drops, request.policy().restrict(policy), request.selections());
    }

    public static void cancelDetails() {
        // 中文：取消只结束本地需求；已消耗的同身份搜索被记为有界未知，不能重开详情刷预算。
        // English: Cancellation ends local demand; spent same-identity work becomes bounded unknown, preventing reopen-based budget renewal.
        detail = null;
        maximumProjection = null;
        if (front != null && front.owner != null) {
            // 中文：取消对该身份是终态，滑块/鼠标返回不能再获一份新的 8ms 预算。
            // Cancellation is terminal for this identity, not a fresh 8 ms
            // allowance when a slider or mouse returns to the same request.
            ClientRecipeStatusStore.completeLocal(front.request,
                    SearchResult.blocked(org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                    front.search.fullEvidence(), front.diagnostic);
            front.search.cancel(); front = null;
        }
    }

    private static Task detailTask() {
        var work = detail;
        if (!ready()) return null;
        if (maximumProjection != null && maximumProjection.owner == work) {
            // 中文：大型 MAX 投影/编码在之后前台回合开始，不能作为求解分片用尽后的免费尾工。
            // Large MAX projections/encoding start in a later foreground turn,
            // never as uncharged tail work after the solver's spent slice.
            if (Minecraft.getInstance().level.getGameTime() < maximumProjection.notBeforeTick) return null;
            retainMaximum(maximumProjection.request, maximumProjection.result);
            maximumProjections++;
            maximumProjection = null;
            publishMaximum(work);
            return null;
        }
        CraftRequest request = work.request;
        if (work.maximum) {
            if (!maximumView(request, snapshot.maxBatches(), maximumSearchCap(work)).pending()) {
                publishMaximum(work); return null;
            }
            boolean found = false;
            boolean bounded = work.request.equals(ceilingRequest) && materialCeiling.isPresent();
            int cap = maximumSearchCap(work);
            // 中文：材料上界被证明后先试最大数量；其成功可覆盖较小数量，但失败仍需逐个核查更大量。
            // After a certified material ceiling, try the largest quantity
            // first. One positive witness there subsumes all smaller counts;
            // failures still require every larger count to be checked.
            for (int visit = 1; visit <= cap; visit++) {
                int count = bounded ? cap - visit + 1 : visit;
                if (!ClientRecipeStatusStore.computed(request.withBatches(count))) {
                    request = request.withBatches(count); found = true; break;
                }
            }
            if (!found) { publishMaximum(work); return null; }
        } else if (retainedRequest != null && request.withPartial(false).equals(retainedRequest.withPartial(false))
                && retainedResult != null) {
            publishPreview(work, retainedResult); return null;
        }
        if (!ClientRecipeStatusStore.canRecord(request)) {
            if (work.maximum) {
                detail = null;
                org.craftablemc.craftable.client.CraftingPlanOverlay.receiveMaximum(
                        menuId, work.sequence, new org.craftablemc.craftable.execution.CraftingService.Maximum(
                                0, false, snapshot.maxBatches(), true, false));
            } else publishPreview(work, SearchResult.blocked(org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED));
            return null; // 中文：结果表已满是终态，不可无限循环 MAX。 English: A full result store is terminal, not an infinite MAX loop.
        }
        if (ClientRecipeStatusStore.exhausted(request) || request.batches() > snapshot.maxBatches()) {
            var unknown = SearchResult.blocked(org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED);
            if (work.maximum) {
                ClientRecipeStatusStore.completeLocal(request, unknown, null, false); publishMaximum(work);
            }
            else publishPreview(work, unknown);
            return null;
        }
        if (!work.maximum && ClientRecipeStatusStore.diagnosticExhausted(request)) {
            // 中文：不变租约、重开详情或嵌入层意图提升均不能补充已完成诊断的预算。
            // An unchanged lease, a re-opened detail, or the overlay's intent
            // promotion cannot replenish a completed diagnostic allowance.
            publishPreview(work, SearchResult.blocked(ClientRecipeStatusStore.diagnosticReason(request)));
            return null;
        }
        // 中文：浏览完整失败证明仅对相同意图有效，CraftSearch 在进入诊断阶段前再次核对。
        // A browsing full-failure proof is valid for the identical intent only;
        // CraftSearch checks it again before entering its diagnostic phase.
        if (background != null && background.request.withPartial(false).equals(request.withPartial(false))) {
            var promoted = new Task(background.id, background.diagnostic, background.search, work, background.request);
            requestCeiling(promoted.search, work);
            background = null;
            return promoted; // 中文：转移同一续算，不复制隐藏搜索。 English: Transfer the same continuation; never duplicate the hidden search.
        }
        return task(request.withPartial(!work.maximum), !work.maximum, work);
    }

    private static void finishDetail(Task task, SearchResult result) {
        ClientRecipeStatusStore.completeLocal(task.request, result, task.search.fullEvidence(), task.diagnostic);
        if (task.request.withBatches(1).withPartial(false).equals(ceilingRequest)) {
            // 中文：现货完成不建立闭包或尝试该证书；下一数量仍保留其原有独立预算。
            // Existing-stock completion does not build a closure or attempt
            // this certificate. The next quantity keeps its own old allowance.
            ceilingAttempted |= task.search.materialUpperBoundAttempted();
            if (task.search.materialUpperBound().isPresent()) materialCeiling = task.search.materialUpperBound();
        }
        if (task.owner != detail) return; // 中文：已变 UI 意图不能接收旧结果。 English: Changed UI intent cannot receive an old result.
        if (task.owner.maximum) {
            if (result.plan().isPresent() && !result.plan().orElseThrow().partial()) {
                maximumProjection = new MaximumProjection(task.owner, task.request, result,
                        Minecraft.getInstance().level.getGameTime() + 1);
            } else publishMaximum(task.owner);
        } else {
            if (!task.diagnostic && result.plan().isEmpty() && task.search.fullEvidence() != null)
                return; // 中文：下个前台分片把同一证明升级为诊断，不重搜完整阶段。 English: Next foreground slice upgrades the same proof to diagnostic, without full re-search.
            retainedRequest = task.owner.request; retainedResult = result;
            // 中文：投影放在后续前台分片，不能跟在可能已耗完 2ms 的求解分片之后。
            // Project in a later foreground slice, not after a solver slice
            // that may already have spent the entire 2 ms allowance.
        }
    }

    private static void retainMaximum(CraftRequest request, SearchResult result) {
        if (result.plan().isEmpty() || result.plan().orElseThrow().partial()) return;
        var plan = result.plan().orElseThrow();
        var delivery = MainInventoryInsertion.simulate(snapshot.inventory(), snapshot.inventoryMaximum(),
                plan, snapshot.playerReferences(), snapshot.references(), request.allowDrops());
        var view = PlanView.from(input, request, result, delivery.drops(),
                snapshot.sources().stream().map(ResourceLedger.Source::stack).toList());
        var candidate = new org.craftablemc.craftable.execution.CraftingService.Draft(
                CraftingDetailPayloads.NO_TOKEN, view, new PlanView.Choices(List.of(), false));
        // 中文：MAX 可能从不渲染；保留唯一复用计划前须检查完整投影而非小型输入证据。失败保留前槽和完整结论，真正预览仍另编码选择卡。
        // MAX may never be rendered. Check its complete projection before
        // keeping the one reusable plan, not just its smaller input witness.
        // Failure leaves the previous slot and the proved full verdict intact;
        // actual previews still encode their own choice cards below.
        if (view.code() != org.craftablemc.craftable.api.CraftingResultCode.CREATED
                || !view.complete() || CraftingDetailPayloads.boundedLocal(candidate,
                        Minecraft.getInstance().level.registryAccess()) != candidate) return;
        retainedRequest = request; retainedResult = result;
    }

    private static void publishPreview(DetailWork work, SearchResult result) {
        detail = null;
        var delivery = result.plan().map(plan -> MainInventoryInsertion.simulate(snapshot.inventory(),
                snapshot.inventoryMaximum(), plan, snapshot.playerReferences(), snapshot.references(), work.request.allowDrops()));
        var view = PlanView.from(input, work.request, result,
                delivery.map(MainInventoryInsertion.Delivery::drops).orElse(List.of()),
                snapshot.sources().stream().map(ResourceLedger.Source::stack).toList());
        // 中文：本地显示有意不含令牌；唯一授权链仍是新服务端审阅再接一次性确认。
        // Local displays deliberately have no token. The only authority path
        // remains a fresh server review followed by its one-use confirmation.
        var draft = CraftingDetailPayloads.boundedLocal(new org.craftablemc.craftable.execution.CraftingService.Draft(
                CraftingDetailPayloads.NO_TOKEN, view, view.choices(input, work.request, work.path)),
                Minecraft.getInstance().level.registryAccess());
        if (draft.view() != view) {
            retainedRequest = null; retainedResult = null;
            ClientRecipeStatusStore.completeLocal(work.request, SearchResult.blocked(draft.view().code()), null, true);
        }
        org.craftablemc.craftable.client.CraftingPlanOverlay.receiveLocal(
                new CraftingDetailPayloads.PreviewResponse(menuId, work.sequence, draft), work.request,
                draft.view() == view && view.complete() ? boundedWitness(work.request, result) : null, scopeVersion);
    }

    private static void publishMaximum(DetailWork work) {
        var maximum = maximumView(work.request, snapshot.maxBatches(), maximumSearchCap(work));
        if (!maximum.pending()) detail = null;
        org.craftablemc.craftable.client.CraftingPlanOverlay.receiveMaximum(menuId, work.sequence, maximum);
    }

    static org.craftablemc.craftable.execution.CraftingService.Maximum maximumView(CraftRequest intent, int cap) {
        return maximumView(intent, cap, cap);
    }

    static org.craftablemc.craftable.execution.CraftingService.Maximum maximumView(CraftRequest intent, int cap, int materialCap) {
        int lower = 0, highestUnknown = 0, highestPending = 0;
        for (int count = 1; count <= Math.min(cap, materialCap); count++) {
            var request = intent.withBatches(count);
            if (!ClientRecipeStatusStore.computed(request)) highestPending = count;
            else if (ClientRecipeStatusStore.reason(request) == org.craftablemc.craftable.api.CraftingResultCode.CREATED) lower = count;
            else if (ClientRecipeStatusStore.exhausted(request)) highestUnknown = count;
        }
        // 中文：容量并非随数量单调；所有更大数量都必须明确排除，较小未知可由已证明的较大数量覆盖。
        // English: Capacity is non-monotone: every larger count needs definite exclusion, while a proven larger count subsumes lower unknowns.
        boolean limited = highestUnknown > lower;
        boolean pending = highestPending > lower;
        return new org.craftablemc.craftable.execution.CraftingService.Maximum(lower, !pending && !limited, cap, limited, pending);
    }

    /** 中文：客户端线程接收的租约只能更新同菜单/序号的授权；资源或规则变化退役动态计算。
     * 不变资源的续约复用数学证据，不额外获得搜索预算。
     * English: A client-thread lease may update authority only for the same menu/sequence; changed resources or rules retire dynamic calculations.
     * Renewal of unchanged resources reuses mathematical evidence without granting another search allowance. */
    public static void receive(CraftingDetailPayloads.BrowseLease value) {
        if (!accepts(value.menuId(), value.revision())) return;
        if (value.recipes() < recipeVersion) return;
        if (snapshot != null && snapshot.session().equals(value.session()) && value.resources() < snapshot.resources()) return;
        if (recipeVersion != value.recipes()) {
            recipeVersion = value.recipes(); generation = new Object(); CATALOG.clear(); input = null;
            ClientRecipeStatusStore.beginLocal(); // 中文：配方重载不同于库存刷新。 English: A recipe reload is not an inventory refresh.
            resetCalculations();
        }
        grant = value;
        org.craftablemc.craftable.client.menu.AmbientInventoryEvents.receiveRules(value.settings(), value.workbench());
        if (snapshot != null && (!snapshot.session().equals(value.session()) || snapshot.resources() != value.resources())) {
            if (!snapshot.session().equals(value.session())) ClientRecipeStatusStore.beginLocal();
            snapshot = null; input = null; resetCalculations();
        }
        // 中文：即使尚无替换内容，仅确认规则改变也须退役过期计算；静态知识仍可复用。
        // A consent-only rule change must retire stale calculations even if a
        // peer sends no replacement content yet. Static knowledge is reusable.
        if (snapshot != null && !matchesRules(snapshot, value)) {
            snapshot = null; input = null; resetCalculations();
        }
        if (value.header() != null) {
            var next = value.header();
            if (receiver != null && receiving != null && receiving.session().equals(next.session())
                    && receiving.recipes() == next.recipes() && receiving.resources() == next.resources()) {
                try { receiver = receiver.retry(next, System.nanoTime()); }
                catch (IllegalStateException terminal) { resyncRequested = false; return; }
            } else {
                if (receiver != null) receiver.close();
                receiver = new CraftingWire.SnapshotReceiver(next, System.nanoTime());
            }
            if (decoder != null) decoder.close(); decoder = null;
            receiving = next; resyncRequested = false;
        }
    }

    public static void receive(CraftingDetailPayloads.BrowseChunk value) {
        if (!accepts(value.menuId(), value.revision()) || receiver == null) return;
        long now = System.nanoTime();
        if (!receiver.accept(value.session(), value.transfer(), value.index(), value.data(), now) || !receiver.complete()) return;
        try {
            if (decoder != null) decoder.close();
            decoder = new CraftingWire.SnapshotDecoder(receiver.take(now), Minecraft.getInstance().level.registryAccess());
        } catch (RuntimeException failure) {
            Craftable.LOGGER.warn("Rejected browsing snapshot ({})", failure.getClass().getSimpleName());
        } finally { receiver.close(); }
    }

    private static boolean accepts(int id, long version) {
        var mc = Minecraft.getInstance();
        return menu != null && mc.player != null && mc.player.containerMenu == menu && id == menuId && version == revision;
    }

    private static boolean matchesRules(BrowsingSnapshot values, CraftingDetailPayloads.BrowseLease lease) {
        var rules = lease.rules();
        return values.maxPlanSteps() == rules.maxPlanSteps() && values.maxBatches() == rules.maxBatches()
                && values.directPreparation().equals(rules.directPreparation())
                && values.allowDrops() == (rules.surplusDelivery()
                        == org.craftablemc.craftable.config.CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW);
    }

    private static void abandon(Task task) {
        task.search.cancel();
        ClientRecipeStatusStore.completeLocal(task.request,
                SearchResult.blocked(org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED),
                task.search.fullEvidence(), task.diagnostic);
    }

    private static void resetCalculations() {
        resetCalculations(true);
    }

    private static void resetCalculations(boolean retainDisplay) {
        if (front != null) front.search.cancel();
        if (background != null) background.search.cancel();
        front = background = null; closure = null;
        resetDetails();
        if (retainDisplay) ClientRecipeStatusStore.beginRefresh();
        else ClientRecipeStatusStore.beginLocal();
    }

    private static void resetDetails() {
        // 中文：单槽计划、MAX 投影和材料上界同属资源 scope，一起失效，旧 UI 回复凭 scopeVersion 被隔离。
        // English: The single plan slot, MAX projection and material ceiling share a resource scope; scopeVersion isolates old UI replies.
        detail = null; retainedRequest = null; retainedResult = null; scopeVersion++;
        maximumProjection = null;
        ceilingRequest = null; materialCeiling = java.util.OptionalInt.empty(); ceilingAttempted = false;
    }

    public static void invalidate() {
        // 中文：硬重置仅用于显式刷新/生命周期边界；普通合成保留会话并遵守 afterCreate 的租约屏障。
        // Hard reset for explicit refresh/lifecycle boundaries only. Ordinary
        // crafting keeps this session and follows afterCreate's lease barrier.
        closeView();
        ClientRecipeStatusStore.beginLocal();
    }

    public static void afterCreate(ResourceLocation recipe) {
        if (menu == null) return;
        lastCrafted = recipe;
        // 中文：保留浏览序号及页面；有序游戏消息保证提交前租约先于结果，只有后续租约才能重新授权。
        // 未完成旧分片不能脱离新租约发布数据。
        // English: Keep the browse nonce and page; ordered play packets put pre-action leases before the result, so only a later lease renews authority.
        // Pending old chunks cannot publish without that new lease.
        grant = null;
        ClientRecipeStatusStore.authorizeLocal(false);
        resetDetails();
    }

    /** 中文：在原浏览会话中请求重新授权；资源不变只续租约，不重建图或补搜索预算。
     * English: Request renewed authority in the existing browsing session; unchanged resources renew the lease, not graph or search budgets. */
    public static void requestRefresh() {
        if (menu == null) return;
        grant = null;
        sentAt = Long.MIN_VALUE;
        ClientRecipeStatusStore.authorizeLocal(false);
    }

    public static void recipesChanged() {
        generation = new Object(); CATALOG.clear(); input = null;
        invalidate();
    }

    /** 中文：归还浏览会话并释放分片/解码器/动态任务；静态目录留待同连接复用，断线再彻底清除。
     * English: Close the browse session and release chunks/decoder/dynamic tasks; the static catalog survives until disconnection. */
    private static void closeView() {
        if (menu != null && connection == Minecraft.getInstance().getConnection() && connection != null)
            PacketDistributor.sendToServer(new CraftingDetailPayloads.BrowseRequest(menuId, revision, false,
                    CraftingDetailPayloads.NO_TOKEN, -1));
        menu = null; grant = null; snapshot = null; input = null; boundPreparation = null; personalMode = null; sentAt = Long.MIN_VALUE;
        if (receiver != null) receiver.close(); receiver = null; receiving = null; resyncRequested = false;
        if (decoder != null) decoder.close(); decoder = null;
        if (front != null) front.search.cancel(); if (background != null) background.search.cancel();
        front = background = null; closure = null;
        lastCrafted = null;
        resetDetails();
        ClientRecipeStatusStore.authorizeLocal(false);
    }

    public static void disconnect() {
        closeView(); CATALOG.clear(); connection = recipeCollection = null; recipeVersion = -1;
        generation = new Object(); foreground = hidden = hovered = List.of();
        ClientRecipeStatusStore.clear();
    }

    /** 中文：真实客户端验收使用的计数器，不是授权接口。
     * English: Counters used by real-client acceptance, never an authority interface. */
    public static long searches() { return searches; }
    public static long slices() { return slices; }
    public static long lastTickNanos() { return lastTickNanos; }
    public static long foregroundSlices() { return foregroundSlices; }
    public static long hiddenSlices() { return hiddenSlices; }
    public static int catalogBuilds() { return CATALOG.builds(); }
    static final class Turns {
        private int consecutive;
        boolean hidden(boolean front, boolean background) { return background && (!front || consecutive >= 3); }
        void served(boolean hidden) { consecutive = hidden ? 0 : Math.min(3, consecutive + 1); }
    }
    private record Task(ResourceLocation id, boolean diagnostic, CraftSearch search, DetailWork owner, CraftRequest request) {}
    private record MaximumProjection(DetailWork owner, CraftRequest request, SearchResult result, long notBeforeTick) {}
    private record DetailWork(long sequence, CraftRequest request, String path, boolean maximum) {}
}
