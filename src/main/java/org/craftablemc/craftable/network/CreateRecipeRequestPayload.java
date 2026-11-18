package org.craftablemc.craftable.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.Craftable;

/**
 * 仅表示客户端合成意图；服务端解析全部资源/工作站，并以世界规则收紧allowDrops和partialPolicy。
 * Client crafting intent only; the server resolves every resource/workstation and restricts allowDrops/partialPolicy by world rules.
 * precedingPress只关联服务端记录的前次结果，不能由客户端自行宣称缺料或双击已获准。
 * precedingPress only links the previous server-recorded result; clients cannot assert missing inputs or approved double-press intent.
 */
public record CreateRecipeRequestPayload(ResourceLocation recipeId, long requestId, long precedingPress,
        boolean allowDrops, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy partialPolicy) implements CustomPacketPayload {
    public static final Type<CreateRecipeRequestPayload> TYPE = new Type<>(Craftable.id("create_recipe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateRecipeRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buffer) -> {
                        buffer.writeResourceLocation(payload.recipeId());
                        buffer.writeVarLong(payload.requestId());
                        buffer.writeVarLong(payload.precedingPress());
                        buffer.writeBoolean(payload.allowDrops());
                        buffer.writeEnum(payload.partialPolicy());
                    },
                    buffer -> new CreateRecipeRequestPayload(
                            buffer.readResourceLocation(), buffer.readVarLong(), buffer.readVarLong(),
                            buffer.readBoolean(), buffer.readEnum(org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.class)));

    @Override
    public Type<CreateRecipeRequestPayload> type() {
        return TYPE;
    }
}
