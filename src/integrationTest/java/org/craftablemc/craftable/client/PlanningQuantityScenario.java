package org.craftablemc.craftable.client;

import static org.craftablemc.craftable.client.PlanningClientFixture.*;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.PlanningClientSmoke.Stage;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：验证混材数量/成本审阅及真实排队 MAX 的取消；入口以 HIGHEST 观察、LOWEST 普通推进，防止快回复令在途状态不可见。
 * English: Mixed-material quantity/cost review and cancellation of a real queued MAX; early observation prevents fast replies from hiding in-flight state.
 * The entry invokes observation at HIGHEST and normal ticks at LOWEST. */
final class PlanningQuantityScenario {
    private boolean queuedCancellationChecked;
    private long queuedCancellationWitnesses, queuedCancellationFull, queuedCancellationPartial;
    private enum CapacityStep { CONFIGURE, OPEN, WAIT_MENU, REVIEW, SELECT_TWO }
    private CapacityStep capacityStep = CapacityStep.CONFIGURE;
    private volatile boolean capacityConfigured;

    void observeQueuedCancellation(Minecraft mc) throws Exception {
        if (queuedCancellationChecked) return;
        var self = overlay();
        if (self == null || draft() == null || draft().view().code() != CraftingResultCode.CREATED
                || (boolean) field(self, "dirty")) return;
        self.tick(); // 中文：复用真实去抖、请求身份及本地 MAX 调度器。 English: Uses the real debounce, request identity and local MAX scheduler.
        if (field(self, "pending") != null && field(self, "pending").toString().equals("MAXIMUM"))
            cancelQueuedMaximum(mc);
    }

    void verifyPendingMaximumControl() throws Exception {
        if (overlay() != null && draft() != null && draft().view().code() == CraftingResultCode.CREATED
                && !(boolean) field(overlay(), "dirty") && !((CraftRequest) field(overlay(), "intent")).partial()
                && field(overlay(), "pane").toString().equals("GRAPH")
                && field(overlay(), "pending") != null && field(overlay(), "pending").toString().equals("MAXIMUM")
                && Boolean.FALSE.equals(field(overlay(), "queuedAction"))) {
            var create = (Button) field(overlay(), "create");
            boolean local = (boolean) field(overlay(), "localDraft");
            boolean authorized = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.ready();
            // 中文：MAX 等待不能让新鲜预览闪灰，但本地显示授权过期必须禁用；检查两个方向，不以 ready 过滤同 scope 样本。
            // Waiting for MAX must not blink a fresh preview, but expired local authority must disable it. Check both directions without filtering same-scope samples by readiness.
            if (local && !authorized) {
                // 中文：LOW 压力夹具可能晚于 NORMAL owner tick 退休 scope；不把尚未消费的旧控件当成同 scope 过期。跨 scope 的草稿/排队退休由生命周期反例检查。
                // The LOW pressure fixture may retire a scope after the NORMAL owner tick. That unconsumed old control is not same-scope expiry; lifecycle counterexamples check cross-scope draft/queue retirement.
                boolean synchronizedScope = (long) field(overlay(), "resourceScope")
                        == org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.scopeVersion();
                if (synchronizedScope && create.active) throw new AssertionError("Expired local MAX left the confirm button enabled"
                        + maximumControlContext(local, authorized));
            } else if (!create.active) {
                throw new AssertionError("MAX slice blinked the confirm button"
                        + maximumControlContext(local, authorized));
            }
        }
    }

    /** 中文：仅失败时读取事实，区分租约撤权与真正新鲜 MAX 闪烁，不维护另一份产品状态。
     * English: Read facts only on failure to distinguish lease revocation from fresh MAX blinking, without maintaining another product state. */
    private static String maximumControlContext(boolean local, boolean authorized) {
        try {
            var mc = Minecraft.getInstance();
            var grantField = org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.class.getDeclaredField("grant");
            grantField.setAccessible(true);
            var grant = (org.craftablemc.craftable.network.CraftingDetailPayloads.BrowseLease) grantField.get(null);
            return "; ready=" + authorized + ", localDraft=" + local + ", token=" + draft().token()
                    + ", intent=" + field(overlay(), "intent")
                    + ", scope=" + org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.scopeVersion()
                    + ", overlayScope=" + field(overlay(), "resourceScope")
                    + ", menu=" + mc.player.containerMenu.containerId + ", gameTime=" + mc.level.getGameTime()
                    + ", leaseMenu=" + (grant == null ? "none" : grant.menuId())
                    + ", leaseExpires=" + (grant == null ? "none" : grant.expires());
        } catch (Exception failure) {
            return "; ready=" + authorized + ", localDraft=" + local + ", diagnosticContextFailure=" + failure;
        }
    }

