package org.craftablemc.craftable.api;

/**
 * 各前端共用的三态显示结果，不代表持有服务端执行令牌。
 * The three frontend-visible outcomes do not imply possession of a server execution token.
 */
public enum CraftingStatus {
    CRAFTABLE,
    PARTIAL,
    BLOCKED
}
