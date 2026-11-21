package org.craftablemc.craftable.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 协议版本与全部payload方向的唯一注册点；服务端公共入口不载入客户端实现。
 * Sole registration point for protocol version and all payload directions; server entry points do not load client implementations.
 * ClientPayloadHandler只在客户端入站主线程任务内调用；任何执行请求仍须经服务层权威校验。
 * ClientPayloadHandler runs only inside client-inbound main-thread tasks; execution requests still require service-owned authoritative validation.
 */
public final class CraftablePayloads {
    public static final String PROTOCOL_VERSION = "17";

    private CraftablePayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(SettingsPayloads.RulesRequest.TYPE, SettingsPayloads.RulesRequest.CODEC,
                CraftablePayloadHandlers::handleSettingsRequest);
        registrar.playToClient(SettingsPayloads.RulesResponse.TYPE, SettingsPayloads.RulesResponse.CODEC,
                (p, c) -> c.enqueueWork(() -> ClientPayloadHandler.handle(p)));
        registrar.playToServer(CraftingDetailPayloads.BrowseRequest.TYPE, CraftingDetailPayloads.BrowseRequest.CODEC,
                CraftablePayloadHandlers::handleBrowse);
        registrar.playToClient(CraftingDetailPayloads.BrowseLease.TYPE, CraftingDetailPayloads.BrowseLease.CODEC,
                (p,c) -> c.enqueueWork(() -> ClientPayloadHandler.handle(p)));
        registrar.playToClient(CraftingDetailPayloads.BrowseChunk.TYPE, CraftingDetailPayloads.BrowseChunk.CODEC,
                (p,c) -> c.enqueueWork(() -> ClientPayloadHandler.handle(p)));
        registrar.playToClient(FurnaceProgressPayload.TYPE, FurnaceProgressPayload.CODEC, (p, c) -> {
            var connection = c.connection();
            c.enqueueWork(() -> ClientPayloadHandler.handle(p, connection));
        });
        registrar.playToServer(CraftingDetailPayloads.PreviewRequest.TYPE, CraftingDetailPayloads.PreviewRequest.CODEC,
                CraftablePayloadHandlers::handlePlanPreview);
        registrar.playToClient(CraftingDetailPayloads.PreviewResponse.TYPE, CraftingDetailPayloads.PreviewResponse.CODEC,
                CraftablePayloadHandlers::handlePlanPreviewResult);
        registrar.playToServer(CraftingDetailPayloads.ConfirmRequest.TYPE, CraftingDetailPayloads.ConfirmRequest.CODEC,
                CraftablePayloadHandlers::handlePlanConfirm);
        registrar.playToServer(OpenInventoryRequestPayload.TYPE, OpenInventoryRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleOpenInventory);
        // 浏览计算和MAX在客户端本地进行；只有显式操作能请求服务端规划。
        // Browsing calculations and MAX are client-local; only explicit actions can request a server plan.
        registrar.playToServer(
                CreateRecipeRequestPayload.TYPE,
                CreateRecipeRequestPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateRequest);
        registrar.playToClient(
                CreateRecipeResultPayload.TYPE,
                CreateRecipeResultPayload.STREAM_CODEC,
                CraftablePayloadHandlers::handleCreateResult);
    }
}
