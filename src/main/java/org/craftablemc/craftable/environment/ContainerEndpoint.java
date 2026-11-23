package org.craftablemc.craftable.environment;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 受槽范围约束的短期活容器视图，不能当作长期授权；提交前重新扫描并验证内容。
 * isStillValid 只检查仍可用，不替代扫描时的锁、范围和区块检查。
 * Short-lived, slot-bounded live view, not durable authority; execution rescans and verifies contents before committing.
 * isStillValid checks continued usability, not the scan's lock, range and loaded-chunk checks.
 */
public record ContainerEndpoint(
        String id,
        EndpointKind kind,
        @Nullable BlockPos position,
        Container container,
        int firstSlot,
        int slotCount) {

    public ContainerEndpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(container, "container");
        position = position == null ? null : position.immutable();
        if (firstSlot < 0 || slotCount < 0 || firstSlot + slotCount > container.getContainerSize()) {
            throw new IllegalArgumentException("Endpoint slot range is outside its container");
        }
    }

    public boolean containsSlot(int slot) {
        return slot >= firstSlot && slot < firstSlot + slotCount;
    }

    public boolean isStillValid(ServerPlayer player) {
        return (kind != EndpointKind.BLOCK && kind != EndpointKind.MENU_INPUT) || container.stillValid(player);
    }

    /** 复用原版的锁与钥匙比较，避免后台扫描或复验发消息、播声音。
     * Match vanilla's lock/key check without emitting messages or sounds during scans or revalidation. */
    public static boolean canOpenWithoutSideEffects(ServerPlayer player, BlockEntity blockEntity) {
        if (!(blockEntity instanceof BaseContainerBlockEntity baseContainer)) return true;
        LockCode lock = baseContainer.collectComponents().getOrDefault(DataComponents.LOCK, LockCode.NO_LOCK);
        return player.isSpectator() || lock.unlocksWith(player.getMainHandItem());
    }
}
