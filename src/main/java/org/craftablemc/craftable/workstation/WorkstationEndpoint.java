package org.craftablemc.craftable.workstation;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.craftablemc.craftable.environment.ContainerEndpoint;

/** 单次快照中某工作台能力的稳定来源；位置复制为不可变值，不持有活方块或容器。
 * Stable source of one workstation capability in a snapshot; position is copied immutably,
 * with no retained live block/container. */
public record WorkstationEndpoint(
        String id,
        WorkstationCapability capability,
        @Nullable BlockPos position) {
    public WorkstationEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        position = position == null ? null : position.immutable();
    }

    /** 提交前核对端点还在已加载区块内且可使用；范围和维度仍由本次服务端扫描固定。
     * Recheck the endpoint in a loaded chunk before commit. The current server scan still fixes range and dimension. */
    public boolean isStillAvailable(ServerPlayer player) {
        if (position == null) return capability == WorkstationCapability.CRAFTING_2X2;
        var level = player.serverLevel();
        var chunk = FurnaceWorkstation.chunk(level, position);
        if (chunk == null || !level.getWorldBorder().isWithinBounds(position)
                || !FurnaceWorkstation.matchesBlock(chunk.getBlockState(position), capability)) return false;
        if (!capability.timedProcessing()) return true;
        if (!FurnaceWorkstation.ticking(level, position) || !FurnaceWorkstation.idle(level, position)) return false;
        if (capability == WorkstationCapability.CAMPFIRE) return player.canInteractWithBlock(position, 4);
        var entity = chunk.getBlockEntities().get(position);
        return entity instanceof BaseContainerBlockEntity container && container.stillValid(player)
                && ContainerEndpoint.canOpenWithoutSideEffects(player, container);
    }
}
