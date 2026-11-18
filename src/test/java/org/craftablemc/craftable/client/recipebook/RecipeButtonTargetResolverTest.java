package org.craftablemc.craftable.client.recipebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.junit.jupiter.api.Test;

/**
 * 固定配方变体选择的优先级，防止容量失败或未知结论抢占已有可合成/可部分合成目标；这里只检查纯选择，不执行合成。
 * Pin variant selection so capacity failures/unknown verdicts cannot displace craftable/partial targets; this checks pure selection, not execution.
 */
class RecipeButtonTargetResolverTest {
    @Test
    void craftableEquivalentRouteWinsWithinTheDisplayedOutputGroup() {
        List<CraftingStatus> statuses = List.of(CraftingStatus.CRAFTABLE, CraftingStatus.BLOCKED);

        assertEquals(CraftingStatus.CRAFTABLE, RecipeButtonTargetResolver.strongestStatus(statuses));
        assertEquals(0, RecipeButtonTargetResolver.preferredIndex(statuses, List.of(false, false), 1));
    }

    @Test
    void fallsBackToVanillaCraftableVariantBeforeServerStatusArrives() {
        assertEquals(
                1,
                RecipeButtonTargetResolver.preferredIndex(
                        List.of(CraftingStatus.BLOCKED, CraftingStatus.BLOCKED),
                        List.of(false, true),
                        0));
    }

    @Test
    void rejectsMisalignedVariantState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RecipeButtonTargetResolver.preferredIndex(
                        List.of(CraftingStatus.BLOCKED), List.of(), 0));
    }

    @Test void materialSatisfiedRouteWinsOverMissingEquivalentWhenDeliveryIsBlocked() {
        assertEquals(1, RecipeButtonTargetResolver.preferredIndex(
                List.of(CraftingStatus.BLOCKED, CraftingStatus.BLOCKED, CraftingStatus.BLOCKED),
                List.of(false, false, false),
                List.of(CraftingResultCode.MISSING_INGREDIENTS, CraftingResultCode.NO_OUTPUT_SPACE,
                        CraftingResultCode.SEARCH_BUDGET_EXCEEDED), 0));
    }

    @Test void successfulAndUsefulRoutesStillWinOverCapacityFailure() {
        for (var status : List.of(CraftingStatus.CRAFTABLE, CraftingStatus.PARTIAL))
            assertEquals(1, RecipeButtonTargetResolver.preferredIndex(
                    List.of(CraftingStatus.BLOCKED, status), List.of(false, false),
                    List.of(CraftingResultCode.NO_OUTPUT_SPACE,
                            status == CraftingStatus.CRAFTABLE ? CraftingResultCode.CREATED : CraftingResultCode.PARTIAL_CREATED), 0));
    }

    @Test void missingAndUnknownDoNotBecomePositiveOrChangeTheFallback() {
        assertEquals(1, RecipeButtonTargetResolver.preferredIndex(
                List.of(CraftingStatus.BLOCKED, CraftingStatus.BLOCKED), List.of(false, false),
                List.of(CraftingResultCode.MISSING_INGREDIENTS, CraftingResultCode.SEARCH_BUDGET_EXCEEDED), 1));
        assertEquals(1, RecipeButtonTargetResolver.preferredIndex(
                List.of(CraftingStatus.BLOCKED, CraftingStatus.BLOCKED), List.of(false, true),
                List.of(CraftingResultCode.MISSING_INGREDIENTS, CraftingResultCode.MISSING_INGREDIENTS), 0));
    }

    @Test void rejectsMisalignedReasons() {
        assertThrows(IllegalArgumentException.class, () -> RecipeButtonTargetResolver.preferredIndex(
                List.of(CraftingStatus.BLOCKED), List.of(false), List.of(), 0));
    }
}
