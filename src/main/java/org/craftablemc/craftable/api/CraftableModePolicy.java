package org.craftablemc.craftable.api;

import net.minecraft.world.level.GameType;

/**
 * 输入、预览和服务端执行共用模式边界；客户端通过不替代服务端再次校验。
 * Mode boundary shared by input, preview and execution; client acceptance never replaces server validation.
 */
public final class CraftableModePolicy {
    private CraftableModePolicy() {}
    public static boolean allows(GameType mode) {
        return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
    }
}
