package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.network.CraftablePayloadHandlers;
import org.craftablemc.craftable.network.CraftingWire;

/**
 * 先建立真实大快照，再仅把同一传输接收时钟置于停滞边界；恢复必须走真实包、发送轮询和解码，不能直接调用 retry 冒充接线覆盖。
 * Establish a real large snapshot, then place only its receive clock at the stall boundary; recovery must traverse real packets, sender polling and decoding, not a direct retry call.
 * 临时受保护物品仅占一个自有背包槽，为固定总期限留出响应余量；成功或异常均归还，不制造见证、令牌或合成请求。
 * One temporary protected inventory stack leaves response margin inside the fixed deadline; success/failure restores it without manufacturing witnesses, tokens or craft requests.
 */
final class SnapshotResyncScenario {
    private enum Phase { PREPARE, AWAIT_LARGE_SNAPSHOT, AWAIT_RESYNC, AWAIT_RESTORE, DONE }
    private Phase phase = Phase.PREPARE;
    private ItemStack original;
    private net.minecraft.server.MinecraftServer server;
    private UUID playerId;
    private int started;
    private long resources, details;
    private UUID session, transfer;
    private int catalogBuilds;

    boolean tick(Minecraft mc, int age) throws Exception {
        if (phase == Phase.DONE) return true;
        if (phase == Phase.PREPARE) {
            if (!ClientBrowsePlanner.ready()) return false;
            server = mc.getSingleplayerServer(); playerId = mc.player.getUUID();
            server.submit(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                original = player.getInventory().getItem(35).copy();
                var tag = new CompoundTag();
                tag.putByteArray("snapshot_resync_fixture", new byte[800_000]);
                var paper = new ItemStack(Items.PAPER);
                paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                player.getInventory().setItem(35, paper);
                player.containerMenu.broadcastChanges();
            }).join();
            started = age; phase = Phase.AWAIT_LARGE_SNAPSHOT;
            return false;
        }
        require(age - started < 200, "Real browsing re-sync did not complete at " + phase);
        if (phase == Phase.AWAIT_LARGE_SNAPSHOT) {
            var snapshot = (BrowsingSnapshot) get(ClientBrowsePlanner.class, null, "snapshot");
            var header = (CraftingWire.SnapshotHeader) get(ClientBrowsePlanner.class, null, "receiving");
            if (!ClientBrowsePlanner.ready() || snapshot == null || header == null
                    || header.bytes() < 800_000 || !snapshot.session().equals(header.session())
                    || snapshot.resources() != header.resources()) return false;
            session = snapshot.session(); resources = snapshot.resources(); transfer = header.transfer();
            catalogBuilds = ClientBrowsePlanner.catalogBuilds(); details = CraftablePayloadHandlers.detailRequests();
            // 中文：不等待真实五秒或延长 D；相同真实头仍决定总期限，只模拟没有收到任何进度的五秒。
            // Do not sleep five seconds or extend D: this same real header determines the total deadline; simulate five seconds without receive progress only.
            var stalled = new CraftingWire.SnapshotReceiver(header, System.nanoTime() - 5_000_000_001L);
            set(ClientBrowsePlanner.class, null, "receiver", stalled);
            set(ClientBrowsePlanner.class, null, "snapshot", null);
            set(ClientBrowsePlanner.class, null, "resyncRequested", false);
            require(!ClientBrowsePlanner.ready(), "Missing browsing bytes retained client authority");
            started = age; phase = Phase.AWAIT_RESYNC;
            return false;
        }
        if (phase == Phase.AWAIT_RESYNC) {
            var snapshot = (BrowsingSnapshot) get(ClientBrowsePlanner.class, null, "snapshot");
            var header = (CraftingWire.SnapshotHeader) get(ClientBrowsePlanner.class, null, "receiving");
            if (!ClientBrowsePlanner.ready() || snapshot == null || header == null || transfer.equals(header.transfer())) return false;
            require(session.equals(snapshot.session()) && resources == snapshot.resources(),
                    "Re-sync replaced the resource identity instead of recovering identical bytes");
            require(ClientBrowsePlanner.catalogBuilds() == catalogBuilds && CraftablePayloadHandlers.detailRequests() == details,
                    "Byte recovery rebuilt the static catalog or requested a server plan");
            server.submit(() -> {
                try {
                    var type = Class.forName("org.craftablemc.craftable.network.BrowseSnapshotTransfers");
                    var transfers = (Map<?, ?>) get(type, null, "BROWSERS");
                    var state = transfers.get(playerId);
                    require(state != null && (boolean) get(state.getClass(), state, "resynced"),
                            "Recovered bytes did not consume the actual sender's one-resync allowance");
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }).join();
            restore();
            started = age; phase = Phase.AWAIT_RESTORE;
            return false;
        }
        if (!ClientBrowsePlanner.ready()) return false;
        var snapshot = (BrowsingSnapshot) get(ClientBrowsePlanner.class, null, "snapshot");
        if (snapshot == null || snapshot.resources() == resources) return false;
        phase = Phase.DONE;
        Craftable.LOGGER.warn("BROWSE_RESYNC PASS: real same-content request/poll/header/chunks/decode recovered a controlled stalled receiver; one allowance consumed, inventory restored");
        return true;
    }

    void close() {
        restore();
        ClientBrowsePlanner.invalidate();
    }

    private void restore() {
        if (original == null) return;
        var saved = original;
        server.submit(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                player.getInventory().setItem(35, saved);
                player.containerMenu.broadcastChanges();
            }
        }).join();
        original = null;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Class<?> type, Object owner, String name) throws ReflectiveOperationException { return field(type, name).get(owner); }
    private static void set(Class<?> type, Object owner, String name, Object value) throws ReflectiveOperationException { field(type, name).set(owner, value); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
