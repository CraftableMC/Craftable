package org.craftablemc.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.craftablemc.craftable.Craftable;

/**
 * 请求打开玩家环境菜单，不表示附近必有工作台；处理器须按当前玩家、模式及新扫描重新确认。
 * Request to open the player's environment menu, not an assertion that a table exists; the handler rechecks current player, mode and a fresh scan.
 */
public record OpenInventoryRequestPayload(long requestId) implements CustomPacketPayload {
    public static final Type<OpenInventoryRequestPayload> TYPE = new Type<>(Craftable.id("open_inventory"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenInventoryRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec((p, b) -> b.writeVarLong(p.requestId()), b -> new OpenInventoryRequestPayload(b.readVarLong()));
    @Override public Type<OpenInventoryRequestPayload> type() { return TYPE; }
}
