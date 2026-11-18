package org.craftablemc.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.craftablemc.craftable.api.CraftingStatus;
import org.junit.jupiter.api.Test;

/**
 * 显示优先级的反例：另一材质可做不能替未知下结论、覆盖当前部分准备或冒充当前可合成。
 * Counterexamples for display priority: an alternative cannot decide an unknown target, hide partial preparation or pretend the target is craftable.
 * 实际轮播集合、材质身份和底图渲染由真实客户端场景验证。
 * The real client scenario checks the rotation, material identity and rendered base sprite.
 */
class RecipeButtonVisualStateTest {
    @Test void unknownTargetCannotBorrowAnotherMaterialsSuccess() {
        for (var status : CraftingStatus.values()) {
            assertEquals(RecipeButtonVisualState.UNKNOWN, RecipeButtonVisualState.from(status, false, true));
            assertEquals(RecipeButtonVisualState.UNKNOWN, RecipeButtonVisualState.from(status, false, false));
        }
    }

    @Test void currentCompleteOrPartialResultKeepsItsOwnColor() {
        for (boolean alternative : new boolean[] {false, true}) {
            assertEquals(RecipeButtonVisualState.CRAFTABLE,
                    RecipeButtonVisualState.from(CraftingStatus.CRAFTABLE, true, alternative));
            assertEquals(RecipeButtonVisualState.PARTIAL,
                    RecipeButtonVisualState.from(CraftingStatus.PARTIAL, true, alternative));
        }
    }

    @Test void alternativeIsADistinctHintRatherThanACraftableCurrentTarget() {
        assertEquals(RecipeButtonVisualState.BLOCKED,
                RecipeButtonVisualState.from(CraftingStatus.BLOCKED, true, false));
        assertEquals(RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE,
                RecipeButtonVisualState.from(CraftingStatus.BLOCKED, true, true));
        assertNotEquals(RecipeButtonVisualState.CRAFTABLE,
                RecipeButtonVisualState.from(CraftingStatus.BLOCKED, true, true));
    }

    @Test void blockedAndAlternativeRemainVisuallyDifferentOnTheSameBase() {
        var red = RecipeButtonVisualState.BLOCKED;
        var pink = RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE;
        assertTrue(red.green() < pink.green() && red.blue() < pink.blue(),
                "A neutral base must still distinguish red from the lighter alternative hint");
    }
}
