package org.craftablemc.craftable.menu.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.craftablemc.craftable.execution.FurnaceJobs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只在点燃的篝火加工刻结束后推进订单，熄灭时原版执行的冷却刻不会推进。
 * Advance orders after a lit campfire's cooking tick. The unlit cooldown tick never advances work. */
@Mixin(CampfireBlockEntity.class)
abstract class CampfireTickMixin {
    @Inject(method = "cookTick", at = @At("TAIL"))
    private static void craftable$advanceOrder(Level level, BlockPos position, BlockState state,
            CampfireBlockEntity campfire, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) FurnaceJobs.processingTick(serverLevel, position, campfire);
    }
}
