package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.menu.AmbientInventoryEvents;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.network.CraftablePayloadHandlers;
import org.craftablemc.craftable.network.CraftingDetailPayloads;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：完整预览自然就绪后再控制同值租约边界；不冒充历史冷启动复现，不制造资源修改、令牌、结果、新预算、真实 MAX 或确认。
 * English: Controlled same-value lease boundary, after natural full-preview readiness.
 * No historical cold-start reproduction, resource edit, token, solver result,
 * fresh budget, real MAX request or network confirmation is manufactured. */
final class LeaseReadinessScenario {
    private LeaseReadinessScenario() {}

    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var overlay = (CraftingPlanOverlay) get(CraftingPlanOverlay.class, null, "current");
        require(overlay != null && mc.screen instanceof AmbientInventoryScreen, "Lease regression needs the actual ambient detail parent");
        var draft = (CraftingService.Draft) get(CraftingPlanOverlay.class, overlay, "draft");
        var intent = (CraftRequest) get(CraftingPlanOverlay.class, overlay, "intent");
        var create = (Button) get(CraftingPlanOverlay.class, overlay, "create");
        var grant = (CraftingDetailPayloads.BrowseLease) get(ClientBrowsePlanner.class, null, "grant");
        Object pending = get(CraftingPlanOverlay.class, overlay, "pending");
        require(ClientBrowsePlanner.ready() && create.active && pending == null && !intent.partial()
                        && draft.view().code() == CraftingResultCode.CREATED && draft.view().complete()
                        && (boolean) get(CraftingPlanOverlay.class, overlay, "localDraft")
                        && draft.token().equals(CraftingDetailPayloads.NO_TOKEN) && grant != null,
                "Lease regression must follow naturally enabled local full confirm, not replace a failed preview");
        require(Objects.equals(get(AmbientInventoryEvents.class, null, "rules"), grant.settings()), "Lease scan rules differ before fixture");

