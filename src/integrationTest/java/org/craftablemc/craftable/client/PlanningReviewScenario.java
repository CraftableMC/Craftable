package org.craftablemc.craftable.client;

import static org.craftablemc.craftable.client.PlanningClientFixture.*;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.PlanningClientSmoke.Stage;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：先只读候选审阅，再真实完整/部分确认；拥有审阅身份和中间 UI 检查，不拥有整组生命周期。
 * English: Read-only candidate review, then real full and partial confirmations.
 * Owns review identities and intermediate UI checks, not the suite lifecycle. */
final class PlanningReviewScenario {
    private Object menu;
    private long serverDetails;
    private long witnessBefore, fullSearchesBefore;
    private enum FullReview { LOCAL_REVIEW, AUTHORITATIVE_REVIEW }
    private enum PartialRefresh { DISPLAY_PARTIAL, RESTORE_FULL, RESTORE_PARTIAL }
    private FullReview fullReview = FullReview.LOCAL_REVIEW;
    private PartialRefresh partialRefresh = PartialRefresh.DISPLAY_PARTIAL;
    private int refreshedPanY;
    private boolean preferenceLifecycleVerified;

    String progress(Stage stage) {
        return switch (stage) {
            case FULL_CONFIRM_AND_LEASE_READINESS -> fullReview.name();
            case PARTIAL_REVIEW -> partialRefresh.name();
            default -> "";
        };
    }