    boolean tick(Stage stage, int age, Minecraft mc) throws Exception {
        if (stage == Stage.MIXED_FIXTURE && configured && age > 30) {
            CraftingPlanOverlay.open(mc.screen, ResourceLocation.withDefaultNamespace("stick"), false);
            return true;
        } else if (stage == Stage.MIXED_REVIEW && ready()) {
            require(((CraftingService.Maximum) field(overlay(), "maximum")).lowerBound() == 6, "Mixed stock MAX not six batches");
            var slider = (net.minecraft.client.gui.components.AbstractSliderButton) field(overlay(), "slider");
            require(slider.active, "Known MAX did not enable slider");
            click(slider.getX() + slider.getWidth() - 4, slider.getY() + 10);
            return true;
        } else if (stage == Stage.QUANTITY_AND_COST_REVIEW && age > 5 && ready()) {
            require(((CraftRequest) field(overlay(), "intent")).batches() == 6, "Slider did not choose six batches");
            require(draft().view().code() == CraftingResultCode.CREATED, "Mixed MAX is not executable");
            require((boolean) field(overlay(), "routeChanged"), "Mixed route warning missing");
            var cells = (List<?>) field(field(overlay(), "graph"), "cells");
            var materialIds = new java.util.HashSet<String>();
            boolean deviation = false;
            for (var cell : cells) {
                var needs = listField(cell, "needs", ItemStack.class);
                if (needs.stream().anyMatch(s -> s.is(Items.BIRCH_LOG) || s.is(Items.OAK_LOG))) {
                    require(needs.size() == 1, "Mixed actual materials still hidden behind one icon");
                    materialIds.add((String) field(cell, "id"));
                    deviation |= (boolean) field(cell, "deviation");
                }
            }
            require(materialIds.size() >= 2 && deviation, "Mixed graph missing oak/birch branches or deviation badge");
            for (int i = 0; i < cells.size(); i++) for (int j = i + 1; j < cells.size(); j++) {
                var a = cells.get(i); var b = cells.get(j);
                if (field(a, "x").equals(field(b, "x")))
                    require(Math.abs((int) field(a, "y") - (int) field(b, "y")) >= 26,
                            "Shared mixed supplies overlap in the same column");
            }
            // 中文：混材分配不等价于固定输出需求，须查总需求与真实生产，不能按显示节点数推数量。
            // Mixed material allocations are not equivalent fixed-output
            // demands. Check their total needs and real production rather
            // than inferring quantities from the number of display nodes.
            int plankNeeds = 0, plankMade = 0;
            int connectedVariants = 0;
            for (var cell : cells) {
                for (var stack : listField(cell, "needs", ItemStack.class))
                    if (stack.is(Items.OAK_PLANKS) || stack.is(Items.BIRCH_PLANKS)) plankNeeds += stack.getCount();
                for (var stack : listField(cell, "made", ItemStack.class))
                    if (stack.is(Items.OAK_PLANKS) || stack.is(Items.BIRCH_PLANKS)) plankMade += stack.getCount();
            }
            require(plankNeeds == 12 && plankMade == 12, "Mixed shared demands lost total needs or real production");
            for (var cell : cells) {
                var needs = listField(cell, "needs", ItemStack.class);
                if (needs.stream().noneMatch(s -> s.is(Items.OAK_PLANKS) || s.is(Items.BIRCH_PLANKS))) continue;
                require(needs.size() == 1, "Mixed production still labels several materials as one wood");
                var wood = needs.getFirst();
                var source = wood.is(Items.OAK_PLANKS) ? Items.OAK_LOG : Items.BIRCH_LOG;
                int expected = wood.is(Items.OAK_PLANKS) ? 8 : 4;
                require(wood.getCount() == expected, "Mixed wood variant lost exact demand quantity");
                var children = listField(cell, "children", String.class);
                require(children.size() == 1 && materialIds.contains(children.getFirst()), "Mixed wood has missing or duplicate source edges");
                var child = cells.stream().filter(c -> {
                    try { return field(c, "id").equals(children.getFirst()); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).findFirst().orElseThrow();
                var inputs = listField(child, "needs", ItemStack.class);
                require(inputs.size() == 1 && inputs.getFirst().is(source) && inputs.getFirst().getCount() == expected / 4,
                        "Mixed wood connected to another material's log");
                var icon = PlanGraphWidget.class.getDeclaredMethod("icon", cell.getClass());
                icon.setAccessible(true);
                var displayed = (ItemStack) icon.invoke(null, cell);
                require(ItemStack.isSameItemSameComponents(displayed, wood) && displayed.getCount() == expected,
                        "Mixed plank icon uses another material's identity or quantity");
                connectedVariants++;
            }
            require(connectedVariants == 2, "Mixed inputs not connected to separate exact production variants");
            shot("mixed-graph");
            var create = (Button) field(overlay(), "create");
            click(create.getX() + 5, create.getY() + 5);
            require(field(overlay(), "pane").toString().equals("COSTS"), "Mixed route skipped actual cost review");
            require(mc.player.getInventory().countItem(Items.STICK) == 0, "Mixed route spent before second confirmation");
            return true;
        } else if (stage == Stage.QUANTITY_CONFIRM && age > 15 && ready()) {
            shot("mixed-costs");
            var create = (Button) field(overlay(), "create");
            click(create.getX() + 5, create.getY() + 5);
            return true;
        } else if (stage == Stage.QUANTITY_ACCOUNTING && age > 30 && ready()) {
            require(mc.player.getInventory().countItem(Items.STICK) == 24, "Mixed MAX did not yield 24 sticks");
            key(256, 0);
            configured = false;
            mc.getSingleplayerServer().execute(() -> {
                stock(true);
                var chest = (ChestBlockEntity) serverPlayer().serverLevel().getBlockEntity(CHEST);
                // 中文：新资源 scope 才有未计算的多选数量，旧 cap-one 缓存 MAX 不属于此取消反例。
                // A fresh resource scope has uncomputed multi-pick
                // quantities, rather than the old cap-one cached MAX.
                chest.setItem(0, new ItemStack(Items.OAK_LOG, 64));
                chest.setItem(1, new ItemStack(Items.DIAMOND, 64));
                chest.setChanged();
                configured = true;
            });
            return true;
        } else if (stage == Stage.QUEUED_ACTION_FIXTURE && configured && age > 30) {
            CraftingPlanOverlay.open(mc.screen, PICK, false);
            return true;
        } else if (stage == Stage.QUEUED_ACTION_CANCELLATION && !queuedCancellationChecked && draft() != null
                && draft().view().code() == CraftingResultCode.CREATED
                && field(overlay(), "pending") != null && field(overlay(), "pending").toString().equals("MAXIMUM")) {
            cancelQueuedMaximum(mc);
        } else if (stage == Stage.QUEUED_ACTION_CANCELLATION && ready()) {
            require(queuedCancellationChecked, "Real in-flight MAX cancellation was not exercised");
            require(mc.player.getInventory().countItem(Items.DIAMOND_PICKAXE) == 0, "Canceled queued click still crafted");
            require(executionCounter("witnessValidations") == queuedCancellationWitnesses
                    && executionCounter("activeFullSearches") == queuedCancellationFull
                    && executionCounter("activePartialSearches") == queuedCancellationPartial,
                    "Canceled queued click reached authoritative preparation after Esc");
            mc.options.guiScale().set(1); mc.resizeDisplay();
            return true;
        }
        return false;
    }

    private void cancelQueuedMaximum(Minecraft mc) throws Exception {
        var create = (Button) field(overlay(), "create");
        require(create.active, "Pending MAX disabled the real queued-action button");
        queuedCancellationWitnesses = executionCounter("witnessValidations");
        queuedCancellationFull = executionCounter("activeFullSearches");
        queuedCancellationPartial = executionCounter("activePartialSearches");
        click(create.getX() + 5, create.getY() + 5);
        require(Boolean.TRUE.equals(field(overlay(), "queuedAction")), "MAX click was lost instead of queued once");
        key(256, 0);
        require(!CraftingPlanOverlay.active(), "Esc did not cancel waiting action");
        queuedCancellationChecked = true;
        CraftingPlanOverlay.open(mc.screen, PICK, false);
    }

    /** 中文：反射只暴露 Object；逐项核对后再读图数据，让类型漂移在观察边界明确失败，而不是压制未经检查的转换。
     * English: Reflection exposes Object; check each graph value at the observation boundary so type drift fails explicitly instead of suppressing an unchecked cast. */
    private static <T> List<T> listField(Object owner, String name, Class<T> type) throws Exception {
        Object value = field(owner, name);
        require(value instanceof List<?>, "Expected a graph list: " + name);
        return ((List<?>) value).stream().map(element -> {
            require(type.isInstance(element), "Unexpected graph element type: " + name);
            return type.cast(element);
        }).toList();
    }

    /**
     * 一份骨头消耗不腾槽、两份才腾槽；用真实资源与调度器防止 MAX 把第一次容量失败当作更大量失败。
     * One bone leaves no output slot, while consuming both frees one. Real resources and scheduling
     * catch MAX treating the first capacity failure as proof against every larger quantity.
     */
    boolean nonMonotoneMaximum(Minecraft mc) throws Exception {
        var boneMeal = ResourceLocation.withDefaultNamespace("bone_meal");
        if (capacityStep == CapacityStep.CONFIGURE) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                var player = serverPlayer();
                player.closeContainer();
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
                for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
                ((ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST)).clearContent();
                player.getInventory().clearContent();
                for (int slot = 0; slot < 36; slot++)
                    player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                player.getInventory().setItem(0, new ItemStack(Items.BONE, 2));
                player.containerMenu.broadcastChanges();
                capacityConfigured = true;
            });
            capacityStep = CapacityStep.OPEN;
        } else if (capacityStep == CapacityStep.OPEN && capacityConfigured
                && mc.player.getInventory().countItem(Items.BONE) == 2) {
            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            capacityStep = CapacityStep.WAIT_MENU;
        } else if (capacityStep == CapacityStep.WAIT_MENU
                && mc.screen instanceof org.craftablemc.craftable.client.menu.AmbientInventoryScreen
                && org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.ready()) {
            // 原版背包会异步替换为环境菜单；等真实菜单/授权建立后再绑定详情，避免把导航竞态当作 MAX 失败。
            // Inventory opens the ambient menu asynchronously; bind details only after its real menu/grant arrives, not to the retired parent.
            CraftingPlanOverlay.open(mc.screen, boneMeal, false);
            capacityStep = CapacityStep.REVIEW;
        } else if (capacityStep == CapacityStep.REVIEW && ready()) {
            var maximum = (CraftingService.Maximum) field(overlay(), "maximum");
            require(draft().view().code() == CraftingResultCode.NO_OUTPUT_SPACE,
                    "One bone should not free capacity for its output");
            require(maximum.proven() && maximum.lowerBound() == 2 && !maximum.limited(),
                    "Live MAX stopped at the first capacity failure: " + maximum);
            var slider = (net.minecraft.client.gui.components.AbstractSliderButton) field(overlay(), "slider");
            require(slider.active, "Feasible larger quantity did not enable quantity selection");
            click(slider.getX() + slider.getWidth() - 4, slider.getY() + 10);
            capacityStep = CapacityStep.SELECT_TWO;
        } else if (capacityStep == CapacityStep.SELECT_TWO && ready()
                && ((CraftRequest) field(overlay(), "intent")).batches() == 2) {
            require(draft().view().code() == CraftingResultCode.CREATED,
                    "Two bones should free one slot for six bone meal");
            require(mc.player.getInventory().countItem(Items.BONE) == 2
                    && mc.player.getInventory().countItem(Items.BONE_MEAL) == 0,
                    "Read-only MAX or quantity selection consumed materials");
            key(256, 0);
            org.craftablemc.craftable.Craftable.LOGGER.warn(
                    "NON_MONOTONE_MAXIMUM PASS: live one-batch capacity failure, two-batch success, no consumption");
            return true;
        }
        return false;
    }
}