        long sentAt = (long) get(CraftingPlanOverlay.class, overlay, "sentAt");
        long unavailableSince = (long) get(CraftingPlanOverlay.class, overlay, "unavailableSince");
        Object maximum = get(CraftingPlanOverlay.class, overlay, "maximum");
        Object snapshot = get(ClientBrowsePlanner.class, null, "snapshot");
        Object input = get(ClientBrowsePlanner.class, null, "input");
        Object witness = get(CraftingPlanOverlay.class, overlay, "displayedWitness");
        Object witnessRequest = get(CraftingPlanOverlay.class, overlay, "displayedWitnessRequest");
        long scope = ClientBrowsePlanner.scopeVersion();
        long overlayScope = (long) get(CraftingPlanOverlay.class, overlay, "resourceScope");
        Object review = draft.view().reviewIdentity();
        require(overlayScope == scope, "Local review is not in the current resource scope");
        var inventory = inventoryIdentity(mc);
        var revisions = List.of(ClientRecipeStatusStore.revision(), ClientRecipeStatusStore.authorityRevision(),
                ClientRecipeStatusStore.presentationRevision(), ClientBrowsePlanner.searches(), ClientBrowsePlanner.slices(),
                CraftablePayloadHandlers.detailRequests());
        Throwable originalFailure = null;
        try {
            // 中文：独立检查可见控件，不照抄 controls() 公式；常规节奏与只读 MAX 待定边界都须过期即禁用、恢复即启用。
            // Independently assert the visible control, not a duplicate of the
            // production controls() formula. Both normal cadence and a pending
            // read-only MAX boundary must disable on expiry and enable now.
            for (boolean waiting : new boolean[]{false, true}) {
                set(CraftingPlanOverlay.class, overlay, "pending", waiting ? maximumWork() : null);
                ClientBrowsePlanner.receive(sameValueLease(grant, mc.level.getGameTime() - 1));
                overlay.tick();
                require(!ClientBrowsePlanner.ready() && !create.active, "Expired lease left full confirm enabled; waiting=" + waiting);
                ClientBrowsePlanner.receive(sameValueLease(grant, grant.expires()));
                set(CraftingPlanOverlay.class, overlay, "sentAt", System.nanoTime());
                overlay.tick();
                require(ClientBrowsePlanner.ready() && create.active, "Renewed lease did not immediately enable full confirm; waiting=" + waiting);
            }
        } catch (Exception | AssertionError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                // 中文：恢复原租约对象但不重放传输头，只重置夹具拥有的 pending/节奏时钟。
                // Restore the original lease object without replaying its transfer
                // header. Only the fixture-owned pending/cadence clocks are reset.
                set(ClientBrowsePlanner.class, null, "grant", grant);
                set(CraftingPlanOverlay.class, overlay, "pending", pending);
                set(CraftingPlanOverlay.class, overlay, "sentAt", sentAt);
                set(CraftingPlanOverlay.class, overlay, "unavailableSince", unavailableSince);
                var controls = CraftingPlanOverlay.class.getDeclaredMethod("controls");
                controls.setAccessible(true); controls.invoke(overlay);
                require(get(ClientBrowsePlanner.class, null, "grant") == grant && ClientBrowsePlanner.scopeVersion() == scope
                                && (long) get(CraftingPlanOverlay.class, overlay, "resourceScope") == overlayScope
                                && get(ClientBrowsePlanner.class, null, "snapshot") == snapshot
                                && get(ClientBrowsePlanner.class, null, "input") == input,
                        "Lease fixture changed the frozen resource/knowledge scope");
                require(get(CraftingPlanOverlay.class, overlay, "draft") == draft && Objects.equals(get(CraftingPlanOverlay.class, overlay, "intent"), intent)
                                && Objects.equals(draft.view().reviewIdentity(), review)
                                && get(CraftingPlanOverlay.class, overlay, "maximum") == maximum
                                && get(CraftingPlanOverlay.class, overlay, "displayedWitness") == witness
                                && Objects.equals(get(CraftingPlanOverlay.class, overlay, "displayedWitnessRequest"), witnessRequest),
                        "Lease fixture changed the reviewed cost/plan/MAX/witness identity");
                require(inventory.equals(inventoryIdentity(mc))
                                && revisions.equals(List.of(ClientRecipeStatusStore.revision(), ClientRecipeStatusStore.authorityRevision(),
                                        ClientRecipeStatusStore.presentationRevision(), ClientBrowsePlanner.searches(), ClientBrowsePlanner.slices(),
                                        CraftablePayloadHandlers.detailRequests())),
                        "Read-only lease boundary changed inventory, verdicts, search work or server detail requests");
                require(get(CraftingPlanOverlay.class, overlay, "pending") == pending
                                && (long) get(CraftingPlanOverlay.class, overlay, "sentAt") == sentAt
                                && (long) get(CraftingPlanOverlay.class, overlay, "unavailableSince") == unavailableSince,
                        "Lease fixture did not restore pending work and cadence clocks");
                require(ClientBrowsePlanner.ready() && create.active, "Lease fixture did not restore original full confirm");
            } catch (Exception | AssertionError cleanupFailure) {
                if (originalFailure == null) throw cleanupFailure;
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        Craftable.LOGGER.warn("LEASE_READINESS PASS: controlled expired/renewed leases, immediate controls and read-only MAX wait; original scope/review/inventory restored");
    }

    private static CraftingDetailPayloads.BrowseLease sameValueLease(CraftingDetailPayloads.BrowseLease value, long expires) {
        return new CraftingDetailPayloads.BrowseLease(value.menuId(), value.revision(), value.session(), value.recipes(), value.resources(),
                expires, value.fingerprint(), null, value.settings(), value.workbench(), value.rules());
    }
    private static Object inventoryIdentity(Minecraft mc) {
        return CraftPlan.stackKeys(mc.player.getInventory().items.stream().map(net.minecraft.world.item.ItemStack::copy).toList());
    }
    private static Object maximumWork() throws Exception {
        for (var value : field(CraftingPlanOverlay.class, "pending").getType().getEnumConstants())
            if (value.toString().equals("MAXIMUM")) return value;
        throw new AssertionError("Missing read-only MAX work boundary");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Class<?> type, Object owner, String name) throws Exception { return field(type, name).get(owner); }
    private static void set(Class<?> type, Object owner, String name, Object value) throws Exception { field(type, name).set(owner, value); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
