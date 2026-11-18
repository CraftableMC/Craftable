package org.craftablemc.craftable.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.execution.CraftingSessions;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/**
 * 服务端主线程拥有的浏览传输状态：管理订阅、捕获节奏与分块公平性，不拥有合成权限。
 * Server-main-thread browsing transport state: owns subscriptions, capture cadence and chunk fairness,
 * but never crafting authority.
 * 包处理器进入前先移交线程；tick、退出和停服事件共享该状态所有者。
 * Packet handlers enqueue before entering; tick/logout/stop events share that state owner.
 * 数据新鲜度及端点绑定仍归 CraftingSessions 所有；传输身份不能替代执行时复验。
 * Freshness and endpoint bindings remain owned by CraftingSessions; transport identity cannot replace execution validation.
 */
final class BrowseSnapshotTransfers {
    private static final LinkedHashMap<UUID, Transfer> BROWSERS = new LinkedHashMap<>();
    private static int browseCursor, transferCursor;

    private BrowseSnapshotTransfers() {}

    /**
     * 接收意图后重新检查当前菜单及模式；关闭请求不受刷新限流阻挡。
     * Recheck the current menu and mode after receiving intent; refresh throttling never blocks a close request.
     */
    static void request(ServerPlayer player, CraftingDetailPayloads.BrowseRequest payload) {
        var existing = BROWSERS.get(player.getUUID());
        if (existing != null && payload.revision() < existing.request.revision()) return;
        if (!payload.open() || player.containerMenu.containerId != payload.menuId() || !CraftingService.validContext(player)) {
            close(player); return;
        }
        if (!CraftableRequestLimiter.allowStatus(player.getUUID(), player.level().getGameTime())) return;
        if (existing == null || existing.request.revision() != payload.revision()) {
            // 订阅数量单独有界；拒绝时保留客户端待定状态，绝不借用其他玩家的视图。
            // Admission is bounded independently of player count; refusal stays pending, never borrowing another player's view.
            if (existing == null && BROWSERS.size() >= 64) return;
            CraftingSessions.closeBrowsing(player);
            existing = new Transfer(payload);
            BROWSERS.put(player.getUUID(), existing);
        }
        existing.request = payload;
        existing.heard = player.level().getGameTime();
    }

    /** 释放传输与对应资源绑定。 / Release the transfer and its corresponding resource bindings. */
    static void close(ServerPlayer player) {
        BROWSERS.remove(player.getUUID());
        CraftingSessions.closeBrowsing(player);
    }

    /** 服务器停止只清理本类状态；执行会话有自己的生命周期。 / Stop clears this owner's state; execution sessions own their lifecycle. */
    static void stop() { BROWSERS.clear(); browseCursor = transferCursor = 0; }

    static void refreshAfterAttempt(ServerPlayer player) {
        var transfer = BROWSERS.get(player.getUUID());
        if (transfer != null) {
            // 下个被准入的轮询重新捕获；不创建客户端新会话，也不扩大预算或绕过公平性。
            // Recapture on the next admitted round-robin turn, without a new client session or extra budget/fairness bypass.
            transfer.nextScan = Long.MIN_VALUE;
        }
    }

