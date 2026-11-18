package org.craftablemc.craftable.config;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 检查免确认仅由精确配方ID、模式和世界名单决定；捕获隐藏形状回退、可变别名及个人偏好放宽世界规则。
 * Check that exemptions depend only on exact recipe IDs, mode and the world list; catch hidden shape fallbacks, mutable aliases and relaxed world rules.
 */
class DirectPreparationPolicyTest {
    private static final ResourceLocation PLANKS = ResourceLocation.parse("minecraft:oak_planks");
    private static final ResourceLocation OTHER = ResourceLocation.parse("minecraft:oak_boat");

    @Test void defaultIsAnExplicitBoundedWhitelistAndNotAStructuralFallback() {
        assertTrue(DirectPreparationPolicy.DEFAULT.allowsWithoutConfirmation(PLANKS));
        assertFalse(DirectPreparationPolicy.DEFAULT.allowsWithoutConfirmation(OTHER));
        assertEquals(DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.DEFAULT.mode());
        assertEquals(32, DirectPreparationPolicy.DEFAULT.allowedRecipes().size());
        assertTrue(DirectPreparationPolicy.validRecipeList(DirectPreparationPolicy.DEFAULT.allowedRecipes()));
        assertTrue(DirectPreparationPolicy.DEFAULT.allowedRecipes().contains("minecraft:stick"));
        assertEquals(DirectPreparationPolicy.DEFAULT.allowedRecipes().size(),
                DirectPreparationPolicy.DEFAULT.allowedRecipes().stream().distinct().count());
    }

