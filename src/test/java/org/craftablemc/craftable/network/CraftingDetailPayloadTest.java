package org.craftablemc.craftable.network;

import io.netty.buffer.Unpooled;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.craftablemc.craftable.planner.CraftRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 走真实编解码路径检查意图/浏览信封与分配前上限，防止四态丢失、超大或截断包进入使用阶段；不证明客户端拥有执行权限。
 * Exercise real codecs for intent/browse envelopes and pre-allocation limits, catching lost modes/oversized/truncated input without granting client execution authority.
 * 每个Netty缓冲区由创建它的测试在finally中释放，失败路径也不能泄漏引用计数资源。
 * Each creating test releases its Netty buffer in finally, including failures, to avoid reference-counted resource leaks.
 */
class CraftingDetailPayloadTest {
    private static final ResourceLocation ID = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    @Test void browsingEnvelopeRoundTripAndChunkLimits() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        var session = java.util.UUID.randomUUID(); var transfer = java.util.UUID.randomUUID();
        try {
            var request = new CraftingDetailPayloads.BrowseRequest(1, 9, true, session, -1);
            CraftingDetailPayloads.BrowseRequest.CODEC.encode(buffer, request);
            assertEquals(request, CraftingDetailPayloads.BrowseRequest.CODEC.decode(buffer));
            buffer.clear();
            var header = new CraftingWire.SnapshotHeader(session, transfer, 2, 3, 3, 1, CraftingWire.sha256().digest(new byte[]{1,2,3}));
            var lease = new CraftingDetailPayloads.BrowseLease(1, 9, session, 2, 3, 100, "a".repeat(64), header,
                    new org.craftablemc.craftable.config.EnvironmentScanSettings(8, 4, 10, true), true,
                    new org.craftablemc.craftable.config.CraftableServerConfig.CraftingRules(
                            org.craftablemc.craftable.config.CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 256,
                            org.craftablemc.craftable.config.DirectPreparationPolicy.of(
                                    org.craftablemc.craftable.config.DirectPreparationPolicy.Mode.DISABLED, java.util.List.of())));
            CraftingDetailPayloads.BrowseLease.CODEC.encode(buffer, lease);
            assertTrue(buffer.readableBytes() < 1024);
            var decoded = CraftingDetailPayloads.BrowseLease.CODEC.decode(buffer);
            assertEquals(lease.session(), decoded.session()); assertEquals(lease.settings(), decoded.settings());
            assertEquals(lease.rules(), decoded.rules());
            assertArrayEquals(header.digest(), decoded.header().digest());
            buffer.clear();
            var chunk = new CraftingDetailPayloads.BrowseChunk(1, 9, session, transfer, 0, new byte[]{1,2,3});
            CraftingDetailPayloads.BrowseChunk.CODEC.encode(buffer, chunk);
            assertArrayEquals(chunk.data(), CraftingDetailPayloads.BrowseChunk.CODEC.decode(buffer).data());
            assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.BrowseChunk(1,9,session,transfer,133,new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.BrowseChunk(1,9,session,transfer,0,new byte[32768]));
        } finally { buffer.release(); }
    }

    @Test void selectedIntentRoundTrip() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            var intent = new CraftRequest(ID, 3, true, false, CraftRequest.PartialPolicy.CONFIRM,
                    Map.of("0.4", ResourceLocation.withDefaultNamespace("stick")));
            var request = new CraftingDetailPayloads.PreviewRequest(1, 12, intent, "0.4", null);
            CraftingDetailPayloads.PreviewRequest.CODEC.encode(buffer, request);
            assertEquals(request, CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void allFourPersonalPreferencesRoundTripWithoutAnIndependentWhitelist() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (var policy : CraftRequest.PartialPolicy.values()) {
                var intent = new CraftRequest(ID, 3, true, false, policy, Map.of());
                var request = new CraftingDetailPayloads.PreviewRequest(1, 12, intent, "", null);
                CraftingDetailPayloads.PreviewRequest.CODEC.encode(buffer, request);
                assertEquals(request, CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer));
                assertFalse(buffer.isReadable());
                buffer.clear();
                var attempt = new CreateRecipeRequestPayload(ID, 12, 11, false, policy);
                CreateRecipeRequestPayload.STREAM_CODEC.encode(buffer, attempt);
                assertEquals(attempt, CreateRecipeRequestPayload.STREAM_CODEC.decode(buffer));
                assertFalse(buffer.isReadable());
                buffer.clear();
            }
        } finally { buffer.release(); }
    }

    @Test void boundsRejectMalformedDemandAndOversizedFrameBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new CraftingDetailPayloads.PreviewRequest(1, 1,
                CraftRequest.one(ID), "0.99", null));
        assertThrows(IllegalArgumentException.class, () -> new CraftRequest(ID, 65, false, false,
                CraftRequest.PartialPolicy.NEVER, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CraftRequest(ID, 1, false, false,
                CraftRequest.PartialPolicy.NEVER, Map.of("0", ID)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeVarInt(1); buffer.writeVarLong(1); buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void witnessEnvelopeRejectsOversizeAndTruncationBeforeParsing() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeVarInt(65536);
            assertThrows(IllegalArgumentException.class, () -> CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer));
            buffer.clear(); buffer.writeVarInt(65536);
            assertThrows(IllegalArgumentException.class, () -> CraftingDetailPayloads.ConfirmRequest.CODEC.decode(buffer));
            buffer.clear(); buffer.writeVarInt(100); buffer.writeByte(0);
            assertThrows(IndexOutOfBoundsException.class, () -> CraftingDetailPayloads.PreviewRequest.CODEC.decode(buffer));
            buffer.clear(); buffer.writeBoolean(true); buffer.writeVarInt(65537);
            assertThrows(IllegalArgumentException.class, () -> CraftingWire.witness(buffer));
        } finally { buffer.release(); }
    }
}
