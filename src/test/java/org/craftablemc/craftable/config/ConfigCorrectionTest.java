package org.craftablemc.craftable.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.craftablemc.craftable.planner.SearchBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接执行注册spec的纠正规则，捕获边界、显式空名单和共享定义漂移，而不是另写一套解析器自证正确。
 * Exercise registered spec correction to catch boundary, explicit-empty-list and shared-definition drift, not a parallel parser validating itself.
 */
class ConfigCorrectionTest {
    @Test void missingSectionsRestoreDefaultsAndNumericLimitsClampToBounds() {
        var config = CommentedConfig.inMemory();
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(128, (int) config.get("crafting.maxPlanSteps"));
        config.set("crafting.maxPlanSteps", 256);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(256, (int) config.get("crafting.maxPlanSteps"));
        config.remove("crafting");
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(128, (int) config.get("crafting.maxPlanSteps"));
        assertEquals(64, (int) config.get("crafting.maxCraftBatch"));
        config.set("crafting.maxPlanSteps", 257);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(256, (int) config.get("crafting.maxPlanSteps"));
        config.set("crafting.maxPlanSteps", 0);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(1, (int) config.get("crafting.maxPlanSteps"));
        assertEquals(SearchBudget.DEFAULT_MAX_STEPS, CraftableServerConfig.DEFAULT_MAX_PLAN_STEPS);
        assertEquals(SearchBudget.HARD_MAX_STEPS, CraftableServerConfig.MAX_MAX_PLAN_STEPS);
    }

    @Test void diagnosticCacheValueSurvivesCorrectionButMissingSectionRestoresDefault() {
        var config = CommentedConfig.inMemory();
        config.set("environment.diagnosticCacheTicks", 17);
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(17, (int) config.get("environment.diagnosticCacheTicks"));
        config.remove("environment");
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(5, (int) config.get("environment.diagnosticCacheTicks"));
    }

    @Test void directPreparationDefaultsAndRegisteredWholeListBoundsAreShared() {
        // 需同时检查整表校验与已注册对象身份：仅逐ID合法，不能证明名单总长有界或两套界面共用同一设置。
        // Check whole-list validation and registered identity: valid individual IDs prove neither total bounds nor one definition shared by both UIs.
        var config = CommentedConfig.inMemory();
        CraftableServerConfig.SPEC.correct(config);
        assertEquals("CUSTOM", config.get("crafting.directPreparationMode").toString());
        assertEquals(DirectPreparationPolicy.DEFAULT.allowedRecipes(), config.get("crafting.directPreparationAllowedRecipes"));
        var values = CraftableServerConfig.directPreparationValues();
        var registered = ConfigSettings.values(CraftableServerConfig.SPEC);
        var mode = registered.stream().filter(value -> value.getPath()
                .equals(java.util.List.of("crafting", "directPreparationMode"))).findFirst().orElseThrow();
        var allowed = registered.stream().filter(value -> value.getPath()
                .equals(java.util.List.of("crafting", "directPreparationAllowedRecipes"))).findFirst().orElseThrow();
        assertTrue(values.stream().anyMatch(value -> value == mode), "shared mode must be the registered value");
        assertTrue(values.stream().anyMatch(value -> value == allowed), "shared list must be the registered value");
        var seen = new java.util.IdentityHashMap<net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?>, Boolean>();
        for (var value : values) {
            assertTrue(registered.stream().anyMatch(candidate -> candidate == value), "unregistered parallel setting");
            assertNull(seen.put(value, true), "duplicated shared setting");
        }
        var metadata = allowed.getSpec();
        assertTrue(metadata.test(java.util.Collections.nCopies(128, "future:unknown")));
        assertFalse(metadata.test(java.util.Collections.nCopies(129, "future:unknown")));
        assertFalse(metadata.test(java.util.List.of("stick")));
        assertFalse(metadata.test(java.util.List.of("minecraft:" + "a".repeat(128))));
        config.set("crafting.directPreparationAllowedRecipes", java.util.Collections.nCopies(129, "future:unknown"));
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(DirectPreparationPolicy.DEFAULT.allowedRecipes(), config.get("crafting.directPreparationAllowedRecipes"));
        config.set("crafting.directPreparationAllowedRecipes", java.util.List.of("stick"));
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(DirectPreparationPolicy.DEFAULT.allowedRecipes(), config.get("crafting.directPreparationAllowedRecipes"));
        config.set("crafting.directPreparationAllowedRecipes", java.util.List.of());
        CraftableServerConfig.SPEC.correct(config);
        assertEquals(java.util.List.of(), config.get("crafting.directPreparationAllowedRecipes"),
                "An explicitly empty whitelist must not regain the shipped defaults");
    }

    @Test void confirmationPolicyAffectsCraftingRulesIdentity() {
        var rules = new CraftableServerConfig.CraftingRules(
                CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 128, DirectPreparationPolicy.DEFAULT);
        assertNotEquals(rules, new CraftableServerConfig.CraftingRules(rules.surplusDelivery(),
                rules.maxBatches(), rules.maxPlanSteps(), DirectPreparationPolicy.of(
                        DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION, java.util.List.of())));
    }

    @Test void executionGateIsDerivedFromTheOnlyWorldMode() {
        for (var mode : DirectPreparationPolicy.Mode.values()) {
            var rules = new CraftableServerConfig.CraftingRules(
                    CraftableServerConfig.SurplusDelivery.REQUIRE_SPACE, 64, 128,
                    DirectPreparationPolicy.of(mode, java.util.List.of("minecraft:stick")));
            assertEquals(mode == DirectPreparationPolicy.Mode.DISABLED
                            ? org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.NEVER
                            : org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                    rules.directPreparation().executionPolicy());
            if (mode == DirectPreparationPolicy.Mode.DISABLED)
                assertFalse(rules.directPreparation().allowsWithoutConfirmation(net.minecraft.resources.ResourceLocation.parse("minecraft:stick")));
        }
    }

    @Test void personalSettingUsesTheSameFourModesButHasNoIndependentRecipeList() {
        var values = ConfigSettings.values(CraftableClientConfig.SPEC);
        assertTrue(values.stream().noneMatch(value -> value.getPath()
                .equals(java.util.List.of("crafting", "directPreparationAllowedRecipes"))));
        var partial = values.stream().filter(value -> value.getPath().equals(java.util.List.of("crafting", "partialPolicy")))
                .findFirst().orElseThrow();
        assertEquals(DirectPreparationPolicy.Mode.ALL_SUPPORTED, partial.getDefault());
        assertEquals(DirectPreparationPolicy.Mode.class, ConfigSettings.type(partial));
        for (var mode : DirectPreparationPolicy.Mode.values()) assertTrue(partial.getSpec().test(mode));
        assertEquals(org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                ((DirectPreparationPolicy.Mode) partial.getDefault()).requestPolicy(), "personal default must follow world rules");
        var config = CommentedConfig.inMemory();
        CraftableClientConfig.SPEC.correct(config);
        assertEquals("ALL_SUPPORTED", config.get("crafting.partialPolicy").toString());
        assertFalse(config.contains("crafting.directPreparationAllowedRecipes"));
        assertEquals(350, (int) config.get("crafting.doublePressMillis"));
    }
}
