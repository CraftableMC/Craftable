package org.craftablemc.craftable.menu.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只重定位原槽，不替换 ResultSlot 或合成回调；由 Mixin 绑定，不依赖显式构造调用。
 * Reposition existing slots without replacing ResultSlot/crafting callbacks; Mixin binds this interface without explicit construction. */
@Mixin(Slot.class)
public interface SlotPositionAccessor {
    @Mutable @Accessor("x") void craftable$setX(int x);
    @Mutable @Accessor("y") void craftable$setY(int y);
}