    void openDetails(Minecraft mc) throws Exception {
        menu = mc.player.containerMenu;
        serverDetails = org.craftablemc.craftable.network.CraftablePayloadHandlers.detailRequests();
        key(67, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        require(CraftingPlanOverlay.active(), "Shift+C did not open embedded detail");
        require(mc.player.containerMenu == menu, "Detail replaced parent menu");
    }

    boolean tick(Stage stage, int age, Minecraft mc) throws Exception {
        if (stage == Stage.DIAGNOSTIC_LIFECYCLE_AND_GRAPH && ready()) {
            if (!DiagnosticLifecycleScenario.verify()) return false;
            GraphPresentationScenario.verify();
            var draft = draft();
            require(draft.view().code() == CraftingResultCode.CREATED, "Full preview not craftable: " + draft.view().code());
            require(draft.view().operations().size() == 3, "Preview lost recursive steps");
            require(draft.token().equals(org.craftablemc.craftable.network.CraftingDetailPayloads.NO_TOKEN),
                    "Local browsing manufactured server execution authority");
            require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Preview mutated inventory");
            var maximum = (CraftingService.Maximum) field(overlay(), "maximum");
            require(maximum.lowerBound() == 1, "Wrong full maximum");
            shot("graph");
            // 中文：走图命中测试与输入拦截，不用私有选择器调用替代玩家路径。
            // Exercise the graph hit-test and input interception, not a private chooser call.
            var graph = field(overlay(), "graph");
            var cells = (List<?>) field(graph, "cells");
            var cell = cells.stream().filter(c -> {
                try { return ((List<?>) field(c, "recipes")).contains("minecraft:stick"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).findFirst().orElseThrow();
            var widget = (net.minecraft.client.gui.components.AbstractWidget) graph;
            require(widget.getHeight() >= mc.screen.height * 0.65, "Graph still squeezed by toolbar");
            var create = (Button) field(overlay(), "create");
            var slider = (net.minecraft.client.gui.components.AbstractWidget) field(overlay(), "slider");
            require(create.getY() == slider.getY(), "Bottom actions not on one row");
            require(create.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.create").getString()),
                    "Primary action no longer has one stable label");
            require(allButtons(overlay()).stream().filter(b -> b.visible && b.getY() == create.getY()).count() == 1,
                    "Old partial/refresh actions remain on bottom row");
            var refresh = (Button) field(overlay(), "refresh");
            require(refresh.getWidth() == 20 && refresh.getY() < create.getY(), "Refresh is still a full-size primary action");
            require(allButtons(overlay()).stream().noneMatch(b -> b.visible && b.getY() < 34), "Old top navigation remains");
            click(widget.getX() + (int) field(cell, "x") + (int) field(graph, "panX") + 8,
                    widget.getY() + (int) field(cell, "y") + (int) field(graph, "panY") + 8);
            return true;
        } else if (stage == Stage.CANDIDATE_NAVIGATION && age == 30) {
            // 中文：首材料悬停若干真实渲染帧后截图，重现后续槽位纹理覆盖其提示的反例。
            // Hover the first ingredient for several rendered frames before
            // capturing: this reproduces later-slot blits covering its tooltip.
            int cx = mc.screen.width / 2, cy = Math.max(24, (mc.screen.height - 76) / 2 - 38);
            var window = mc.getWindow();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(),
                    (cx - 62.0) * window.getScreenWidth() / mc.screen.width,
                    (cy + 25.0) * window.getScreenHeight() / mc.screen.height);
        } else if (stage == Stage.CANDIDATE_NAVIGATION && age > 60 && field(overlay(), "pending") == null) {
            require(field(overlay(), "pane").toString().equals("CHOICES"), "Node click missed chooser");
            var states = (java.util.Map<?, ?>) field(overlay(), "candidateStates");
            require(states.get(ResourceLocation.withDefaultNamespace("stick")) == CraftingResultCode.CREATED, "Usable candidate not verified globally");
            require(states.get(ResourceLocation.withDefaultNamespace("stick_from_bamboo_item")) != CraftingResultCode.CREATED, "Bamboo candidate falsely available");
            shot("choices");
            var next = (Button) field(overlay(), "nextChoice");
            click(next.getX() + 5, next.getY() + 5);
            require((int) field(overlay(), "choicePage") == 1, "Next recipe did not change card");
            shot("bamboo-card");
            var apply = (Button) field(overlay(), "applyChoice");
            click(apply.getX() + 5, apply.getY() + 5);
            return true;
        } else if (stage == Stage.UNAVAILABLE_PIN && ready()) {
            require(org.craftablemc.craftable.network.CraftablePayloadHandlers.detailRequests() == serverDetails,
                    "Passive details/candidates/MAX still searched on server");
            Craftable.LOGGER.warn("LOCAL_DETAILS previews/candidates/MAX serverRequests=0");
            require(draft().view().code() != CraftingResultCode.CREATED, "Unavailable pin silently ignored");
            require(mc.player.getInventory().countItem(Items.STICK) == 0, "Candidate selection crafted materials");
            shot("blocked-pin");
            key(256, 0);
            require(!CraftingPlanOverlay.active() && mc.player.containerMenu == menu && mc.screen instanceof AmbientInventoryScreen,
                    "Esc closed/replaced the parent menu");
            CraftingPlanOverlay.open(mc.screen, PICK, false);
            return true;
        } else if (stage == Stage.FULL_CONFIRM_AND_LEASE_READINESS && ready()) {
            var create = (Button) field(overlay(), "create");
            require(create.active, "Full confirm disabled; plannerReady="
                    + org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.ready()
                    + ", code=" + draft().view().code());
            if (fullReview == FullReview.LOCAL_REVIEW) {
                // 中文：自然就绪后才做具名可控回归，在任何真实审阅/合成点击前恢复租约。
                // A named, controlled regression after natural readiness;
                // restore its lease before any real review/craft click.
                if (!preferenceLifecycleVerified) LeaseReadinessScenario.verify();
                if (ClientTestRun.leaseDiagnostic()) return true;
                witnessBefore = executionCounter("witnessValidations");
                fullSearchesBefore = executionCounter("activeFullSearches");
                // 中文：只模拟客户端旧/不匹配审阅而不改服务端库存；首次点击可获授权，但不能静默接受不同材料成本。
                // Simulate a stale/mismatching client review, not changed
                // server inventory. The first click may obtain authority
                // but must not silently accept different material costs.
                var original = draft(); var view = original.view();
                var changed = new org.craftablemc.craftable.planner.PlanView(view.code(), view.workbench(), view.complete(),
                        view.completedBatches(), view.nodes(), view.operations(), List.of(new ItemStack(Items.OAK_LOG, 2)),
                        view.primary(), view.surplus(), view.drops(), view.missing());
                var field = overlay().getClass().getDeclaredField("draft"); field.setAccessible(true);
                field.set(overlay(), new CraftingService.Draft(original.token(), changed, original.choices()));
                click(create.getX() + 5, create.getY() + 5);
                fullReview = FullReview.AUTHORITATIVE_REVIEW;
                return false;
            }
            require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0,
                    "Changed authoritative review auto-executed without a second click");
            require(!draft().token().equals(org.craftablemc.craftable.network.CraftingDetailPayloads.NO_TOKEN),
                    "Explicit review never reached the server");
            if (!preferenceLifecycleVerified) {
                PersonalPreferenceLifecycleScenario.verify();
                preferenceLifecycleVerified = true;
                fullReview = FullReview.LOCAL_REVIEW;
                return false; // 中文：先取得新的自然审阅，再重置既有证据/搜索基线。 English: Obtain a fresh natural review, then reset existing witness/search baselines.
            }
            click(create.getX() + 5, create.getY() + 5);
            return true;
        } else if (stage == Stage.FULL_CONFIRM_ACCOUNTING && age > 30 && ready()) {
            require(executionCounter("witnessValidations") >= witnessBefore + 2, "Full detail did not validate witness twice");
            require(executionCounter("activeFullSearches") == fullSearchesBefore, "Full detail re-searched on server");
            Craftable.LOGGER.warn("PLANNING_CLIENT full detail review+confirm witnessValidations=2 fullSearches=0");
            require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 1, "Confirm did not deliver pickaxe");
            require(mc.player.getInventory().countItem(Items.STICK) == 2, "Wrong stick surplus");
            require(mc.player.getInventory().countItem(Items.OAK_PLANKS) == 2, "Wrong plank surplus");
            require(mc.player.containerMenu == menu, "Confirmation changed menu");
            key(256, 0);
            configured = false;
            mc.getSingleplayerServer().execute(() -> { stock(false); configured = true; });
            return true;
        } else if (stage == Stage.PARTIAL_FIXTURE && configured && age > 30) {
            CraftingPlanOverlay.open(mc.screen, PICK, false);
            return true;
        } else if (stage == Stage.PARTIAL_REVIEW && ready()) {
            if (partialRefresh == PartialRefresh.DISPLAY_PARTIAL) {
                require(((CraftRequest) field(overlay(), "intent")).partial(), "Partial frontier was not selected for read-only display");
                var graph = (PlanGraphWidget) field(overlay(), "graph");
                graph.mouseScrolled(graph.getX() + 2, graph.getY() + 2, 0, 1);
                refreshedPanY = (int) field(graph, "panY");
                mc.getSingleplayerServer().execute(() -> {
                    var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                    chest.setItem(1, new ItemStack(Items.DIAMOND, 3)); chest.setChanged();
                });
                partialRefresh = PartialRefresh.RESTORE_FULL;
                return false;
            }
            if (partialRefresh == PartialRefresh.RESTORE_FULL) {
                if (draft().view().code() != CraftingResultCode.CREATED) return false;
                require(!((CraftRequest) field(overlay(), "intent")).partial(), "Automatic full refresh retained partial intent");
                require((int) field(field(overlay(), "graph"), "panY") == refreshedPanY, "Automatic refresh reset graph position");
                require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Auto-refresh crafted without a click");
                mc.getSingleplayerServer().execute(() -> {
                    var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                    chest.setItem(1, ItemStack.EMPTY); chest.setChanged();
                });
                partialRefresh = PartialRefresh.RESTORE_PARTIAL;
                return false;
            }
            if (draft().view().code() != CraftingResultCode.PARTIAL_CREATED) return false;
            require(draft().view().code() == CraftingResultCode.PARTIAL_CREATED, "Missing diamond not shown as partial");
            shot("partial-graph");
            require(((Button) field(overlay(), "create")).active, "Displayed partial action disabled");
            return true;
        } else if (stage == Stage.PARTIAL_CONFIRM && ready()) {
            require(((CraftRequest) field(overlay(), "intent")).partial(), "Review did not select partial intent");
            require(mc.player.getInventory().countItem(Items.STICK) == 0, "First partial click consumed before review");
            var create = (Button) field(overlay(), "create");
            require(create.active, "Displayed partial craft disabled");
            click(create.getX() + 5, create.getY() + 5);
            return true;
        } else if (stage == Stage.PARTIAL_ACCOUNTING && age > 30 && ready()) {
            if (mc.player.getInventory().countItem(Items.STICK) != 4) shot("partial-failed");
            require(mc.player.getInventory().countItem(Items.STICK) == 4,
                    "Partial did not deliver one whole stick batch: code=" + draft().view().code()
                            + ", token=" + draft().token() + ", local=" + field(overlay(), "localDraft")
                            + ", pane=" + field(overlay(), "pane") + ", routeChanged=" + field(overlay(), "routeChanged")
                            + ", notice=" + ((net.minecraft.network.chat.Component) field(overlay(), "notice")).getString()
                            + ", fullSearches=" + executionCounter("activeFullSearches")
                            + ", partialSearches=" + executionCounter("activePartialSearches"));
            require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Partial fabricated root");
            require(!((Button) field(overlay(), "create")).active, "Existing frontier allowed redundant partial");
            shot("partial-completed");
            key(256, 0);
            configured = false;
            mc.getSingleplayerServer().execute(() -> {
                var player = serverPlayer();
                player.getInventory().clearContent();
                var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST);
                chest.clearContent();
                chest.setItem(0, new ItemStack(Items.BIRCH_LOG));
                chest.setItem(1, new ItemStack(Items.OAK_LOG, 2));
                chest.setChanged(); player.containerMenu.broadcastChanges(); configured = true;
            });
            return true;
        }
        return false;
    }
}
