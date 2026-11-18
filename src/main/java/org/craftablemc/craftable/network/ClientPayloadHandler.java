package org.craftablemc.craftable.network;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.craftablemc.craftable.client.CraftableFeedback;
import org.craftablemc.craftable.client.recipebook.RecipeBookStatusHandler;
import org.craftablemc.craftable.config.CraftableClientConfig;

/**
 * 客户端主线程的响应分发；各界面自行核对菜单、连接及revision，本类不拥有执行状态。
 * Client-main-thread response dispatch; each view checks menu/connection/revision while this class owns no execution state.
 * 结果去重序号与ClientRequestSequence同为进程生命周期，断线不重置其中任意一侧。
 * Result deduplication and ClientRequestSequence both span the process lifecycle; disconnect resets neither side independently.
 */
@OnlyIn(Dist.CLIENT)
final class ClientPayloadHandler {
    private static long lastCreateResponseRequestId = Long.MIN_VALUE;
    private ClientPayloadHandler() {}
    public static void handle(SettingsPayloads.RulesResponse payload) {
        org.craftablemc.craftable.client.CraftableSettingsScreen.receiveRules(payload.revision(), payload.scan(), payload.crafting());
    }
    public static void handle(CraftingDetailPayloads.BrowseLease payload) {
        org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }
    public static void handle(CraftingDetailPayloads.BrowseChunk payload) {
        org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.receive(payload);
    }

    static void handle(CraftingDetailPayloads.PreviewResponse payload) {
        org.craftablemc.craftable.client.CraftingPlanOverlay.receive(payload);
    }


    static void handle(CreateRecipeResultPayload payload) {
        // 先消费去重序号，再决定是否显示；离开有效模式期间收到的结果也不能在以后重复触发提示或刷新。
        // Consume deduplication sequence before deciding presentation; results received outside an allowed mode must not later repeat feedback/refresh.
        if (payload.requestId() <= lastCreateResponseRequestId) return;
        lastCreateResponseRequestId = payload.requestId();
        if (Minecraft.getInstance().player == null || !org.craftablemc.craftable.client.recipebook.RecipeBookProjection.modeAllowed()) return;
        // 限流/上下文拒绝未检查或改变资源；完成尝试后由现有服务端浏览会话刷新。
        // Throttling/context refusals inspect/change no resources; completed attempts refresh through the existing server browse session.
        if (payload.resultCode() != org.craftablemc.craftable.api.CraftingResultCode.REQUEST_THROTTLED
                && payload.resultCode() != org.craftablemc.craftable.api.CraftingResultCode.INVALID_CONTEXT)
            RecipeBookStatusHandler.afterCreate(payload.recipeId());
        org.craftablemc.craftable.client.CraftingPlanOverlay.created(payload);
        CraftableFeedback.showCreateResult(payload, CraftableClientConfig.detailedFailureFeedbackEnabled());
    }
}