    /**
     * 捕获与编码计入同一准入预算；两个游标分别限制准备与发送，慢接收者不会获得额外搜索。
     * Capture and encoding share admission accounting; separate preparation/wire cursors prevent slow receivers gaining extra searches.
     * 每次tick先移除失效订阅，再捕获与发送；不把资源句柄交给网络线程。
     * Each tick removes invalid subscriptions before capture and send; no resource handles are handed to a network thread.
     */
    static void tick(MinecraftServer server) {
        BROWSERS.entrySet().removeIf(e -> {
            var player = server.getPlayerList().getPlayer(e.getKey());
            boolean invalid = player == null || !CraftingService.validContext(player)
                    || player.containerMenu.containerId != e.getValue().request.menuId()
                    || player.level().getGameTime() - e.getValue().heard > 40;
            if (invalid && player != null) CraftingSessions.closeBrowsing(player);
            return invalid;
        });
        if (BROWSERS.isEmpty()) return;
        var ids = List.copyOf(BROWSERS.keySet());
        for (int i = 0; i < Math.min(4, ids.size()); i++) {
            var id = ids.get(Math.floorMod(browseCursor++, ids.size()));
            var transfer = BROWSERS.get(id);
            var player = server.getPlayerList().getPlayer(id);
            long now = player.level().getGameTime();
            if (now < transfer.nextScan) continue;
            // 限制同时传输的字节流而非获准查看的人数；第九个查看者无需等待别人关闭界面。
            // Bound concurrent byte streams, not authorized viewers; a ninth viewer need not wait for another UI to close.
            if (transfer.bytes == null && BROWSERS.values().stream().filter(v -> v.bytes != null).count() >= 8) continue;
            try (var lease = CraftableRequestLimiter.planning(server, id, false)) {
                if (!lease.allowed()) continue;
                var snapshot = CraftingSessions.refreshBrowsing(player);
                boolean changed = transfer.snapshot != snapshot;
                boolean resync = transfer.resyncRequested(snapshot);
                transfer.snapshot = snapshot;
                transfer.nextScan = now + 8;
                CraftingWire.SnapshotHeader header = null;
                if (changed || resync) {
                    // 身份变化必须重新编码并从第0块开始；不能把两个资源版本的字节拼成同一传输。
                    // Changed identity requires re-encoding from chunk zero; bytes from two resource versions must not form one transfer.
                    transfer.bytes = CraftingWire.snapshot(snapshot, player.registryAccess());
                    transfer.retained = 2L * transfer.bytes.length + 256L * snapshot.inputs().size() + 36L * 1024;
                    if (BROWSERS.values().stream().mapToLong(v -> v.retained).sum() > 64L * 1024 * 1024)
                        throw new IllegalStateException("Global browsing memory bound");
                    header = new CraftingWire.SnapshotHeader(snapshot.session(), UUID.randomUUID(),
                            snapshot.recipes(), snapshot.resources(), transfer.bytes.length,
                            Math.max(1, (transfer.bytes.length + CraftingWire.SNAPSHOT_CHUNK_BYTES - 1) / CraftingWire.SNAPSHOT_CHUNK_BYTES),
                            CraftingWire.sha256().digest(transfer.bytes));
                    transfer.header = header;
                    transfer.chunk = 0;
                    // 真实内容变化获得独立传输；同内容补传只消费一次，仍使用原轮询准入和并发限额。
                    // Changed content is a distinct transfer; same-content re-sync is consumed once under the existing admission/concurrency limits.
                    transfer.resynced = !changed;
                }
                var fingerprint = new CraftingRecipes(player, snapshot.workbench()).planningInput().fingerprint();
                PacketDistributor.sendToPlayer(player,
                        new CraftingDetailPayloads.BrowseLease(transfer.request.menuId(), transfer.request.revision(),
                                snapshot.session(), snapshot.recipes(), snapshot.resources(), now + 10, fingerprint, header,
                                CraftableServerConfig.scanSettings(), snapshot.workbench(),
                                new CraftableServerConfig.CraftingRules(
                                        snapshot.allowDrops() ? CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW
                                                : CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE,
                                        snapshot.maxBatches(), snapshot.maxPlanSteps(), snapshot.directPreparation())));
            } catch (RuntimeException failure) {
                // 规范化或捕获失败即丢弃保留的数据与资源绑定，冷却后再尝试，不发送半成品授权。
                // Failed normalization/capture drops retained data and bindings, then cools down; no partial grant is sent.
                transfer.bytes = null; transfer.snapshot = null; transfer.retained = 0;
                transfer.nextScan = now + 100;
                CraftingSessions.closeBrowsing(player);
                Craftable.LOGGER.warn("Browsing snapshot unavailable ({})", failure.getClass().getSimpleName());
            }
        }
        for (var id : transferTurn(ids, candidate -> BROWSERS.get(candidate).bytes != null)) {
            var transfer = BROWSERS.get(id);
            int offset = transfer.chunk * CraftingWire.SNAPSHOT_CHUNK_BYTES;
            var bytes = Arrays.copyOfRange(transfer.bytes, offset,
                    Math.min(transfer.bytes.length, offset + CraftingWire.SNAPSHOT_CHUNK_BYTES));
            PacketDistributor.sendToPlayer(server.getPlayerList().getPlayer(id),
                    new CraftingDetailPayloads.BrowseChunk(transfer.request.menuId(), transfer.request.revision(),
                            transfer.header.session(), transfer.header.transfer(), transfer.chunk++, bytes));
            if (transfer.chunk == transfer.header.chunks()) transfer.bytes = null;
        }
    }

    /**
     * 每轮全局至多四块、每人一块；选择与发送分开，测试直接覆盖实际游标策略。
     * At most four chunks globally and one per viewer per turn; selection is separate from I/O so tests exercise the real cursor policy.
     */
    static List<UUID> transferTurn(List<UUID> ids, Predicate<UUID> pending) {
        var selected = new ArrayList<UUID>(4);
        for (int visited = 0; visited < ids.size() && selected.size() < 4; visited++) {
            var id = ids.get(Math.floorMod(transferCursor++, ids.size()));
            if (pending.test(id)) selected.add(id);
        }
        return selected;
    }

    /**
     * 本类独占的可变传输游标；snapshot为执行会话提供的不可变值，bytes仅在发送期间保留。
     * Mutable transport cursor owned only here; snapshot is an immutable session value, and bytes live only during transmission.
     */
    static final class Transfer {
        CraftingDetailPayloads.BrowseRequest request;
        BrowsingSnapshot snapshot;
        CraftingWire.SnapshotHeader header;
        byte[] bytes;
        int chunk;
        long heard, nextScan, retained;
        boolean resynced;
        Transfer(CraftingDetailPayloads.BrowseRequest request) { this.request = request; }

        boolean resyncRequested(BrowsingSnapshot current) {
            return snapshot == current && !resynced && request.resources() == -1
                    && request.session().equals(current.session());
        }
    }
}
