package org.craftablemc.craftable.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.environment.EnvironmentSnapshotService;
import org.craftablemc.craftable.execution.CraftingService;

/**
 * 网络边界只负责主线程移交、请求准入及回应；规则、资源与最终提交由 CraftingService 负责。
 * The network boundary owns main-thread handoff, admission and replies; CraftingService owns rules, resources and final commit.
 * 客户端传来的菜单、策略与见证都是意图或关联信息，不能成为权限证明。
 * Client menu IDs, preferences and witnesses express intent/correlation, never authority.
 */
public final class CraftablePayloadHandlers {
    private CraftablePayloadHandlers() {}

    private static volatile long detailRequests;
    /** 只观察实际网络入口，不授予执行权限。 / Observe real network entries without granting execution authority. */
    public static long detailRequests() { return detailRequests; }

    /** 只读规则发现，不访问资源或创建合成租约。 / Read-only rule discovery without resource access or crafting leases. */
    public static void handleSettingsRequest(SettingsPayloads.RulesRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !CraftableRequestLimiter.allowSettings(player.getUUID(), System.nanoTime())) return;
            // 设置读取廉价且不授予资源权限，因此创造模式也可查看。
            // Effective-config reads are cheap and grant no resource access, so creative players may inspect settings too.
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new SettingsPayloads.RulesResponse(payload.revision(),
                            org.craftablemc.craftable.config.CraftableServerConfig.scanSettings(),
                            org.craftablemc.craftable.config.CraftableServerConfig.craftingRules()));
        });
    }

    public static void handleBrowse(CraftingDetailPayloads.BrowseRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            BrowseSnapshotTransfers.request(player, payload);
        });
    }

    public static void closeBrowse(ServerPlayer player) {
        BrowseSnapshotTransfers.close(player);
    }
    static void stopBrowsing() { BrowseSnapshotTransfers.stop(); }

    private static void refreshAfterAttempt(ServerPlayer player) {
        BrowseSnapshotTransfers.refreshAfterAttempt(player);
    }

    /** 仅由服务端tick调用。 / Called only by the server tick. */
    public static void browseTick(net.minecraft.server.MinecraftServer server) {
        BrowseSnapshotTransfers.tick(server);
    }

    /** 保留测试使用的实际轮询入口。 / Preserve the real round-robin entry exercised by transport tests. */
    static java.util.List<java.util.UUID> transferTurn(java.util.List<java.util.UUID> ids,
            java.util.function.Predicate<java.util.UUID> pending) {
        return BrowseSnapshotTransfers.transferTurn(ids, pending);
    }

    /** 详情审阅只产生一次性候选令牌；执行仍经确认和新鲜度复验。 / Detail review offers a one-use token; execution still revalidates on confirm. */
    public static void handlePlanPreview(CraftingDetailPayloads.PreviewRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            detailRequests++;
            var result = CraftingDetailPayloads.failed(CraftingResultCode.INVALID_CONTEXT);
            if (player.containerMenu.containerId == payload.menuId() && CraftingService.validContext(player)) {
                result = CraftingDetailPayloads.failed(CraftingResultCode.REQUEST_THROTTLED);
                if (CraftableRequestLimiter.allowDetail(player.getUUID(), player.level().getGameTime()))
                    try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                        if (lease.allowed()) result = CraftingService.preview(player, payload.request(), payload.choicePath(),
                                payload.witness(), payload.revision(), lease::remaining);
                    }
            }
            context.reply(new CraftingDetailPayloads.PreviewResponse(payload.menuId(), payload.revision(), result));
        });
    }

    /** 菜单检查和创建限流不能取代服务层令牌消费、规则和资源复验。 / Menu checks/create admission do not replace service token consumption or rule/resource validation. */
    public static void handlePlanConfirm(CraftingDetailPayloads.ConfirmRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // 必须在主线程重新读取当前菜单，不能使用网络到达时的上下文来确认随后已关闭/切换的页面。
            // Reread the current menu on the main thread; arrival-time context cannot confirm a page closed/switched before queued work runs.
            if (player.containerMenu.containerId != payload.menuId() || !CraftingService.validContext(player)) {
                context.reply(new CreateRecipeResultPayload(payload.recipe(), payload.revision(), CraftingResultCode.INVALID_CONTEXT));
                return;
            }
            if (CraftableRequestLimiter.allowCreate(player.getUUID(), player.level().getGameTime()))
                try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                    if (lease.allowed()) {
                        var result = CraftingService.confirm(player, payload.token(), payload.witness(), payload.revision(), lease::remaining);
                        refreshAfterAttempt(player);
                        var target = result.plan() == null ? payload.recipe() : result.plan().target();
                        context.reply(CreateRecipeResultPayload.from(target, payload.revision(), result));
                        return;
                    }
                }
            context.reply(new CreateRecipeResultPayload(payload.recipe(), payload.revision(), CraftingResultCode.REQUEST_THROTTLED));
        });
    }

    public static void handlePlanPreviewResult(CraftingDetailPayloads.PreviewResponse payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }


    /** 重新扫描可用工作台后打开菜单；客户端不能指定端点或资源。 / Open after a fresh usable-workbench scan; clients cannot name endpoints/resources. */
    public static void handleOpenInventory(OpenInventoryRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || payload.requestId() < 0
                    || !org.craftablemc.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())
                    || !player.isAlive() || player.containerMenu != player.inventoryMenu
                    || !CraftableRequestLimiter.allowOpen(player.getUUID(), player.serverLevel().getGameTime())) return;
            var snapshot = EnvironmentSnapshotService.fresh(player);
            var tables = snapshot.workstations().stream()
                    .filter(w -> w.capability() == org.craftablemc.craftable.workstation.WorkstationCapability.CRAFTING_3X3)
                    .map(w -> w.position()).filter(java.util.Objects::nonNull).toList();
            if (tables.isEmpty()) return;
            // openMenu不会移除inventoryMenu；先返还旧2x2格及手持物，避免切换时遗留物品。
            // openMenu does not remove inventoryMenu; return its old 2x2 grid and carried stack first to avoid stranded items.
            player.inventoryMenu.removed(player);
            player.inventoryMenu.broadcastChanges();
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, owner) -> new org.craftablemc.craftable.menu.AmbientInventoryMenu(id, inventory, tables),
                    net.minecraft.network.chat.Component.translatable("container.crafting")));
        });
    }


    /** 普通/双击部分合成共用创建准入；服务端关联前次结果并收紧个人请求。 / Ordinary/double-press partial crafting share admission; the service links prior results and restricts personal intent. */
    public static void handleCreateRequest(CreateRecipeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !org.craftablemc.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer())) {
                return;
            }
            if (payload.requestId() < 0) {
                return;
            }
            if (!CraftableRequestLimiter.allowCreate(player.getUUID(), player.serverLevel().getGameTime())) {
                context.reply(new CreateRecipeResultPayload(
                        payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
                return;
            }
            try (var lease = CraftableRequestLimiter.planning(player.getServer(), player.getUUID(), true)) {
                if (!lease.allowed()) {
                    context.reply(new CreateRecipeResultPayload(payload.recipeId(), payload.requestId(), CraftingResultCode.REQUEST_THROTTLED));
                    return;
                }
                var result = CraftingService.attempt(player, payload.recipeId(), payload.requestId(),
                        payload.precedingPress(), payload.allowDrops(), payload.partialPolicy(), lease::remaining);
                // 尝试结束无论成功与否都只安排受预算约束的重新捕获；回应并不成为下一次尝试的材料快照。
                // Any completed attempt only schedules budgeted recapture, successful or not; its reply never becomes the next attempt's input snapshot.
                refreshAfterAttempt(player);
                context.reply(CreateRecipeResultPayload.from(payload.recipeId(), payload.requestId(), result));
            }
        });
    }

    public static void handleCreateResult(CreateRecipeResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handle(payload));
    }
}
