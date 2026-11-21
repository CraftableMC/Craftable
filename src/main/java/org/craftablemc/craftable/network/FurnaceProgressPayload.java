package org.craftablemc.craftable.network;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;

/**
 * 玩家独立的计时加工显示快照，不包含来源、执行令牌或新的服务端任务状态。
 * Per-player display snapshot for timed processing, containing no sources, execution tokens or separate server job state.
 * 维度表示接收者当前显示上下文，不限制任务所有权；任务由服务端负责跨维度暂停、恢复与重新同步。
 * The dimension identifies the recipient's current display context, not job ownership; the server owns cross-dimension pause/resume and resynchronization.
 */
public record FurnaceProgressPayload(ResourceLocation dimension, Mode mode, int elapsed, int total)
        implements CustomPacketPayload {
    public enum Mode { NONE, RUNNING, UNLOADED, BUSY, READY, REFUND, EXTINGUISHED }

    public FurnaceProgressPayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(mode, "mode");
        if (elapsed < 0 || total < 0 || elapsed > total)
            throw new IllegalArgumentException("Invalid furnace progress");
    }

    public static final Type<FurnaceProgressPayload> TYPE = new Type<>(Craftable.id("furnace_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceProgressPayload> CODEC = CustomPacketPayload.codec(
            (payload, buffer) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeEnum(payload.mode());
                buffer.writeVarInt(payload.elapsed());
                buffer.writeVarInt(payload.total());
            },
            buffer -> new FurnaceProgressPayload(buffer.readResourceLocation(), buffer.readEnum(Mode.class),
                    buffer.readVarInt(), buffer.readVarInt()));

    /** 仅通知任务所属玩家；发送不授予执行权，不替代任务的提交、交付或退款处理。
     * Notify only the job's player; sending grants no execution authority and does not commit, deliver or refund a job. */
    public static void send(ServerPlayer player, Mode mode, int elapsed, int total) {
        PacketDistributor.sendToPlayer(player, new FurnaceProgressPayload(player.level().dimension().location(), mode, elapsed, total));
    }

    @Override public Type<FurnaceProgressPayload> type() { return TYPE; }
}
