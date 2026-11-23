package org.craftablemc.craftable.menu.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.craftablemc.craftable.execution.FurnaceJobs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 虚拟酿造订单跟随绑定工作站的原版 tick，其计时和燃料独立结算。
 * Virtual brewing follows the bound stand's native tick and accounts for time and fuel independently. */
@Mixin(BrewingStandBlockEntity.class)
abstract class BrewingStandTickMixin {
    @Inject(method = "serverTick", at = @At("TAIL"))
    private static void craftable$advanceOrder(Level level, BlockPos position, BlockState state,
            BrewingStandBlockEntity stand, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) FurnaceJobs.processingTick(serverLevel, position, stand);
    }
}
