package org.craftablemc.craftable.client.recipebook;

import org.craftablemc.craftable.api.CraftingStatus;

/**
 * 按钮背景的显示含义与颜色；其他材质可做只是提示，不改变当前配方、三态结论或执行权限。
 * Background meanings and colors; a craftable alternative is only a hint, never a target change, verdict or execution grant.
 * 配合统一的中性底图使用，避免原版整组底图与当前材质着色相乘后偶然产生另一种含义。
 * Use a neutral base sprite so vanilla collection-wide backgrounds cannot accidentally change the current output's color meaning.
 */
public enum RecipeButtonVisualState {
    UNKNOWN(1F, 1F, 1F),
    CRAFTABLE(0.65F, 1F, 0.65F),
    PARTIAL(0.84F, 0.75F, 0.46F),
    BLOCKED(1F, 0.35F, 0.35F),
    OTHER_VARIANT_CRAFTABLE(1F, 0.7F, 0.75F);

    private final float red, green, blue;

    RecipeButtonVisualState(float red, float green, float blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }

    /** 未知不能借另一材质的成功变成当前否定；当前可部分准备仍优先显示黄色。
     * Unknown cannot borrow an alternative's success to become a negative verdict; current partial preparation retains yellow. */
    static RecipeButtonVisualState from(CraftingStatus current, boolean hasConclusion, boolean otherVariantCraftable) {
        if (!hasConclusion) return UNKNOWN;
        return switch (current) {
            case CRAFTABLE -> CRAFTABLE;
            case PARTIAL -> PARTIAL;
            case BLOCKED -> otherVariantCraftable ? OTHER_VARIANT_CRAFTABLE : BLOCKED;
        };
    }
}
