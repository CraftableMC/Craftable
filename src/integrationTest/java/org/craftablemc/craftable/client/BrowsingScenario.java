package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Items;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.network.CraftingDetailPayloads;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.RecipeBookStatusHandler;

/** 中文：由 PlanningClientSmoke 调度的浏览场景，不另注册事件/运行器；使用真实租约与冷/热样本检查动态重建和静态目录复用边界。
 * English: One scenario owned by PlanningClientSmoke, not another subscriber or runner; real leases and cold/warm samples check dynamic rebuilding and static-catalog reuse boundaries. */
final class BrowsingScenario {
    private final SnapshotResyncScenario snapshotResync = new SnapshotResyncScenario();
    private int browsePhase, browseStarted;
    private long browseSearches, browseCompleted;
    private long browseStartNanos;
    private int rangeReturns;
    private final java.util.List<Double> filterTimings = new java.util.ArrayList<>();
    private final java.util.List<Double> filterCpu = new java.util.ArrayList<>();
    private int browseBuilds;
    private final java.util.Map<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>, Object> originalPolicy
            = new java.util.IdentityHashMap<>();
    private DirectPreparationPolicy savedPolicy, expectedPolicy;
    private boolean policyChecked, restoringPolicy;
    private volatile boolean policyConfigured, policyAborted;
    private volatile Throwable policyFailure;
    private int policyPhase, policySample, policyStarted;
    private long previousPolicyRevision;
    private Object originalPersonalMode;
    private int personalSample, personalPhase, personalMatrices, matrixCatalogBuilds;
    private long personalPreviousScope;
    private boolean personalChanged;
    private org.craftablemc.craftable.planner.CraftSearch personalSearch;
    private boolean overlayPolicyChecked;
    private int overlayPolicyPhase, overlayCatalogBuilds;
    private long overlayOldScope;

    boolean tick(Minecraft mc, int age, ResourceLocation PICK) throws Exception {
        try {
            if (!snapshotResync.tick(mc, age)) return false;
            if (!policyChecked && !verifyActualLeasePolicies(mc, age)) return false;
            if (!overlayPolicyChecked && !verifyOverlayPersonalInvalidation(mc, PICK)) return false;
            return browse(mc, age, PICK);
        } catch (Throwable failure) {
            // 中文：仅改内存配置；即使超时也归还真实注册值，并保留首次失败，避免清理掩盖原因。
            // Config changes are in-memory only. Preserve the first failure
            // while returning the actual registered values, even on timeout.
            policyAborted = true;
            try { snapshotResync.close(); }
            catch (Throwable restoreFailure) { failure.addSuppressed(restoreFailure); }
            try { restorePolicyValues(); }
            catch (Throwable restoreFailure) { failure.addSuppressed(restoreFailure); }
            try { restorePersonalMode(); }
            catch (Throwable restoreFailure) { failure.addSuppressed(restoreFailure); }
            try {
                var overlay = activeOverlay();
                if (overlay != null) overlay.onClose();
            } catch (Throwable restoreFailure) { failure.addSuppressed(restoreFailure); }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }
    }

