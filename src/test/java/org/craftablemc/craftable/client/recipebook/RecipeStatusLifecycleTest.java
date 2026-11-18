package org.craftablemc.craftable.client.recipebook;

import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 区分独立租约失效与资源替换/断线，防止过期绿色获得授权或新作用域继承旧结论；不复活已退役的逐配方包序列模型。
 * Distinguish lease expiry from resource replacement/disconnect, catching authorized stale green or inherited verdicts without reviving retired per-recipe packet sequencing.
 */
class RecipeStatusLifecycleTest {
    private static final ResourceLocation A = ResourceLocation.withDefaultNamespace("stick");
    private static final ResourceLocation B = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    @AfterEach void clear() { ClientRecipeStatusStore.clear(); }
    private void put(ResourceLocation id, CraftingStatus status) {
        ClientRecipeStatusStore.completeLocal(id, status,
                status == CraftingStatus.CRAFTABLE ? CraftingResultCode.CREATED : CraftingResultCode.MISSING_INGREDIENTS,
                null, false);
    }
    @Test void expiredAuthorizationHidesGreenWithoutLosingTheConclusion() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        put(A, CraftingStatus.CRAFTABLE);
        ClientRecipeStatusStore.authorizeLocal(false);
        assertEquals(CraftingStatus.BLOCKED, ClientRecipeStatusStore.get(A, false));
        assertEquals(ClientRecipeStatusStore.Lifecycle.PENDING, ClientRecipeStatusStore.lifecycle(A));
        assertTrue(ClientRecipeStatusStore.computed(A));
        ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(CraftingStatus.CRAFTABLE, ClientRecipeStatusStore.get(A, false));
    }
    @Test void completedWorkCannotPublishOutsideALocalScope() {
        ClientRecipeStatusStore.clear();
        put(B, CraftingStatus.CRAFTABLE);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(B));
    }
    @Test void replacementResourcesDiscardEveryOldVerdict() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        put(A, CraftingStatus.CRAFTABLE); put(B, CraftingStatus.CRAFTABLE);
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(A));
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(B));
    }
    @Test void disconnectClearsKnowledgeAndAuthorization() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        put(A, CraftingStatus.CRAFTABLE);
        ClientRecipeStatusStore.clear();
        assertEquals(ClientRecipeStatusStore.Lifecycle.UNKNOWN, ClientRecipeStatusStore.lifecycle(A));
        assertFalse(ClientRecipeStatusStore.computed(A));
    }
    @Test void unchangedRenewalDoesNotRebuildPageButPendingResolutionDoes() {
        ClientRecipeStatusStore.beginLocal(); ClientRecipeStatusStore.authorizeLocal(true);
        put(A, CraftingStatus.CRAFTABLE);
        long revision = ClientRecipeStatusStore.revision();
        put(A, CraftingStatus.CRAFTABLE); ClientRecipeStatusStore.authorizeLocal(true);
        assertEquals(revision, ClientRecipeStatusStore.revision());
        ClientRecipeStatusStore.authorizeLocal(false);
        long pendingRevision = ClientRecipeStatusStore.revision();
        ClientRecipeStatusStore.authorizeLocal(true);
        assertTrue(ClientRecipeStatusStore.revision() > pendingRevision);
    }
}
