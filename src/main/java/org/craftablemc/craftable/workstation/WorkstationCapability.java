package org.craftablemc.craftable.workstation;

/** 环境快照理解的原版工作台能力；能力存在不代表玩家或材料获得合成授权。
 * Vanilla workstation abilities understood by environment snapshots; presence grants neither player nor material authority. */
public enum WorkstationCapability {
    CRAFTING_2X2,
    CRAFTING_3X3,
    FURNACE,
    BLAST_FURNACE,
    SMOKER,
    CAMPFIRE,
    STONECUTTER,
    SMITHING_TABLE,
    BREWING_STAND;

    // 已有顺序也是同步能力位的顺序；新增值只能追加，不能重排。
    // Existing order defines the synchronized bits. Append new values; never reorder them.
    public static final int COOKING_MASK = FURNACE.bit() | BLAST_FURNACE.bit() | SMOKER.bit() | CAMPFIRE.bit();
    public static final int PROCESSING_MASK = COOKING_MASK | STONECUTTER.bit() | SMITHING_TABLE.bit() | BREWING_STAND.bit();

    public int bit() { return 1 << ordinal(); }

    public boolean cooking() { return (bit() & COOKING_MASK) != 0; }

    public boolean timedProcessing() { return cooking() || this == BREWING_STAND; }
}