    private boolean browse(Minecraft mc, int age, ResourceLocation PICK) {
        var liveBook = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
        var liveSearch = ((RecipeBookComponentAccessor) liveBook).craftable$getSearchBox();
        if (browsePhase == 0) {
            browseSearches = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches();
            browseBuilds = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds();
            browseStarted = age;
            browseStartNanos = System.nanoTime();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
            liveSearch.setValue(""); liveBook.recipesUpdated();
            org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
            browsePhase = 5; return false;
        }
        if (browsePhase == 1) {
            filterCpu.add(org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.lastTickNanos() / 1e6);
            var ids = org.craftablemc.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                    .flatMap(c -> org.craftablemc.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList();
            long completed = ids.stream().filter(org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore::computed).count();
            if (completed != ids.size() && age - browseStarted < 400) return false;
            require(completed == ids.size(), "Local hidden filtering did not finish within 400 ticks: " + completed + "/" + ids.size()
                    + (completed == ids.size() ? "" : browseFailureContext(liveBook)));
            var unknownIds = ids.stream().filter(id -> org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.reason(id)
                    == CraftingResultCode.SEARCH_BUDGET_EXCEEDED).toList();
            long unknown = unknownIds.size();
            var cpu = filterCpu.stream().sorted().toList();
            Craftable.LOGGER.warn("BROWSE_FILTER targets={} ticks={} quantityTasks={} unknown={} catalogBuilds={} cpuP95Ms={} cpuMaxMs={}",
                    ids.size(), age - browseStarted, org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches() - browseSearches,
                    unknown, org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds(),
                    cpu.get((int) Math.ceil(cpu.size() * .95) - 1), cpu.getLast());
            filterCpu.clear();
            filterTimings.add((System.nanoTime() - browseStartNanos) / 1e6);
            require(unknown == 0, "Stopped unknown work is not filter convergence: " + unknownIds);
            if (filterTimings.size() < 5) {
                // 中文：新动态会话必须重新捕获/传输/绑定，但连接内静态目录必须保留。
                // A fresh dynamic session must capture/transfer/bind again,
                // but the connection's static catalog must survive.
                org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.invalidate();
                browseStarted = age; browseStartNanos = System.nanoTime();
                browseSearches = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches();
                browsePhase = 5;
                return false;
            }
            var sorted = filterTimings.stream().sorted().toList();
            Craftable.LOGGER.warn("BROWSE_FILTER_SESSIONS samples=5 ms={} P50={} P95={} max={} catalogBuilds={}",
                    filterTimings, sorted.get(2), sorted.get(4), sorted.get(4),
                    org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds());
            browseCompleted = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches();
            browseStarted = age; browsePhase = 2;
            liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated(); return false;
        }
        if (browsePhase == 5) {
            // 中文：invalidate 立即撤销显示授权；等新快照后再取冷样本，避免旧结论提前完成样本。
            // invalidate() hides authority immediately; wait for the new
            // snapshot so old conclusions cannot finish a cold sample.
            if (!org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.ready()) return false;
            browsePhase = 1; return false;
        }
        if (browsePhase == 2) {
            if (age - browseStarted < 1) return false;
            liveSearch.setValue(""); liveBook.recipesUpdated(); browseStarted = age; browsePhase = 3; return false;
        }
        if (browsePhase == 3) {
            if (age - browseStarted < 1) return false;
            require(org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches() == browseCompleted,
                    "Same-version category/search return restarted searches");
            require(org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds() == browseBuilds,
                    "Category/search changed static catalog generation");
            if (++rangeReturns < 100) {
                liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
                browseStarted = age; browsePhase = 2; return false;
            }
            Craftable.LOGGER.warn("BROWSE_REUSE completeRangeReturns=100 newSearches=0 unchangedLease=0Rebuilds");
            var ids = org.craftablemc.craftable.client.recipebook.RecipeBookProjection.scope(liveBook).stream()
                    .flatMap(c -> org.craftablemc.craftable.client.recipebook.RecipeBookProjection.candidates(c).stream())
                    .map(r -> r.id()).distinct().toList();
            BrowseSchedulingProbe.start(ids); browsePhase = 6; return false;
        }
        if (browsePhase == 6) {
            if (!BrowseSchedulingProbe.complete()
                    || !org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.ready()
                    || !org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.computed(PICK)) return false;
            liveSearch.setValue(Items.DIAMOND_PICKAXE.getDescription().getString()); liveBook.recipesUpdated();
            browsePhase = 4;
        }
        return true;
    }

    /** 中文：允许调度夹具注入过期租约前，先走真实 BrowseRequest→browseTick→租约/分片接收；检查实际组装租约而非仅编解码。
     * English: Exercise the real BrowseRequest -> browseTick -> lease/chunk receive
     * path before the scheduling fixture can inject an expired lease. This
     * deliberately checks the assembled lease, not just the snapshot codec. */
    private boolean verifyActualLeasePolicies(Minecraft mc, int age) throws Exception {
        if (policyFailure != null) throw new AssertionError("Real browse policy configuration failed", policyFailure);
        if (policyPhase == 0) {
            savedPolicy = CraftableServerConfig.directPreparationPolicy();
            originalPersonalMode = personalValue().getRaw();
            for (var value : CraftableServerConfig.directPreparationValues()) {
                Object raw = value.getRaw();
                originalPolicy.put(value, raw instanceof java.util.List<?> ids ? java.util.List.copyOf(ids) : raw);
            }
            policyStarted = age;
            configurePolicy(mc, samplePolicy(0)); policyPhase = 1; return false;
        }
        if (policyPhase == 1) {
            require(age - policyStarted <= 160, "Server did not acknowledge its owned browse policy fixture");
            if (!policyConfigured) return false;
            previousPolicyRevision = (long) plannerField("revision");
            ClientBrowsePlanner.invalidate();
            require(plannerField("grant") == null && plannerField("snapshot") == null,
                    "Explicit browse invalidation retained an old policy lease or snapshot");
            policyStarted = age; policyPhase = 2; return false;
        }
        var grant = (CraftingDetailPayloads.BrowseLease) plannerField("grant");
        if (grant != null) {
            require(grant.revision() == (long) plannerField("revision") && grant.revision() != previousPolicyRevision,
                    "Policy regression observed a lease from before its explicit browse invalidation");
            require(grant.rules().directPreparation().equals(expectedPolicy),
                    "Actual browseTick lease lost the authoritative preparation policy: expected=" + expectedPolicy
                            + ", lease=" + grant.rules().directPreparation());
        }
        require(age - policyStarted <= 160, "Real policy lease/snapshot did not become ready: expected=" + expectedPolicy
                + ", grant=" + (grant == null ? "none" : grant.rules().directPreparation())
                + ", snapshot=" + plannerField("snapshot"));
        if (!ClientBrowsePlanner.ready()) return false;
        var snapshot = (org.craftablemc.craftable.environment.BrowsingSnapshot) plannerField("snapshot");
        require(grant != null && snapshot != null && snapshot.directPreparation().equals(expectedPolicy)
                && snapshot.directPreparation().equals(grant.rules().directPreparation()),
                "Published real resource snapshot and lease disagree on the configured policy");
        if (restoringPolicy) {
            originalPolicy.forEach((value, original) -> require(value.getRaw().equals(original),
                    "Real browse policy regression failed to restore an original registered value: " + value.getPath()));
            policyChecked = true;
            require(personalMatrices == 20 && personalValue().getRaw().equals(originalPersonalMode),
                    "Real browse regression failed to finish its four-by-four modes plus empty-list matrix or restore personal mode");
            Craftable.LOGGER.warn("BROWSE_POLICY PASS: real BrowseRequest/browseTick/lease/chunk path retained all four world modes plus empty CUSTOM;20 real lease-bound personal/world combinations (all16 plus4empty-list) verified actual read-only partial safePartial, world whitelist membership and unlisted rejection, immediate ready invalidation, dynamic scope retirement and unchanged static catalog; original registered server and personal values restored through a fresh real lease; no injected grant or TOML write");
            return true;
        }
        if (!verifyPersonalModes(mc, snapshot)) return false;
        if (++policySample < 5) configurePolicy(mc, samplePolicy(policySample));
        else { restorePersonalMode(); restoringPolicy = true; configurePolicy(mc, savedPolicy); }
        policyStarted = age; policyPhase = 1;
        return false;
    }

    private boolean verifyPersonalModes(Minecraft mc, org.craftablemc.craftable.environment.BrowsingSnapshot live) throws Exception {
        var modes = DirectPreparationPolicy.Mode.values();
        var personal = modes[personalSample];
        if (personalPhase == 0) {
            if (personalSample == 0) matrixCatalogBuilds = ClientBrowsePlanner.catalogBuilds();
            personalPreviousScope = ClientBrowsePlanner.scopeVersion();
            personalChanged = CraftableClientConfig.partialMode() != personal;
            setPolicyValue(personalValue(), personal);
            if (personalChanged) require(!ClientBrowsePlanner.ready(),
                    "A changed personal mode retained stale ready authority before the next browsing tick: " + personal);
            personalPhase = 1; return false;
        }
        require(CraftableClientConfig.partialMode() == personal && ClientBrowsePlanner.ready(),
                "Live browsing did not bind the configured personal mode");
        require(ClientBrowsePlanner.catalogBuilds() == matrixCatalogBuilds
                && (!personalChanged || ClientBrowsePlanner.scopeVersion() > personalPreviousScope),
                "A personal-only mode change failed to retire dynamic results or rebuilt the static recipe catalog");
        var input = (org.craftablemc.craftable.recipe.PlanningInput) plannerField("input");
        var effective = expectedPolicy.restrict(personal.requestPolicy());
        require(effective.equals(plannerField("boundPreparation")),
                "Personal mode substituted a second list or loosened the world-owned whitelist: " + expectedPolicy + "/" + personal);
        for (String id : java.util.List.of("minecraft:stick", "minecraft:chest")) {
            var entry = input.find(ResourceLocation.parse(id));
            require(entry != null && input.allowDirectPreparation(entry) == effective.allowsWithoutConfirmation(entry.id()),
                    "Live input lost effective listed/unlisted consent: " + id + "/" + expectedPolicy.mode() + "/" + personal);
        }
        if (personalSearch == null) {
            // 中文：真实夹具仅一块橡木原木，三批木棍无法完整完成但只读准备可显示八根；用生产求解器/真实租约来源，EXPLICIT_SAFE 在个人 NEVER 下仍可读诊断。
            // The unchanged real fixture has one oak log: three stick batches
            // cannot fully complete, but a read-only preparation can show eight
            // sticks. This uses the production solver and real lease sources;
            // EXPLICIT_SAFE keeps diagnostics readable under personal NEVER.
            var request = new org.craftablemc.craftable.planner.CraftRequest(ResourceLocation.parse("minecraft:stick"), 3,
                    true, false, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of());
            personalSearch = new org.craftablemc.craftable.planner.CraftSearch(input, request, live.sources(),
                    org.craftablemc.craftable.planner.SearchBudget.resumable(8_000_000L), live.maxPlanSteps(),
                    plan -> org.craftablemc.craftable.execution.MainInventoryInsertion.simulate(live.inventory(),
                            live.inventoryMaximum(), plan, live.playerReferences(), live.references(), false).failure())
                    .withDiagnosticBudget(org.craftablemc.craftable.planner.SearchBudget.resumable(8_000_000L));
        }
        var result = personalSearch.advance(2_000_000L);
        if (result.isEmpty()) return false;
        var plan = result.get().plan().orElseThrow(() -> new AssertionError("Real personal-mode partial fixture did not finish: "
                + expectedPolicy.mode() + "/" + personal + ": " + result.get().code()));
        boolean expectedSafe = effective.allowsWithoutConfirmation(ResourceLocation.parse("minecraft:oak_planks"))
                && effective.allowsWithoutConfirmation(ResourceLocation.parse("minecraft:stick"));
        require(plan.partial() && plan.primary().stream().anyMatch(stack -> stack.is(Items.STICK))
                && plan.safePartial() == expectedSafe,
                "Real lease-bound partial plan used the wrong world/personal confirmation policy: "
                        + expectedPolicy.mode() + "/" + personal + "; expectedSafe=" + expectedSafe + "; plan=" + plan);
        personalSearch = null; personalPhase = 0; personalMatrices++;
        if (++personalSample < modes.length) return false;
        personalSample = 0; return true;
    }

    private static net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> personalValue() {
        return CraftableClientConfig.SPEC.getValues().get(java.util.List.of("crafting", "partialPolicy"));
    }

    private boolean verifyOverlayPersonalInvalidation(Minecraft mc, ResourceLocation target) throws Exception {
        if (overlayPolicyPhase == 0) {
            overlayCatalogBuilds = ClientBrowsePlanner.catalogBuilds();
            setPolicyValue(personalValue(), DirectPreparationPolicy.Mode.ALL_SUPPORTED);
            overlayPolicyPhase = 1; return false;
        }
        if (!ClientBrowsePlanner.ready()) return false;
        if (overlayPolicyPhase == 1) {
            org.craftablemc.craftable.client.CraftingPlanOverlay.open(mc.screen, target, false);
            var overlay = activeOverlay();
            require(overlay != null, "Personal mode race fixture could not open the real embedded review");
            overlay.tick(); // 中文：先绑定当前资源 scope。 English: Bind its current resource scope first.
            var changedAt = overlay.getClass().getDeclaredField("changedAt"); changedAt.setAccessible(true); changedAt.setLong(overlay, 0L);
            overlay.tick(); // 中文：由真实生产路径排入常规本地预览。 English: Real production path now queues its ordinary local preview.
            require("PREVIEW".equals(instanceField(overlay, "pending").toString()) && plannerField("detail") != null,
                    "Personal mode race fixture did not create an actual queued local overlay request");
            int generation = (int) instanceField(overlay, "generation");
            overlayOldScope = ClientBrowsePlanner.scopeVersion();
            setPolicyValue(personalValue(), DirectPreparationPolicy.Mode.DISABLED);
            require(!ClientBrowsePlanner.ready(), "Personal disable left the queued overlay's old browsing input ready");
            overlay.tick();
            var intent = (org.craftablemc.craftable.planner.CraftRequest) instanceField(overlay, "intent");
            require(intent.policy() == org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.NEVER
                    && (int) instanceField(overlay, "generation") > generation && (boolean) instanceField(overlay, "dirty")
                    && instanceField(overlay, "pending") == null && instanceField(overlay, "draft") == null
                    && instanceField(overlay, "displayedWitness") == null && instanceField(overlay, "maximum") == null,
                    "Open review retained its old policy/request/draft after the personal mode tightened");
            overlay.onClose(); overlayPolicyPhase = 2; return false;
        }
        if (overlayPolicyPhase == 2) {
            require(CraftableClientConfig.partialMode() == DirectPreparationPolicy.Mode.DISABLED
                    && ClientBrowsePlanner.scopeVersion() > overlayOldScope && plannerField("detail") == null,
                    "A queued old local preview survived the real personal-mode browsing transition");
            restorePersonalMode(); overlayPolicyPhase = 3; return false;
        }
        require(CraftableClientConfig.partialMode() == originalPersonalMode
                && ClientBrowsePlanner.catalogBuilds() == overlayCatalogBuilds,
                "Personal overlay race fixture did not restore its original preference without a catalog rebuild");
        overlayPolicyChecked = true;
        Craftable.LOGGER.warn("PERSONAL_POLICY_OVERLAY PASS: real embedded review tick queued a normal local PREVIEW (fixture bypassed only the150ms debounce); changing personal ALL to DISABLED immediately revoked ready and replaced the open intent/generation, cleared queued request/draft/witness/MAX, and next real browsing tick retired its dynamic scope; no create/confirm packet sent, original personal mode restored, static catalog unchanged");
        return true;
    }

    private static org.craftablemc.craftable.client.CraftingPlanOverlay activeOverlay() throws Exception {
        var field = org.craftablemc.craftable.client.CraftingPlanOverlay.class.getDeclaredField("current"); field.setAccessible(true);
        return (org.craftablemc.craftable.client.CraftingPlanOverlay) field.get(null);
    }

    private static Object instanceField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private void restorePersonalMode() {
        if (originalPersonalMode != null) setPolicyValue(personalValue(), originalPersonalMode);
        if (personalSearch != null) personalSearch.cancel(); personalSearch = null;
    }

    private static DirectPreparationPolicy samplePolicy(int sample) {
        if (sample == 4) return DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, java.util.List.of());
        return DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.values()[sample],
                java.util.List.of("minecraft:stick", "minecraft:oak_planks", "craftable:browse_allow_probe"));
    }

    private void configurePolicy(Minecraft mc, DirectPreparationPolicy policy) {
        expectedPolicy = policy; policyConfigured = false;
        mc.getSingleplayerServer().execute(() -> {
            if (policyAborted) return;
            try {
                if (restoringPolicy) restorePolicyValues();
                else for (var value : CraftableServerConfig.directPreparationValues()) {
                    Object proposed = switch (value.getPath().getLast()) {
                        case "directPreparationMode" -> policy.mode();
                        case "directPreparationAllowedRecipes" -> policy.allowedRecipes();
                        default -> throw new AssertionError("Unexpected preparation setting identity");
                    };
                    setPolicyValue(value, proposed);
                }
                if (policyAborted) { restorePolicyValues(); return; }
                policyConfigured = true;
            } catch (Throwable failure) { policyFailure = failure; }
        });
    }

    private void restorePolicyValues() { originalPolicy.forEach(BrowsingScenario::setPolicyValue); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setPolicyValue(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> value, Object proposed) {
        ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) value).set(proposed);
    }

    private static Object plannerField(String name) throws Exception {
        var field = ClientBrowsePlanner.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }

    /** 中文：失败后才观察授权/需求/调度，区分未派发与已运行未知；不改变等待、预算或被测搜索。
     * English: Inspect authority/demand/scheduling only on failure, separating missing dispatch from computed unknown without changing waits, budgets or searches. */
    private String browseFailureContext(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book) {
        try {
            return "; ready=" + ClientBrowsePlanner.ready() + ", bookVisible=" + book.isVisible()
                    + ", searches=" + (ClientBrowsePlanner.searches() - browseSearches)
                    + ", demand=" + ((java.util.List<?>) plannerField("foreground")).size() + "/"
                    + ((java.util.List<?>) plannerField("hidden")).size()
                    + ", gameTime=" + Minecraft.getInstance().level.getGameTime()
                    + ", renderTick=" + staticField(RecipeBookStatusHandler.class, "lastTick");
        } catch (Exception diagnosticFailure) { return "; context unavailable: " + diagnosticFailure; }
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
