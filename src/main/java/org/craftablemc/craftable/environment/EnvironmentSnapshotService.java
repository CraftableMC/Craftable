package org.craftablemc.craftable.environment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.EnvironmentScanSettings;

/**
 * 环境发现的唯一运行入口；缓存包含活容器，只能由服务端主线程拥有和访问。
 * 只读诊断可复用短缓存；任何授权续租、审阅或资源变更都必须 fresh，不能缓存授权。
 * Sole runtime discovery entry; cached snapshots contain live containers and are owned/accessed only on the server thread.
 * Read-only diagnostics may reuse a short cache; grant renewal, review and mutation must use fresh, never cached authority.
 */
public final class EnvironmentSnapshotService {
    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();

    private EnvironmentSnapshotService() {}

    /** 仅用于有界陈旧的只读诊断。 / Bounded-age values for read-only diagnostics only. */
    public static EnvironmentSnapshot preview(ServerPlayer player) {
        requireServerThread(player);
        EnvironmentScanSettings settings = CraftableServerConfig.scanSettings();
        EnvironmentSnapshotCacheKey key = key(player, settings);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        EnvironmentSnapshot cached = state.cached;
        if (cached != null
                && key.canReuse(cached, player.serverLevel().getGameTime())
                && cached.endpoints().stream().allMatch(endpoint -> endpoint.isStillValid(player))) {
            return cached;
        }
        return capture(player, settings, state);
    }

    /**
     * 每次重新扫描，重新检查已加载范围、锁/物理可用性和当前菜单输入。
     * Always rescan loaded range, locks/physical accessibility and current menu inputs.
     */
    public static EnvironmentSnapshot fresh(ServerPlayer player) {
        requireServerThread(player);
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        return capture(player, CraftableServerConfig.scanSettings(), state);
    }

    /** 清值不重置扫描世代；登出才移除玩家状态。 / Drop values without resetting scan generations; logout removes the owner. */
    public static void invalidate(UUID playerId) {
        PlayerState state = PLAYER_STATES.get(playerId);
        if (state != null) {
            state.cached = null;
        }
    }

    public static void remove(UUID playerId) {
        PLAYER_STATES.remove(playerId);
    }

    private static EnvironmentSnapshot capture(
            ServerPlayer player, EnvironmentScanSettings settings, PlayerState state) {
        if (state.lastGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Environment generation exhausted");
        }
        long generation = ++state.lastGeneration;
        EnvironmentSnapshot snapshot = EnvironmentScanner.scan(player, settings, generation);
        state.cached = snapshot;
        return snapshot;
    }

    private static EnvironmentSnapshotCacheKey key(
            ServerPlayer player, EnvironmentScanSettings settings) {
        return new EnvironmentSnapshotCacheKey(
                player.serverLevel().dimension().location(), player.blockPosition(), settings);
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread()) {
            throw new IllegalStateException("Environment snapshots may only be accessed on the server thread");
        }
    }

    private static final class PlayerState {
        private long lastGeneration;
        private EnvironmentSnapshot cached;
    }
}
