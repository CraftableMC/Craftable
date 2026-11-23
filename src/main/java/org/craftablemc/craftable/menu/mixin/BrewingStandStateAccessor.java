package org.craftablemc.craftable.menu.mixin;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读原版酿造计时器，避免扫描或推进虚拟订单时序列化整组槽位。
 * Read only the native brew timer, avoiding slot serialization during scans and order ticks. */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingStandStateAccessor {
    @Accessor("brewTime")
    int craftable$brewTime();
}
