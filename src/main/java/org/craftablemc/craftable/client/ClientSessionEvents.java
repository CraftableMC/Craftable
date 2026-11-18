package org.craftablemc.craftable.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.client.recipebook.RecipeBookStatusHandler;

/** 中文：客户端事件总线上的会话失效入口，防止上一服务器的缓存、授权或菜单请求流入下一会话。
 * 配方/标签重载与断线分别退役动态证据；集成服的服务端标签事件不能冒充客户端重载。
 * English: Client event-bus lifecycle boundary preventing cached evidence, authority and menu requests from crossing sessions.
 * Recipe/tag reloads and disconnects retire dynamic evidence; integrated-server tag events are not client reloads. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class ClientSessionEvents {
    private ClientSessionEvents() {}

    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        // 中文：集成游戏共用事件总线；只有收到的客户端绑定可使本客户端图失效，服务端加载事件不可替代。
        // In an integrated game the same event bus also sees server loading.
        // Only the received client binding invalidates this client's graph.
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)
            org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.recipesChanged();
    }

    @SubscribeEvent
    public static void onRecipesUpdated(net.neoforged.neoforge.client.event.RecipesUpdatedEvent event) {
        org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.recipesChanged();
        ClientRecipeStatusStore.clear();
        RecipeBookStatusHandler.clearRequestState();
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        clearSessionState();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearSessionState();
    }

    private static void clearSessionState() {
        // 中文：各所有者清理自己的状态；这里仅编排生命周期，不建立第二份会话缓存。
        // English: Each owner clears its own state; this method coordinates lifecycle without a second session cache.
        org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.disconnect();
        ClientRecipeStatusStore.clear();
        org.craftablemc.craftable.client.menu.AmbientInventoryEvents.clear();
        RecipeBookStatusHandler.clearRequestState();
    }
}
