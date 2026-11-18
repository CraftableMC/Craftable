package org.craftablemc.craftable.workstation;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

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
}
