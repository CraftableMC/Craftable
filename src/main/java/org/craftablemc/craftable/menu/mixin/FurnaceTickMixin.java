package org.craftablemc.craftable.menu.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.craftablemc.craftable.execution.FurnaceJobs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 借绑定炉本身的实际 tick 推进订单，不读取或写入原版燃烧进度。
 * Drive an order from the bound furnace's own tick, without changing native fuel or cooking progress. */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class FurnaceTickMixin {
    @Inject(method = "serverTick", at = @At("TAIL"))
    private static void craftable$advanceOrder(Level level, BlockPos position, BlockState state,
            AbstractFurnaceBlockEntity furnace, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) FurnaceJobs.processingTick(serverLevel, position, furnace);
    }
}
