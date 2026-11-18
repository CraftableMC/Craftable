package org.craftablemc.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.planner.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 检查结论、展示与证据的独立生命周期及有界调度，防止租约续期复活旧证据、跨意图借用结论或把未知MAX当精确值。
 * Check independent verdict/display/evidence lifecycles and bounded scheduling against revived evidence, cross-intent reuse and falsely exact unknown MAX values.
 * 每例结束清空静态存储，使前例的授权/配方结论不能污染后例。
 * Clear static storage after each case so prior authorization/verdicts cannot contaminate later cases.
 */
class ClientRecipeStatusStoreTest {
    private static final ResourceLocation RECIPE = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    @AfterEach void clearStore() { ClientRecipeStatusStore.clear(); }

    @Test void refreshRetainsPresentationButDiscardsEvidenceAndDoesNotAuthorizeCrafting() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        ClientRecipeStatusStore.beginRefresh();
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.display(RECIPE, false));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, false));
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        assertNull(ClientRecipeStatusStore.evidence(RECIPE));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.display(RECIPE, false));
        assertEquals(ClientRecipeStatusStore.Lifecycle.KNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
    }

    @Test void repeatedRefreshCannotLoseDisplayAndNewMenuCannotInheritIt() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        for (int i = 0; i < 50; i++) ClientRecipeStatusStore.beginRefresh();
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.display(RECIPE, false));
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.hasDisplay(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.display(RECIPE, false));
    }

    @Test void sameDisplayAfterRefreshDoesNotRequestACollectionRebuild() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        long shown = ClientRecipeStatusStore.presentationRevision();
        ClientRecipeStatusStore.beginRefresh();
        ClientRecipeStatusStore.authorizeLocal(true);
        ClientRecipeStatusStore.completeLocal(RECIPE, CraftingStatus.CRAFTABLE, CraftingResultCode.CREATED, null, false);
        assertEquals(shown, ClientRecipeStatusStore.presentationRevision());
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        assertEquals(shown + 1, ClientRecipeStatusStore.presentationRevision());
    }

    @Test void authorizationRenewalKeepsComputedUnknownWithoutGrantingRetry() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        assertFalse(ClientRecipeStatusStore.needsDiagnostic(RECIPE));
        long before = ClientRecipeStatusStore.revision();
        ClientRecipeStatusStore.authorizeLocal(true);
        for (int i = 0; i < 100; i++) ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(before + 1, ClientRecipeStatusStore.revision());
        ClientRecipeStatusStore.authorizeLocal(false);
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
    }

    @Test void newScopeDoesNotInheritOldConclusionsOrVanillaOptimism() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.completeLocal(RECIPE, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.KNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.computed(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, true));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(RECIPE));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(RECIPE));
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(RECIPE, true));
    }

    @Test void foregroundChurnCannotStarveHiddenHeadAcross400Slices() {
        var turns = new ClientBrowsePlanner.Turns();
        int last = -1, served = 0;
        for (int tick = 0; tick < 400; tick++) {
            boolean hidden = turns.hidden(true, true);
            turns.served(hidden);
            if (hidden) { assertTrue(tick - last <= 4); last = tick; served++; }
        }
        assertEquals(100, served);
        // 隐藏队列空闲时不能积攒无限前台额度，否则再次出现的隐藏任务会长期饥饿。
        // Idle hidden queues must not accumulate unlimited foreground credit, which would starve hidden work when it returns.
        for (int tick = 0; tick < 100; tick++) turns.served(false);
        assertTrue(turns.hidden(true, true));
        turns.served(true);
        assertFalse(turns.hidden(true, true));
        assertTrue(turns.hidden(false, true));
    }

    @Test void detailCountsAndConstraintsShareOneStoreButNeverBorrowEvidence() {
        ClientRecipeStatusStore.beginLocal();
        ClientRecipeStatusStore.defaults(false, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE);
        var one = org.craftablemc.craftable.planner.CraftRequest.one(RECIPE);
        assertSame(one, one.withPartial(false));
        assertSame(one, one.withBatches(1));
        ClientRecipeStatusStore.completeLocal(one, SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        assertTrue(ClientRecipeStatusStore.computed(RECIPE));
        assertTrue(ClientRecipeStatusStore.exhausted(one.withPartial(true)));
        assertFalse(ClientRecipeStatusStore.computed(one.withBatches(2)));
        var pin = new org.craftablemc.craftable.planner.CraftRequest(RECIPE, 1, false, one.allowDrops(), one.policy(),
                java.util.Map.of("0.7", ResourceLocation.withDefaultNamespace("stick_from_bamboo_item")));
        assertFalse(ClientRecipeStatusStore.computed(pin));
        for (int i = 0; i < 100; i++) ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.exhausted(one));
        ClientRecipeStatusStore.beginLocal();
        assertFalse(ClientRecipeStatusStore.exhausted(one));
    }

    @Test void localMaximumKeepsNonMonotoneCapacityAndUnknownUpperCounts() {
        ClientRecipeStatusStore.beginLocal();
        var intent = org.craftablemc.craftable.planner.CraftRequest.one(RECIPE);
        ClientRecipeStatusStore.completeLocal(intent, SearchResult.blocked(CraftingResultCode.NO_OUTPUT_SPACE), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(2), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        assertTrue(ClientBrowsePlanner.maximumView(intent, 4).pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(3), SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(4), SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
        var limited = ClientBrowsePlanner.maximumView(intent, 4);
        assertEquals(2, limited.lowerBound());
        assertFalse(limited.proven()); assertTrue(limited.limited()); assertFalse(limited.pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(4), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        var complete = ClientBrowsePlanner.maximumView(intent, 4);
        assertEquals(4, complete.lowerBound()); assertTrue(complete.proven());
        assertFalse(complete.limited());
    }

    @Test void largeConstraintIdentitiesStopBeforeTheResultMemoryReserve() {
        ClientRecipeStatusStore.beginLocal();
        int inserted = 0;
        var pins = new java.util.HashMap<String, ResourceLocation>();
        for (int i = 0; i < 32; i++) pins.put("0." + (i / 9) + "." + (i % 9), RECIPE);
        for (int i = 0; i < 16_384; i++) {
            var request = new org.craftablemc.craftable.planner.CraftRequest(ResourceLocation.withDefaultNamespace("test_" + i),
                    1, false, false, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
            if (!ClientRecipeStatusStore.canRecord(request)) break;
            ClientRecipeStatusStore.completeLocal(request, SearchResult.blocked(CraftingResultCode.MISSING_INGREDIENTS), null, false);
            inserted++;
        }
        assertTrue(inserted > 0 && inserted < 512, "constraint strings must be charged, not just entry count");
        ClientRecipeStatusStore.beginLocal();
        assertTrue(ClientRecipeStatusStore.canRecord(org.craftablemc.craftable.planner.CraftRequest.one(RECIPE)));
    }

    @Test void certifiedMaterialCeilingDoesNotTurnUnknownVerdictsIntoFailureProof() {
        ClientRecipeStatusStore.beginLocal();
        var intent = org.craftablemc.craftable.planner.CraftRequest.one(RECIPE);
        ClientRecipeStatusStore.completeLocal(intent, SearchResult.blocked(CraftingResultCode.NO_OUTPUT_SPACE), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(2), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        ClientRecipeStatusStore.completeLocal(intent.withBatches(3), SearchResult.blocked(CraftingResultCode.SEARCH_BUDGET_EXCEEDED), null, false);
        var exact = ClientBrowsePlanner.maximumView(intent, 64, 2);
        assertEquals(2, exact.lowerBound()); assertEquals(64, exact.cap());
        assertTrue(exact.proven()); assertFalse(exact.pending()); assertFalse(exact.limited());
        assertTrue(ClientRecipeStatusStore.exhausted(intent.withBatches(3)));
        assertNull(ClientRecipeStatusStore.evidence(intent.withBatches(3)));
        // 证书范围内存在未知数量仍不能声称精确，即使更少数量成功；容量并不单调，不能推导缺失结论。
        // An unknown quantity inside the certificate still prevents precision despite a smaller success; non-monotone capacity cannot fill missing verdicts.
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64, 3).limited());
        assertFalse(ClientBrowsePlanner.maximumView(intent, 64, 3).proven());
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64).pending());
        ClientRecipeStatusStore.beginRefresh();
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64, 2).pending());
        ClientRecipeStatusStore.completeLocal(intent.withBatches(32), SearchResult.blocked(CraftingResultCode.CREATED), null, false);
        var largest = ClientBrowsePlanner.maximumView(intent, 64, 32);
        assertEquals(32, largest.lowerBound()); assertTrue(largest.proven());
        assertFalse(largest.pending()); assertFalse(largest.limited());
        // 未计算的更低数量并不因此可行，但证书上限已有具体见证时，这些未知值不改变已证明的MAX。
        // Lower uncomputed quantities are not implied feasible, but do not change MAX once its certified ceiling has a concrete witness.
        assertFalse(ClientRecipeStatusStore.computed(intent.withBatches(31)));
        assertTrue(ClientBrowsePlanner.maximumView(intent, 64).pending());
    }
}
