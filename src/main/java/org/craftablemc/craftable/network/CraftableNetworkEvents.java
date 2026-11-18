package org.craftablemc.craftable.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.craftablemc.craftable.Craftable;

/**
 * NeoForge服务端事件入口：tick驱动浏览/会话过期，退出清理连接状态，停服清理传输状态。
 * NeoForge server-event entry: ticks drive browsing/session expiry, logout clears connection state, and stop clears transport state.
 * 与主线程包处理共享状态所有者；注解订阅不是显式Java调用，不能按引用搜索删除。
 * Shares state ownership with main-thread packet handling; annotated subscriptions are not explicit Java calls and cannot be removed by reference searches.
 */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class CraftableNetworkEvents {
    private CraftableNetworkEvents() {}

    @SubscribeEvent
    public static void onStop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        CraftablePayloadHandlers.stopBrowsing();
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        CraftablePayloadHandlers.browseTick(event.getServer());
        if (event.getServer().getTickCount() % 20 == 0)
            org.craftablemc.craftable.execution.CraftingSessions.expire(event.getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 先释放浏览端点绑定，再删除限流和执行会话；每个所有者清理自己的状态。
            // Release browse endpoint bindings, then rate/execution state; each owner clears its own lifecycle state.
            CraftablePayloadHandlers.closeBrowse(player);
            CraftableRequestLimiter.clear(player.getUUID());
            org.craftablemc.craftable.execution.CraftingSessions.clear(player.getUUID());
        }
    }
}
