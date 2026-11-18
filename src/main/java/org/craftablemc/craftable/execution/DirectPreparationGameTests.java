package org.craftablemc.craftable.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.environment.EnvironmentSnapshotService;
import org.craftablemc.craftable.network.CraftingDetailPayloads;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.recipe.PlanningInput;

/** 确认策略变化仍通过既有搜索、新鲜授权和事务；真实普通配方及冻结功能时钟不代证墙钟性能，
 * 也不增加支持范围或放宽生产准入上限。
 * Consent changes use the existing search, fresh authority and transaction.
 * Genuine ordinary recipes and a frozen functional clock do not claim wall
 * clock performance, add support or weaken the production admission cap. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DirectPreparationGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final ResourceLocation A = id("a"), B = id("b"), TARGET = id("target");

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void unifiedFourStateSettingGatesPartialWorkButNeverCompleteCrafting(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain();
            var list = List.of(A.toString(), B.toString());
            for (var mode : DirectPreparationPolicy.Mode.values()) {
                fixture.policy(mode, list);
                helper.assertValueEqual(CraftableServerConfig.craftingRules().directPreparation().executionPolicy(),
                        mode == DirectPreparationPolicy.Mode.DISABLED ? CraftRequest.PartialPolicy.NEVER
                                : CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                        "world execution gate did not derive from the only registered mode");
                fixture.stock(Items.AMETHYST_SHARD);
                var before = inventoryIdentity(fixture.player);
                var result = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
                var expected = switch (mode) {
                    case DISABLED -> CraftingResultCode.MISSING_INGREDIENTS;
                    case REQUIRE_CONFIRMATION -> CraftingResultCode.CONFIRMATION_REQUIRED;
                    case CUSTOM, ALL_SUPPORTED -> CraftingResultCode.PARTIAL_CREATED;
                };
                helper.assertValueEqual(result.code(), expected, "four-state partial policy " + mode);
                if (expected != CraftingResultCode.PARTIAL_CREATED)
                    helper.assertValueEqual(inventoryIdentity(fixture.player), before, "blocked/confirmation mode consumed input");
                fixture.stock(Items.AMETHYST_SHARD);
                fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
                helper.assertValueEqual(create(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                        CraftingResultCode.CREATED, "partial setting blocked complete crafting: " + mode);
                helper.assertValueEqual(fixture.player.getInventory().countItem(Items.GOLD_INGOT), 1,
                        "complete crafting did not deliver root output: " + mode);
                helper.assertValueEqual(fixture.allowed.get(), list, "switching mode erased whitelist");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void allWorldAndPersonalModePairsUseOnlyTheWorldWhitelist(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain();
            var order = List.of(DirectPreparationPolicy.Mode.DISABLED, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION,
                    DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.Mode.ALL_SUPPORTED);
            for (var list : List.of(List.of(A.toString()), List.of(A.toString(), B.toString()))) {
                for (var world : order) {
                    fixture.policy(world, list);
                    for (var personal : order) {
                        fixture.stock(Items.AMETHYST_SHARD);
                        var before = inventoryIdentity(fixture.player);
                        var effective = order.get(Math.min(order.indexOf(world), order.indexOf(personal)));
                        var result = create(fixture.player, request(true, personal.requestPolicy()));
                        var expected = effective == DirectPreparationPolicy.Mode.DISABLED
                                ? CraftingResultCode.MISSING_INGREDIENTS
                                : effective == DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION
                                        || effective == DirectPreparationPolicy.Mode.CUSTOM && list.size() != 2
                                ? CraftingResultCode.CONFIRMATION_REQUIRED : CraftingResultCode.PARTIAL_CREATED;
                        helper.assertValueEqual(result.code(), expected, "world/personal pair " + world + "/" + personal + "/" + list);
                        if (expected != CraftingResultCode.PARTIAL_CREATED)
                            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "tightened personal preference consumed stock");
                        helper.assertValueEqual(fixture.allowed.get(), list, "personal preference rewrote the world whitelist");
                        helper.assertValueEqual(fixture.mode.get(), world, "personal preference rewrote the world mode");
                        fixture.stock(Items.AMETHYST_SHARD);
                        fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
                        helper.assertValueEqual(create(fixture.player, request(false, personal.requestPolicy())).code(),
                                CraftingResultCode.CREATED, "personal partial preference blocked complete crafting");
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void personalWhitelistRestrictsSearchWitnessAndConfirmationUnderWorldAll(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain();
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(A.toString()));
            fixture.stock(Items.AMETHYST_SHARD);
            fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
            var intent = request(false, CraftRequest.PartialPolicy.WHITELIST);
            var prepared = prepare(fixture.player, intent);
            var frozen = prepared.recipes().planningInput();
            helper.assertTrue(frozen.allowDirectPreparation(frozen.find(A)) && !frozen.allowDirectPreparation(frozen.find(B)),
                    "personal whitelist did not narrow world ALL using its retained ID list");
            var plan = prepared.result().plan().orElseThrow();
            helper.assertTrue(!plan.safePartial(), "personal whitelist search retained blanket world ALL exemptions");
            var snapshot = CraftingSessions.refreshBrowsing(fixture.player);
            var witness = CraftPlan.Witness.from(plan, snapshot);
            var replayed = CraftingService.prepareWitness(fixture.player, intent,
                    EnvironmentSnapshotService.fresh(fixture.player), witness, logical());
            helper.assertTrue(!replayed.result().plan().orElseThrow().safePartial(), "authoritative witness replay lost personal whitelist");
            var rawRecipes = new CraftingRecipes(fixture.player, true);
            helper.assertTrue(rawRecipes.allowDirectPreparation(rawRecipes.find(B)), "world ALL fixture not permissive");
            var checked = CraftWitnessValidator.validate(rawRecipes, intent, witness, snapshot.sources(), logical(), snapshot.maxPlanSteps());
            helper.assertTrue(!checked.safePartial(), "standalone witness validator ignored stricter request preference");
            var searched = new org.craftablemc.craftable.planner.CraftSearch(rawRecipes, intent, snapshot.sources(), logical(), snapshot.maxPlanSteps(), p -> null).run();
            helper.assertTrue(!searched.plan().orElseThrow().safePartial(), "standalone search ignored stricter request preference");
            helper.assertValueEqual(rawRecipes.generation(), prepared.recipes().generation(), "personal preference rebuilt static recipes");

            fixture.stock(Items.AMETHYST_SHARD);
            var before = inventoryIdentity(fixture.player);
            var partial = request(true, CraftRequest.PartialPolicy.WHITELIST);
            helper.assertValueEqual(create(fixture.player, partial).code(), CraftingResultCode.CONFIRMATION_REQUIRED,
                    "unlisted preparation executed without personal confirmation");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unconfirmed personal preparation consumed stock");
            var draft = CraftingService.preview(fixture.player, partial, "", null, -1, logical(), null);
            helper.assertTrue(draft.view().complete() && !draft.token().equals(CraftingDetailPayloads.NO_TOKEN),
                    "personal whitelist blocked a valid review offer");
            helper.assertValueEqual(CraftingService.confirm(fixture.player, draft.token(), null, -1, logical(), null).code(),
                    CraftingResultCode.PARTIAL_CREATED, "confirmed personal-whitelist preparation did not replay consistently");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.COPPER_INGOT), 1,
                    "confirmed personal-whitelist route failed to deliver frontier");
            helper.assertValueEqual(fixture.allowed.get(), List.of(A.toString()), "confirmation rewrote shared world whitelist");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void defaultWhitelistAndExplicitEmptyListUseRecipeIdsNotStructuralFallback(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            var vanilla = new CraftingRecipes(fixture.player, true);
            helper.assertValueEqual(DirectPreparationPolicy.DEFAULT.allowedRecipes().size(), 32, "unexpected shipped whitelist size");
            for (var recipe : DirectPreparationPolicy.DEFAULT.allowedRecipes())
                helper.assertTrue(vanilla.find(ResourceLocation.parse(recipe)) != null,
                        "shipped whitelist does not name a supported real vanilla recipe: " + recipe);
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, DirectPreparationPolicy.DEFAULT.allowedRecipes());
            fixture.recipes(List.of(
                    recipe(ResourceLocation.withDefaultNamespace("oak_planks"), Items.OAK_PLANKS, 4, Items.OAK_LOG),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.OAK_PLANKS, Items.EMERALD)));
            fixture.stock(Items.OAK_LOG);
            var baseline = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(baseline.code(), CraftingResultCode.PARTIAL_CREATED, "default ordinary wood processing");
            helper.assertTrue(baseline.plan() != null && baseline.plan().safePartial(), "default whitelist omitted consent marker");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.OAK_PLANKS), 4, "default preparation output");

            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of());
            fixture.stock(Items.OAK_LOG);
            var before = inventoryIdentity(fixture.player);
            var unlisted = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(unlisted.code(), CraftingResultCode.CONFIRMATION_REQUIRED,
                    "empty whitelist inherited an ordinary wood-processing structural default");
            helper.assertTrue(unlisted.plan() != null && !unlisted.plan().safePartial(), "empty whitelist granted consent");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unlisted ordinary preparation consumed stock");

            // 这是精确配方 ID 确认名单，不是第二套结构分类器。
            // This is an exact recipe-ID consent list, not a second structural classifier.
            fixture.recipes(List.of(
                    recipe(ResourceLocation.withDefaultNamespace("oak_planks"), Items.OAK_PLANKS, 4, Items.DIAMOND),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.OAK_PLANKS, Items.EMERALD)));
            fixture.stock(Items.DIAMOND);
            before = inventoryIdentity(fixture.player);
            var changed = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(changed.code(), CraftingResultCode.CONFIRMATION_REQUIRED, "unlisted vanilla ID acquired consent");
            helper.assertTrue(changed.plan() != null && !changed.plan().safePartial(), "unlisted replacement acquired consent");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unconfirmed replacement consumed stock");
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of("minecraft:oak_planks"));
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.PARTIAL_CREATED, "supported validated recipe did not use its exact whitelist entry");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.DIAMOND), 0, "listed replacement did not extract actual input");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.OAK_PLANKS), 4, "listed replacement did not deliver actual frontier");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void whitelistMustContainEveryActualPreparationStep(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var before = inventoryIdentity(fixture.player);
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString()));
            var missingConsent = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(missingConsent.code(), CraftingResultCode.CONFIRMATION_REQUIRED, "allowing only the first operation allowed the whole chain");
            helper.assertTrue(missingConsent.plan() != null && missingConsent.plan().steps().size() == 2
                    && !missingConsent.plan().safePartial(), "fixture did not check two actual preparation operations");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unapproved chain consumed stock");
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(B.toString()));
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "allowing only the final preparation step allowed the whole chain");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "partly listed chain consumed stock");
            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString(), B.toString()));
            var allowed = create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            helper.assertValueEqual(allowed.code(), CraftingResultCode.PARTIAL_CREATED, "explicit custom direct preparation");
            helper.assertTrue(allowed.plan().safePartial(), "fully allowed chain omitted consent marker");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.AMETHYST_SHARD), 0, "chain did not extract actual input");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.COPPER_INGOT), 1, "chain did not deliver actual frontier");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.IRON_INGOT), 0, "chain leaked an intermediate");
            helper.assertValueEqual(fixture.player.getInventory().countItem(Items.GOLD_INGOT), 0, "partial preparation fabricated root output");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void shortcutModesPreserveCustomListsAndDoNotDisableConfirmedPreparation(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var allowed = List.of(A.toString());
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, allowed);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.PARTIAL_CREATED, "all-supported mode did not permit an explicit supported preparation");
            fixture.stock(Items.AMETHYST_SHARD);
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertValueEqual(fixture.allowed.get(), allowed, "shortcut erased custom allow IDs");
            var before = inventoryIdentity(fixture.player);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "require-confirmation mode allowed direct preparation");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "require-confirmation consumed stock");
            var draft = CraftingService.preview(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE), "", null, -1, logical(), null);
            helper.assertTrue(draft.view().complete() && !draft.token().equals(CraftingDetailPayloads.NO_TOKEN),
                    "require-confirmation mode prohibited a genuine review offer");
            helper.assertValueEqual(CraftingService.confirm(fixture.player, draft.token(), null, -1, logical(), null).code(),
                    CraftingResultCode.PARTIAL_CREATED, "require-confirmation mode prohibited confirmed preparation");
            fixture.mode.set(DirectPreparationPolicy.Mode.CUSTOM);
            helper.assertTrue(CraftableServerConfig.directPreparationPolicy().allowsWithoutConfirmation(A)
                            && !CraftableServerConfig.directPreparationPolicy().allowsWithoutConfirmation(B),
                    "returning to whitelist mode ignored retained membership");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void allSupportedCannotBypassExplicitIntentOrStricterWorldAndPersonalPolicy(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of());
            var before = inventoryIdentity(fixture.player);
            helper.assertValueEqual(create(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "single C implicitly authorized preparation");
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.NEVER)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "personal NEVER was relaxed by all-supported");
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.CONFIRM)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "personal CONFIRM was relaxed by all-supported");
            fixture.mode.set(DirectPreparationPolicy.Mode.DISABLED);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.MISSING_INGREDIENTS, "world DISABLED was relaxed by explicit intent");
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertValueEqual(create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE)).code(),
                    CraftingResultCode.CONFIRMATION_REQUIRED, "world confirmation was relaxed by explicit intent");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "stricter policies consumed stock");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void policyChangeInvalidatesOfferAndDynamicEvidenceWithoutRebuildingStaticKnowledge(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of());
            var first = CraftingSessions.refreshBrowsing(fixture.player);
            var prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            var token = CraftingSessions.offer(fixture.player, prepared, false);
            var staticGeneration = prepared.recipes().generation();
            String fingerprint = prepared.recipes().planningInput().fingerprint();
            var before = inventoryIdentity(fixture.player);
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertValueEqual(CraftingService.confirm(fixture.player, token, null, -1, logical(), null).code(),
                    CraftingResultCode.ENVIRONMENT_CHANGED, "old offer survived policy change");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "stale offer consumed or delivered stock");
            var second = CraftingSessions.refreshBrowsing(fixture.player);
            var current = new CraftingRecipes(fixture.player, true);
            helper.assertTrue(second.resources() > first.resources() && second.recipes() == first.recipes(),
                    "consent rule did not isolate dynamic identity from recipe generation");
            helper.assertTrue(current.generation() == staticGeneration, "consent change rebuilt server recipe index");
            helper.assertValueEqual(current.planningInput().fingerprint(), fingerprint, "consent polluted static recipe fingerprint");

            fixture.policy(DirectPreparationPolicy.Mode.CUSTOM, List.of(A.toString(), B.toString()));
            var listed = CraftingSessions.refreshBrowsing(fixture.player);
            var encoded = org.craftablemc.craftable.network.CraftingWire.snapshot(listed, fixture.player.registryAccess());
            var decoded = org.craftablemc.craftable.network.CraftingWire.snapshot(encoded, fixture.player.registryAccess());
            helper.assertValueEqual(decoded.directPreparation(), CraftableServerConfig.directPreparationPolicy(),
                    "resource snapshot codec lost the authoritative recipe whitelist");
            helper.assertValueEqual(decoded.contentIdentity(), listed.contentIdentity(),
                    "policy/source content identity changed through the actual resource codec");
            helper.assertTrue(listed.resources() > second.resources(), "list-only policy change did not invalidate dynamic evidence");
            helper.assertTrue(new CraftingRecipes(fixture.player, true).generation() == staticGeneration,
                    "list-only policy change rebuilt static recipe knowledge");

            var catalog = new PlanningInput.Catalog();
            var recipes = fixture.player.getServer().getRecipeManager().getRecipes();
            catalog.observe(fixture.player.getServer(), recipes, recipes, fixture.player.registryAccess());
            catalog.advance(Long.MAX_VALUE / 2);
            helper.assertValueEqual(catalog.state(), PlanningInput.Catalog.State.READY, "ordinary value catalog failed to build");
            var custom = catalog.bind(true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT);
            var all = catalog.bind(true, false, java.util.Set.of(), CraftableServerConfig.directPreparationPolicy());
            helper.assertTrue(custom.generation() == all.generation() && catalog.builds() == 1,
                    "binding policy rebuilt client static catalog");
            helper.assertValueEqual(custom.fingerprint(), all.fingerprint(), "policy binding altered static client fingerprint");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void searchAndAuthoritativeWitnessUseTheSameFrozenStepPolicy(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            fixture.player.getInventory().setItem(1, new ItemStack(Items.EMERALD));
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of());
            var prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            var plan = prepared.result().plan().orElseThrow();
            var snapshot = CraftingSessions.refreshBrowsing(fixture.player);
            var witness = CraftPlan.Witness.from(plan, snapshot);
            var replayed = CraftingService.prepareWitness(fixture.player, prepared.request(), EnvironmentSnapshotService.fresh(fixture.player), witness, logical());
            helper.assertTrue(plan.safePartial() && replayed.result().plan().orElseThrow().safePartial(),
                    "all-supported search and actual replay disagree on step consent");
            var frozen = prepared.recipes().planningInput();
            fixture.mode.set(DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
            helper.assertTrue(frozen.allowDirectPreparation(frozen.find(A)), "running search adopted later mutable policy");
            var current = new CraftingRecipes(fixture.player, true);
            helper.assertTrue(!current.allowDirectPreparation(current.find(A)), "new authority ignored changed policy");
            prepared = prepare(fixture.player, request(false, CraftRequest.PartialPolicy.EXPLICIT_SAFE));
            plan = prepared.result().plan().orElseThrow();
            snapshot = CraftingSessions.refreshBrowsing(fixture.player);
            replayed = CraftingService.prepareWitness(fixture.player, prepared.request(), EnvironmentSnapshotService.fresh(fixture.player),
                    CraftPlan.Witness.from(plan, snapshot), logical());
            helper.assertTrue(!plan.safePartial() && !replayed.result().plan().orElseThrow().safePartial(),
                    "require-confirmation search and actual replay disagree on step consent");
            helper.assertValueEqual(prepared.result().code(), CraftingResultCode.CREATED, "consent setting prohibited complete crafting");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void allSupportedAndUnknownConfiguredIdsNeverGrantUnsupportedOrBudgetUnknownWork(GameTestHelper helper) {
        try (var fixture = new Environment(helper)) {
            fixture.chain(); fixture.stock(Items.AMETHYST_SHARD);
            var unsupported = ResourceLocation.fromNamespaceAndPath("pre_m5_unsupported", "recipe");
            fixture.policy(DirectPreparationPolicy.Mode.ALL_SUPPORTED, List.of(unsupported.toString()));
            var before = inventoryIdentity(fixture.player);
            var unsupportedRequest = CraftRequest.one(unsupported).withPartial(true);
            helper.assertValueEqual(create(fixture.player, unsupportedRequest).code(), CraftingResultCode.UNSUPPORTED_RECIPE,
                    "configured ID added unsupported execution capability");
            var clock = new AtomicLong();
            var expired = new SearchBudget(clock::get, 8_000_000L, SearchBudget.MAX_STATES);
            clock.set(8_000_001L);
            helper.assertValueEqual(CraftingService.create(fixture.player, request(true, CraftRequest.PartialPolicy.EXPLICIT_SAFE), false, expired).code(),
                    CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "all-supported converted unknown into partial authority");
            helper.assertValueEqual(inventoryIdentity(fixture.player), before, "unsupported or unknown request changed stock");
            fixture.mode.set(DirectPreparationPolicy.Mode.CUSTOM);
            helper.assertValueEqual(CraftableServerConfig.directPreparationPolicy().allowedRecipes(), List.of(unsupported.toString()),
                    "well-formed unknown ID was not retained for future availability");
            helper.assertTrue(new CraftingRecipes(fixture.player, true).find(unsupported) == null,
                    "unknown configuration created a catalog entry");
        }
        helper.succeed();
    }

    private static CraftRequest request(boolean partial, CraftRequest.PartialPolicy policy) {
        return new CraftRequest(TARGET, 1, partial, false, policy, Map.of());
    }
    private static CraftingService.Prepared prepare(ServerPlayer player, CraftRequest request) {
        return CraftingService.prepare(player, request, EnvironmentSnapshotService.fresh(player), logical());
    }
    private static CraftingService.Outcome create(ServerPlayer player, CraftRequest request) {
        return CraftingService.create(player, request, false, logical());
    }
    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }
    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }
    private static ResourceLocation id(String name) { return ResourceLocation.withDefaultNamespace("pre_m5_consent_" + name); }
    private static RecipeHolder<CraftingRecipe> recipe(ResourceLocation id, Item output, int count, Item... inputs) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var item : inputs) ingredients.add(Ingredient.of(item));
        return new RecipeHolder<>(id, new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(output, count), ingredients));
    }

    private static final class Environment implements AutoCloseable {
        final ServerPlayer player;
        final List<RecipeHolder<?>> originalRecipes;
        final BlockPos table;
        final BlockState originalTable;
        final ModConfigSpec.EnumValue<DirectPreparationPolicy.Mode> mode;
        final ModConfigSpec.ConfigValue<List<String>> allowed;
        final DirectPreparationPolicy.Mode originalMode;
        final List<String> originalAllowed;
        Environment(GameTestHelper helper) {
            player = PlanningTestSupport.player(helper);
            originalRecipes = List.copyOf(player.getServer().getRecipeManager().getRecipes());
            table = player.blockPosition().north();
            originalTable = player.serverLevel().getBlockState(table);
            player.serverLevel().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            mode = CraftableServerConfig.SPEC.getValues().get("crafting.directPreparationMode");
            allowed = CraftableServerConfig.SPEC.getValues().get("crafting.directPreparationAllowedRecipes");
            originalMode = mode.get(); originalAllowed = List.copyOf(allowed.get());
            mode.set(DirectPreparationPolicy.Mode.CUSTOM);
        }
        void recipes(List<RecipeHolder<?>> recipes) { player.getServer().getRecipeManager().replaceRecipes(recipes); }
        void chain() {
            recipes(List.of(recipe(A, Items.IRON_INGOT, 1, Items.AMETHYST_SHARD),
                    recipe(B, Items.COPPER_INGOT, 1, Items.IRON_INGOT),
                    recipe(TARGET, Items.GOLD_INGOT, 1, Items.COPPER_INGOT, Items.EMERALD)));
        }
        void stock(Item item) { player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(item)); }
        void policy(DirectPreparationPolicy.Mode value, List<String> allow) {
            mode.set(value); allowed.set(allow);
        }
        @Override public void close() {
            try { player.getServer().getRecipeManager().replaceRecipes(originalRecipes); }
            finally {
                try {
                    mode.set(originalMode); allowed.set(originalAllowed);
                    player.serverLevel().setBlock(table, originalTable, 2);
                } finally { PlanningTestSupport.remove(player); }
            }
        }
    }
}
