package org.craftablemc.craftable.environment;

/** 区分环境来源所有权，尤其不能把网格/外部材料当成主背包空位。
 * Distinguish environment source ownership, especially grid/external inputs from main-inventory capacity. */
public enum EndpointKind {
    PLAYER,
    MENU_INPUT,
    ENDER_CHEST,
    BLOCK
}
