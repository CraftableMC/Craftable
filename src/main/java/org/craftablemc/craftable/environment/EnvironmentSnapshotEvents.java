package org.craftablemc.craftable.environment;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.craftablemc.craftable.Craftable;

/**
 * NeoForge 生命周期入口，不能因没有显式 Java 调用而删除；清理服务端玩家快照所有权。
 * NeoForge lifecycle entry, not dead code despite no direct Java caller; releases server-player snapshot ownership.
 */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class EnvironmentSnapshotEvents {
    private EnvironmentSnapshotEvents() {}

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EnvironmentSnapshotService.invalidate(player.getUUID());
        }
    }
}
