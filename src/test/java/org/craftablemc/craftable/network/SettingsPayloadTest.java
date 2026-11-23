package org.craftablemc.craftable.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.planner.SearchBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 核对只读规则与浏览规则包使用相同有界表示，捕获空名单变默认免确认、退役第二表残留或恶意长度进入解析的回归。
 * Check one bounded rule representation across settings/browse packets, catching empty-list default exemptions, retired second lists and malicious parse lengths.
 * 缓冲区始终局部创建并释放；往返成功只证明协议值保真，不证明材料、世界归属或执行许可。
 * Buffers are locally created/released; round trips prove wire value fidelity, not inputs, world ownership or execution permission.
 */
class SettingsPayloadTest {
    @Test void preparationPolicyRoundTripCanonicalAndBoundedInRulesAndLease() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                java.util.List.of("future:recipe", "minecraft:stick"));
        var rules = new CraftableServerConfig.CraftingRules(
                CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 128, policy, false, 17);
        try {
            var response = new SettingsPayloads.RulesResponse(20, new EnvironmentScanSettings(8, 4, 5, true), rules);
            SettingsPayloads.RulesResponse.CODEC.encode(buffer, response);
            assertEquals(response, SettingsPayloads.RulesResponse.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            buffer.clear();
            var lease = new CraftingDetailPayloads.BrowseLease(1, 20, java.util.UUID.randomUUID(), 2, 3, 100,
                    "a".repeat(64), null, response.scan(), true, rules,
                    org.craftablemc.craftable.workstation.WorkstationCapability.FURNACE.bit()
                            | org.craftablemc.craftable.workstation.WorkstationCapability.SMOKER.bit());
            CraftingDetailPayloads.BrowseLease.CODEC.encode(buffer, lease);
            assertEquals(lease, CraftingDetailPayloads.BrowseLease.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            var ids = java.util.stream.IntStream.range(0, DirectPreparationPolicy.MAX_RECIPES)
                    .mapToObj(i -> "test:" + "x".repeat(118) + i).toList();
            var largest = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED, ids);
            buffer.clear(); SettingsPayloads.writeDirectPreparation(buffer, largest);
            assertTrue(buffer.readableBytes() < CraftingWire.MAX_DETAIL_BYTES);
            assertEquals(largest, SettingsPayloads.readDirectPreparation(buffer));
            assertFalse(buffer.isReadable(), "Single-list codec retained a retired second list");
            buffer.clear();
            var empty = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, java.util.List.of());
            SettingsPayloads.writeDirectPreparation(buffer, empty);
            assertEquals(empty, SettingsPayloads.readDirectPreparation(buffer));
            assertFalse(buffer.isReadable());
            assertFalse(empty.allowsWithoutConfirmation(net.minecraft.resources.ResourceLocation.parse("minecraft:stick")),
                    "Codec must not turn an explicit empty whitelist into default consent");
        } finally { buffer.release(); }
    }

    @Test void malformedPreparationCountsIdsModesAndTruncationFailBeforeUse() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(129);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1); buffer.writeUtf("stick");
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1);
            buffer.writeUtf("test:" + "x".repeat(124)); // 129字符，必须在发布策略前拒绝。 / 129 characters, rejected before publishing a policy.
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeVarInt(99);
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
            buffer.clear(); buffer.writeEnum(DirectPreparationPolicy.Mode.CUSTOM); buffer.writeVarInt(1);
            assertThrows(RuntimeException.class, () -> SettingsPayloads.readDirectPreparation(buffer));
        } finally { buffer.release(); }
    }

    @Test void allFourPartialModesSurviveRulesAndResourcePolicyCodecs() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (var mode : DirectPreparationPolicy.Mode.values()) {
                var policy = DirectPreparationPolicy.of(mode, java.util.List.of("minecraft:stick"));
                var rules = new CraftableServerConfig.CraftingRules(
                        CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 128, policy);
                SettingsPayloads.writeCrafting(buffer, rules);
                assertEquals(rules, SettingsPayloads.readCrafting(buffer));
                assertFalse(buffer.isReadable());
                buffer.clear();
                SettingsPayloads.writeDirectPreparation(buffer, policy);
                assertEquals(policy, SettingsPayloads.readDirectPreparation(buffer));
                assertFalse(buffer.isReadable());
                buffer.clear();
            }
        } finally { buffer.release(); }
    }

    @Test void effectiveRulesRoundTripWithoutAResourceGrant() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            var request = new SettingsPayloads.RulesRequest(19);
            SettingsPayloads.RulesRequest.CODEC.encode(buffer, request);
            assertEquals(request, SettingsPayloads.RulesRequest.CODEC.decode(buffer));
            buffer.clear();
            var response = new SettingsPayloads.RulesResponse(19, new EnvironmentScanSettings(3, 2, 0, false),
                    new CraftableServerConfig.CraftingRules(
                            CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW, 17, 256,
                            DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.DISABLED, java.util.List.of())));
            SettingsPayloads.RulesResponse.CODEC.encode(buffer, response);
            assertTrue(buffer.readableBytes() < CraftingWire.MAX_DETAIL_BYTES);
            assertEquals(response, SettingsPayloads.RulesResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void ruleBoundsAndMalformedMessagesFailClosed() {
        for (int steps : new int[]{0, 257, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CraftableServerConfig.CraftingRules(
                    CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, steps,
                    DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.DISABLED, java.util.List.of())));
        assertThrows(IllegalArgumentException.class, () -> new SettingsPayloads.RulesRequest(-1));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeVarLong(1); SettingsPayloads.writeScan(buffer, new EnvironmentScanSettings(8, 4, 5, true));
            buffer.writeEnum(CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE);
            buffer.writeVarInt(64); buffer.writeVarInt(257);
            assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.RulesResponse.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void hardWitnessCeilingIsIndependentFromTheDefaultWorldLimit() {
        assertEquals(128, SearchBudget.DEFAULT_MAX_STEPS);
        assertEquals(256, SearchBudget.HARD_MAX_STEPS);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        var body = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            body.writeUUID(java.util.UUID.randomUUID()); body.writeVarLong(0); body.writeVarLong(0);
            body.writeVarInt(257); // 分配格子/步骤或解码材料前即拒绝。 / Reject before grid/step allocation or ingredient decoding.
            buffer.writeBoolean(true); buffer.writeVarInt(body.readableBytes()); buffer.writeBytes(body);
            assertThrows(IllegalArgumentException.class, () -> CraftingWire.witness(buffer));
        } finally { body.release(); buffer.release(); }
    }

    @Test void malformedFurnaceBatchLimitsFailInTheSharedRulesDecoder() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            for (int invalid : new int[]{-1, 0, 65, Integer.MAX_VALUE}) {
                buffer.clear();
                buffer.writeEnum(CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE);
                buffer.writeVarInt(64); buffer.writeVarInt(128);
                SettingsPayloads.writeDirectPreparation(buffer, DirectPreparationPolicy.DEFAULT);
                buffer.writeBoolean(false); buffer.writeVarInt(invalid);
                assertThrows(IllegalArgumentException.class, () -> SettingsPayloads.readCrafting(buffer));
            }
        } finally { buffer.release(); }
    }
}