    @Test void whitelistIsRecipeExactAndAnEmptyListRequiresConfirmationForEverything() {
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                List.of("minecraft:oak_boat"));
        assertTrue(policy.allowsWithoutConfirmation(OTHER));
        assertFalse(policy.allowsWithoutConfirmation(PLANKS));
        assertFalse(policy.allowsWithoutConfirmation(ResourceLocation.parse("minecraft:birch_boat")));
        var empty = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, List.of());
        assertFalse(empty.allowsWithoutConfirmation(PLANKS));
        assertFalse(empty.allowsWithoutConfirmation(OTHER));
        assertNotEquals(empty, DirectPreparationPolicy.DEFAULT);
    }

    @Test void allAndNoneAreModesNotDestructiveListRewrites() {
        var ids = List.of("minecraft:oak_boat");
        var all = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED, ids);
        var none = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION, ids);
        var disabled = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.DISABLED, ids);
        assertTrue(all.allowsWithoutConfirmation(PLANKS));
        assertTrue(all.allowsWithoutConfirmation(OTHER));
        assertFalse(none.allowsWithoutConfirmation(PLANKS));
        assertFalse(none.allowsWithoutConfirmation(OTHER));
        assertFalse(disabled.allowsWithoutConfirmation(PLANKS));
        assertFalse(disabled.allowsWithoutConfirmation(OTHER));
        assertEquals(ids, all.allowedRecipes());
        assertEquals(ids, none.allowedRecipes());
        assertEquals(ids, disabled.allowedRecipes());
        assertTrue(DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, none.allowedRecipes())
                .allowsWithoutConfirmation(OTHER));
    }

    @Test void normalizationIsImmutableSortedAndValueIdentified() {
        var raw = new ArrayList<>(List.of(" minecraft:stick ", "minecraft:oak_planks", "minecraft:stick"));
        var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, raw);
        raw.clear();
        assertEquals(List.of("minecraft:oak_planks", "minecraft:stick"), policy.allowedRecipes());
        assertThrows(UnsupportedOperationException.class, () -> policy.allowedRecipes().add("minecraft:boat"));
        var same = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM,
                List.of("minecraft:stick", "minecraft:oak_planks"));
        assertEquals(policy, same);
        assertEquals(policy.hashCode(), same.hashCode());
        assertNotEquals(policy, DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                same.allowedRecipes()));
    }

    @Test void personalFourModesOnlyTightenAndAlwaysUseTheSameWorldWhitelist() {
        var order = List.of(DirectPreparationPolicy.Mode.DISABLED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION,
                DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.Mode.ALL_SUPPORTED);
        for (var worldMode : order) {
            var world = DirectPreparationPolicy.of(worldMode, List.of(PLANKS.toString()));
            for (var personalMode : order) {
                var effective = world.restrict(personalMode.requestPolicy());
                var expected = order.get(Math.min(order.indexOf(worldMode), order.indexOf(personalMode)));
                assertEquals(expected, effective.mode(), worldMode + "/" + personalMode);
                assertEquals(world.allowedRecipes(), effective.allowedRecipes(), "personal mode invented another list");
                assertEquals(expected == DirectPreparationPolicy.Mode.CUSTOM
                                || expected == DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                        effective.allowsWithoutConfirmation(PLANKS));
                assertEquals(expected == DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                        effective.allowsWithoutConfirmation(OTHER));
                assertSame(effective, effective.restrict(personalMode.requestPolicy()), "restriction must be idempotent");
                assertEquals(worldMode, world.mode(), "personal preference mutated world settings");
            }
        }
        var empty = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of())
                .restrict(org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.WHITELIST);
        assertEquals(DirectPreparationPolicy.Mode.CUSTOM, empty.mode());
        assertFalse(empty.allowsWithoutConfirmation(PLANKS), "empty world whitelist acquired implicit exemptions");
    }

    @Test void sharedSettingAndRequestMappingIsBijectiveAndOldFollowWorldIntentStaysPermissive() {
        for (var mode : DirectPreparationPolicy.Mode.values())
            assertEquals(mode, DirectPreparationPolicy.Mode.fromRequestPolicy(mode.requestPolicy()));
        for (var policy : org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.values())
            assertEquals(policy, DirectPreparationPolicy.Mode.fromRequestPolicy(policy).requestPolicy());
        assertEquals(DirectPreparationPolicy.Mode.ALL_SUPPORTED,
                DirectPreparationPolicy.Mode.fromRequestPolicy(org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE));
        assertThrows(NullPointerException.class, () -> DirectPreparationPolicy.Mode.fromRequestPolicy(null));
        assertThrows(NullPointerException.class, () -> DirectPreparationPolicy.DEFAULT.restrict(null));
    }

    @Test void boundsAndFullNamespaceValidationApplyBeforeDeduplication() {
        // 重复ID也必须占用输入额度，防止先去重让超大外部列表绕过解析/配置边界。
        // Duplicate IDs still spend input allowance; deduplicating first must not let oversized external lists bypass parsing/config bounds.
        assertTrue(DirectPreparationPolicy.validRecipeId("future:machine/recipe"));
        for (var invalid : List.of("oak_planks", "", "minecraft:", ":stick", "Minecraft:stick", "minecraft:two words", "minecraft:a:b")) {
            assertFalse(DirectPreparationPolicy.validRecipeId(invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> DirectPreparationPolicy.of(
                    DirectPreparationPolicy.Mode.CUSTOM, List.of(invalid)), invalid);
        }
        assertFalse(DirectPreparationPolicy.validRecipeId("m:" + "a".repeat(127)));
        assertTrue(DirectPreparationPolicy.validRecipeId("m:" + "a".repeat(126)));
        assertTrue(DirectPreparationPolicy.validRecipeList(java.util.Collections.nCopies(128, "minecraft:stick")));
        assertFalse(DirectPreparationPolicy.validRecipeList(java.util.Collections.nCopies(129, "minecraft:stick")));
        assertFalse(DirectPreparationPolicy.validRecipeList(List.of(1)));
        assertFalse(DirectPreparationPolicy.validRecipeList("minecraft:stick"));
        assertThrows(IllegalArgumentException.class, () -> DirectPreparationPolicy.of(
                DirectPreparationPolicy.Mode.CUSTOM, java.util.Collections.nCopies(129, "minecraft:stick")));
        assertThrows(NullPointerException.class, () -> DirectPreparationPolicy.of(null, List.of()));
    }
}
