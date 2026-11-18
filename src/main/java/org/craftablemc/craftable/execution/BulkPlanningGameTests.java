package org.craftablemc.craftable.execution;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.MaterialUpperBound;
import org.craftablemc.craftable.planner.ResourceLedger;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.planner.SearchResult;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/** 真实原版多批次配方；冻结 CPU 时钟以单独验证状态/内存上限，不代证墙钟性能。
 * Real vanilla bulk recipes: a frozen CPU clock isolates state/memory limits,
 * without claiming wall-clock performance. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BulkPlanningGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void singleStackOfLogsCanProduceThirtyTwoChests(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, false)) {
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var before = inventoryIdentity(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertValueEqual(materialBound(catalog, snapshot, chest(32)).orElse(-1), 32,
                    "64 original logs did not certify the necessary 32-chest material ceiling");
            var result = search(catalog, snapshot, chest(32), logical());
            var plan = fullPlan(helper, result, 32);
            helper.assertValueEqual(plan.steps().size(), 96, "32 chests lost ordinary intermediate steps");
            helper.assertValueEqual(extracted(plan, Items.OAK_LOG), 64, "32 chests require exactly 64 original logs");
            helper.assertValueEqual(extracted(plan, Items.BIRCH_LOG), 0, "single-wood fixture fabricated a second source");
            helper.assertTrue(plan.surplus().isEmpty(), "whole chest batches fabricated plank surplus");
            helper.assertTrue(catalog.validate(plan), "32 chest route did not replay against real vanilla recipes");
            helper.assertValueEqual(inventoryIdentity(player), before, "read-only bulk planning consumed its source");
            logRealAllowances(catalog, snapshot, chest(32));
            var outcome = CraftingService.create(player, chest(32), false, logical());
            helper.assertValueEqual(outcome.code(), CraftingResultCode.CREATED, "service failed the reachable 32 chest route");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 0, "bulk transaction left logs behind");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 32, "bulk transaction delivered the wrong chest count");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 0, "bulk transaction leaked intermediate planks");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void mixedLogStacksCanProduceFortyTwoChestsWithinTheStepLimit(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, true)) {
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var before = inventoryIdentity(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertValueEqual(materialBound(catalog, snapshot, chest(42)).orElse(-1), 64,
                    "mixed logs were double-counted or one wood species was omitted from the material ceiling");
            var result = search(catalog, snapshot, chest(42), logical());
            var plan = fullPlan(helper, result, 42);
            helper.assertValueEqual(plan.steps().size(), 126, "mixed route changed the existing 128-step accounting");
            int oak = extracted(plan, Items.OAK_LOG), birch = extracted(plan, Items.BIRCH_LOG);
            helper.assertValueEqual(oak + birch, 84, "42 chest route duplicated mixed logs");
            helper.assertTrue(oak <= 64 && birch <= 64 && (oak == 64 || birch == 64),
                    "unconstrained preferred route did not exhaust one real stack before the other");
            helper.assertTrue(plan.surplus().isEmpty() && catalog.validate(plan), "mixed full route lost recipe/material accounting");
            helper.assertValueEqual(inventoryIdentity(player), before, "mixed bulk planning mutated actual inventory");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void existingPlanksAllowFortyThreeChestsWithoutProducerSteps(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, false)) {
            var player = environment.player;
            // 使用六叠真实库存，不用超大合成物品堆。 / Six real inventory stacks, not an oversized synthetic stack.
            for (int slot = 0; slot < 6; slot++)
                player.getInventory().setItem(slot, new ItemStack(Items.OAK_PLANKS, slot == 5 ? 24 : 64));
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var before = inventoryIdentity(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertValueEqual(materialBound(catalog, snapshot, chest(43)).orElse(-1), 43,
                    "material ceiling charged existing planks as raw logs");
            var plan = fullPlan(helper, search(catalog, snapshot, chest(43), logical()), 43);
            helper.assertValueEqual(plan.steps().size(), 43, "existing planks were charged fictitious producer operations");
            helper.assertValueEqual(extracted(plan, Items.OAK_PLANKS), 344, "43 chests require exactly the existing 344 planks");
            helper.assertValueEqual(extracted(plan, Items.OAK_LOG), 0, "existing-plank route fabricated raw logs");
            helper.assertTrue(plan.surplus().isEmpty() && catalog.validate(plan), "43 existing-plank chests failed real vanilla replay");
            helper.assertValueEqual(inventoryIdentity(player), before, "existing-plank planning consumed inventory");
            var outcome = CraftingService.create(player, chest(43), false, logical());
            helper.assertValueEqual(outcome.code(), CraftingResultCode.CREATED, "step floor refused the complete existing-plank transaction");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 0, "existing-plank transaction left inputs behind");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 43, "existing-plank transaction output count");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void changedPlankYieldKeepsTheStepFloorConservative(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try (var environment = new BulkEnvironment(helper, false)) {
            // 使用普通数据包语义而非箱子/原木特例求解器：一根原木产八块木板，故 43 个箱子仅需 86 步。
            // Ordinary data-pack semantics, not a special-case chest/log solver:
            // one log now makes eight planks, so 43 chests need only 86 steps.
            var id = ResourceLocation.withDefaultNamespace("oak_planks");
            var modified = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
            modified.removeIf(holder -> holder.id().equals(id));
            modified.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id,
                    new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                            new ItemStack(Items.OAK_PLANKS, 8), net.minecraft.core.NonNullList.of(
                                    net.minecraft.world.item.crafting.Ingredient.EMPTY,
                                    net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_LOG)))));
            manager.replaceRecipes(modified);
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var before = inventoryIdentity(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertValueEqual(materialBound(catalog, snapshot, chest(43)).orElse(-1), 64,
                    "material certificate ignored the changed ordinary plank yield");
            var plan = fullPlan(helper, search(catalog, snapshot, chest(43), logical()), 43);
            helper.assertValueEqual(plan.steps().size(), 86, "changed-yield route lost ordinary operation accounting");
            helper.assertValueEqual(extracted(plan, Items.OAK_LOG), 43, "changed-yield route consumed the vanilla four-plank quantity");
            helper.assertTrue(plan.surplus().isEmpty() && catalog.validate(plan), "changed-yield route failed authoritative recipe replay");
            helper.assertValueEqual(inventoryIdentity(player), before, "changed-yield planning consumed its source");
        } finally { manager.replaceRecipes(original); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void requestsAboveTheStepBoundaryStayUnknownAndCannotPrepare(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, true)) {
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertValueEqual(materialBound(catalog, snapshot, chest(43)).orElse(-1), 64,
                    "material ceiling incorrectly disguised the independent 128-step limit");
            var before = inventoryIdentity(player);
            for (int count : List.of(43, 64)) {
                var budget = logical();
                var intent = chest(count).withPartial(true);
                var search = solver(catalog, snapshot, intent, budget);
                long started = System.nanoTime();
                var result = search.run();
                log(count, result, System.nanoTime() - started);
                helper.assertValueEqual(result.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                        "step-limited bulk request became a material/capacity denial");
                helper.assertTrue(budget.truncated() && !result.completeSearch() && result.plan().isEmpty(),
                        "more than 128 steps escaped as a complete plan or negative proof");
                helper.assertTrue(search.fullEvidence() == null && search.partialSearches() == 0,
                        "omitted full branches authorized a partial bulk phase");
                helper.assertValueEqual(result.visitedStates(), 0, "necessary step floor waited for state expansion");
                helper.assertValueEqual(budget.states(), 0, "necessary step floor spent the finite state allowance");
                helper.assertValueEqual(search.initializations(), 1, "limited bulk request restarted its root");
                var outcome = CraftingService.create(player, intent, false, logical());
                helper.assertValueEqual(outcome.code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                        "direct partial action bypassed the bulk structural limit");
                helper.assertTrue(outcome.plan() == null && outcome.missing().isEmpty() && outcome.drops().isEmpty(),
                        "unknown bulk request exposed executable/authoritative partial data");
                helper.assertValueEqual(inventoryIdentity(player), before, "unknown bulk request consumed either log stack");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void materialCertificateRejectsGainReturnsAndUnsupportedSemantics(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        try (var environment = new BulkEnvironment(helper, false)) {
            var player = environment.player;
            var modified = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
            // 两块木板可还原原木再产四块木板，故给原始原木固定权重的证明必须校验失败。
            // Two planks can recover a log which then produces four planks:
            // assigning the original raw log a fixed value must fail validation.
            modified.add(certificateRecipe("gain_log", Items.OAK_LOG, 1, Items.OAK_PLANKS, Items.OAK_PLANKS));
            manager.replaceRecipes(modified);
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertTrue(materialBound(catalog, snapshot, chest(32)).isEmpty(),
                    "gaining conversion cycle produced a counterfeit finite material ceiling");

            manager.replaceRecipes(List.of(
                    certificateRecipe("return_root", Items.DIAMOND, 1, Items.BUCKET),
                    certificateRecipe("return_bucket", Items.COOKED_BEEF, 1, Items.MILK_BUCKET)));
            player.getInventory().setItem(0, new ItemStack(Items.MILK_BUCKET));
            snapshot = CraftingSessions.refreshBrowsing(player);
            catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertTrue(materialBound(catalog, snapshot, CraftRequest.one(certificateId("return_root"))).isEmpty(),
                    "positive bucket remainder was treated as an absent or free material");

            // 序列化器身份精确相同，仍不足以证明任意子类的依赖世界的产物/匹配行为。
            // Exact serializer identity is insufficient to establish an
            // arbitrary subclass's world-dependent output/matching behavior.
            var opaque = new net.minecraft.world.item.crafting.ShapelessRecipe("",
                    net.minecraft.world.item.crafting.CraftingBookCategory.MISC, new ItemStack(Items.OAK_LOG),
                    net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                            net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_PLANKS))) {};
            modified = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(original);
            modified.add(new net.minecraft.world.item.crafting.RecipeHolder<>(certificateId("opaque"), opaque));
            manager.replaceRecipes(modified);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            snapshot = CraftingSessions.refreshBrowsing(player);
            catalog = new CraftingRecipes(player, snapshot.workbench());
            helper.assertTrue(!catalog.planningInput().fullySupported() && materialBound(catalog, snapshot, chest(32)).isEmpty(),
                    "unsupported producer semantics certified an authoritative upper quantity");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 64,
                    "failed optional material certificates consumed actual sources");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 0,
                    "optional upper quantity proof manufactured an output");
        } finally { manager.replaceRecipes(original); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void materialCertificateRequiresAnInitialClosedScopeAndUnspentAllowance(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, false)) {
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var input = new CraftingRecipes(player, snapshot.workbench()).planningInput();
            var sources = snapshot.sources();
            var reachable = presenceClosure(input, sources);
            helper.assertTrue(MaterialUpperBound.compute(input, chest(32), new ResourceLedger(sources),
                    java.util.Set.of(Items.OAK_LOG), logical()).isEmpty(),
                    "incomplete presence closure certified missing upstream material");
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var spent = new SearchBudget(clock::get, 1, SearchBudget.MAX_STATES);
            clock.set(1);
            helper.assertTrue(MaterialUpperBound.compute(input, chest(32), new ResourceLedger(sources), reachable, spent).isEmpty()
                    && spent.exhausted(), "material certificate replenished an exhausted search allowance");
            var consumed = new ResourceLedger(sources);
            consumed.consume(new ItemStack(Items.OAK_LOG), -1, null);
            helper.assertTrue(MaterialUpperBound.compute(input, chest(32), consumed, reachable, logical()).isEmpty(),
                    "a branch-local consumed ledger was accepted as initial stock");
            var pinned = new CraftRequest(chest(32).recipe(), 32, false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE,
                    java.util.Map.of("0.0", ResourceLocation.withDefaultNamespace("birch_planks")));
            helper.assertValueEqual(MaterialUpperBound.compute(input, pinned, new ResourceLedger(sources), reachable, logical()).orElse(-1),
                    32, "relaxing a production pin incorrectly shrank the necessary material ceiling");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 64,
                    "certificate context checks mutated actual stock");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void thirtyTwoChestWitnessExportsValidatesAndConfirmsWithoutResearch(GameTestHelper helper) {
        try (var environment = new BulkEnvironment(helper, false)) {
            var player = environment.player;
            var snapshot = CraftingSessions.refreshBrowsing(player);
            var request = chest(32);
            var catalog = new CraftingRecipes(player, snapshot.workbench());
            var plan = fullPlan(helper, search(catalog, snapshot, request, logical()), 32);
            var before = inventoryIdentity(player);
            var witness = CraftPlan.Witness.from(plan, snapshot);
            helper.assertValueEqual(witness.steps().size(), 96, "bulk witness compressed or omitted ordinary steps");
            long started = System.nanoTime();
            var replay = CraftWitnessValidator.validate(catalog, request, witness, snapshot.sources(), logical(), snapshot.maxPlanSteps());
            long validationNanos = System.nanoTime() - started;
            helper.assertValueEqual(replay.fingerprint(), plan.fingerprint(), "bulk witness changed provenance or material counts");
            helper.assertTrue(catalog.validate(replay), "bulk witness failed real vanilla plan validation");
            helper.assertValueEqual(inventoryIdentity(player), before, "bulk witness validation consumed actual inventory");

            long fullSearches = CraftingService.activeFullSearches;
            long validations = CraftingService.witnessValidations;
            started = System.nanoTime();
            var draft = CraftingService.preview(player, request, "", witness, 1, logical(), null);
            long previewNanos = System.nanoTime() - started;
            helper.assertValueEqual(draft.view().code(), CraftingResultCode.CREATED, "fresh review rejected the complete bulk witness");
            helper.assertTrue(draft.view().complete() && !draft.token().equals(new java.util.UUID(0, 0)),
                    "complete bulk witness did not receive its one-use review token");
            started = System.nanoTime();
            var outcome = CraftingService.confirm(player, draft.token(), witness, 2, logical(), null);
            long confirmNanos = System.nanoTime() - started;
            helper.assertValueEqual(outcome.code(), CraftingResultCode.CREATED, "fresh confirmation did not commit the full bulk witness");
            helper.assertValueEqual(CraftingService.activeFullSearches, fullSearches, "bulk witness preview/confirmation re-searched alternatives");
            helper.assertValueEqual(CraftingService.witnessValidations, validations + 2, "bulk witness did not validate freshly at both server boundaries");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 0, "bulk witness commit did not consume exactly its source");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 32, "bulk witness commit delivered the wrong quantity");
            var replayAttempt = CraftingService.confirm(player, draft.token(), witness, 3, logical(), null);
            helper.assertValueEqual(replayAttempt.code(), CraftingResultCode.CONFIRMATION_EXPIRED, "bulk confirmation token could be replayed");
            helper.assertValueEqual(player.getInventory().countItem(Items.CHEST), 32, "replayed bulk confirmation duplicated output");
            Craftable.LOGGER.info("Bulk chest witness: steps={} directValidationNanos={} previewNanos={} confirmNanos={} (functional frozen budget)",
                    witness.steps().size(), validationNanos, previewNanos, confirmNanos);
        }
        helper.succeed();
    }

    private static SearchResult search(CraftingRecipes catalog, BrowsingSnapshot snapshot, CraftRequest request, SearchBudget budget) {
        var search = solver(catalog, snapshot, request, budget);
        long started = System.nanoTime();
        var result = search.run();
        log(request.batches(), result, System.nanoTime() - started);
        return result;
    }

    private static CraftSearch solver(CraftingRecipes catalog, BrowsingSnapshot snapshot, CraftRequest request, SearchBudget budget) {
        return new CraftSearch(catalog, request, snapshot.sources(), budget, SearchBudget.DEFAULT_MAX_STEPS,
                plan -> MainInventoryInsertion.simulate(snapshot.inventory(), snapshot.inventoryMaximum(), plan,
                        snapshot.playerReferences(), snapshot.references(), false).failure());
    }

    private static java.util.OptionalInt materialBound(CraftingRecipes catalog, BrowsingSnapshot snapshot, CraftRequest request) {
        var input = catalog.planningInput();
        return MaterialUpperBound.compute(input, request, new ResourceLedger(snapshot.sources()),
                presenceClosure(input, snapshot.sources()), logical());
    }

    private static java.util.Set<Item> presenceClosure(org.craftablemc.craftable.recipe.PlanningInput input,
            List<ResourceLedger.Source> sources) {
        var result = new java.util.HashSet<Item>();
        for (var source : sources) if (!CraftingRecipes.protectedStack(source.stack())) result.add(source.stack().getItem());
        boolean changed;
        do {
            changed = false;
            for (var entry : input.entries()) changed |= input.addReachableOutputs(entry, result);
        } while (changed);
        return java.util.Set.copyOf(result);
    }

    private static net.minecraft.world.item.crafting.RecipeHolder<?> certificateRecipe(String name, Item output, int count, Item... inputs) {
        var ingredients = net.minecraft.core.NonNullList.<net.minecraft.world.item.crafting.Ingredient>create();
        for (var item : inputs) ingredients.add(net.minecraft.world.item.crafting.Ingredient.of(item));
        return new net.minecraft.world.item.crafting.RecipeHolder<>(certificateId(name),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(output, count), ingredients));
    }

    private static ResourceLocation certificateId(String name) { return ResourceLocation.withDefaultNamespace("m412_material_" + name); }

    private static CraftPlan fullPlan(GameTestHelper helper, SearchResult result, int batches) {
        helper.assertValueEqual(result.code(), CraftingResultCode.CREATED, "reachable bulk route remained unknown: " + batches);
        helper.assertTrue(result.completeSearch() && result.plan().isPresent() && !result.plan().orElseThrow().partial(),
                "bulk route was not a complete concrete witness");
        var plan = result.plan().orElseThrow();
        helper.assertValueEqual(plan.completedBatches(), batches, "bulk route completed the wrong root count");
        helper.assertValueEqual(PlanningTestSupport.count(plan.primary(), Items.CHEST), batches, "bulk route manufactured the wrong quantity");
        return plan;
    }

    private static int extracted(CraftPlan plan, Item item) {
        return plan.extractions().stream().filter(extraction -> extraction.expected().is(item))
                .mapToInt(CraftPlan.Extraction::count).sum();
    }

    private static void log(int count, SearchResult result, long nanos) {
        Craftable.LOGGER.info("Bulk chest planning: batches={} code={} complete={} states={} steps={} elapsedNanos={} (frozen search clock)",
                count, result.code(), result.completeSearch(), result.visitedStates(),
                result.plan().map(plan -> plan.steps().size()).orElse(0), nanos);
    }

    private static void logRealAllowances(CraftingRecipes catalog, BrowsingSnapshot snapshot, CraftRequest request) {
        var scope = new CraftSearch.Reachability();
        // 仅预热这些不可变值的普通共享闭包；每次观测仍独立使用未改变的真实 8 ms 上限。
        // Warm only the ordinary shared closure for these immutable values.
        // Each observed attempt still has its own unchanged real 8 ms cap.
        solver(catalog, snapshot, request, logical()).withReachability(scope).run();
        for (int run = 0; run < 3; run++) {
            for (boolean resumable : List.of(false, true)) {
                var budget = resumable ? SearchBudget.resumable(8_000_000L) : new SearchBudget(8_000_000L);
                var search = solver(catalog, snapshot, request, budget).withReachability(scope);
                long started = System.nanoTime();
                SearchResult result;
                int slices = 0;
                if (resumable) {
                    java.util.Optional<SearchResult> completed;
                    do { completed = search.advance(2_000_000L); slices++; } while (completed.isEmpty());
                    result = completed.orElseThrow();
                } else { result = search.run(); slices = 1; }
                Craftable.LOGGER.info("Bulk chest real-cap observation: run={} resumable={} batches={} code={} states={} slices={} truncated={} exhausted={} elapsedNanos={} (solver only, no wallclock assertion)",
                        run, resumable, request.batches(), result.code(), result.visitedStates(), slices,
                        budget.truncated(), budget.exhausted(), System.nanoTime() - started);
            }
        }
    }

    private static CraftRequest chest(int batches) {
        return CraftRequest.one(ResourceLocation.withDefaultNamespace("chest")).withBatches(batches);
    }

    private static SearchBudget logical() { return new SearchBudget(() -> 0, 1, SearchBudget.MAX_STATES); }

    private static Object inventoryIdentity(ServerPlayer player) {
        return CraftPlan.stackKeys(MainInventoryInsertion.copyMainInventory(player.getInventory()));
    }

    private static final class BulkEnvironment implements AutoCloseable {
        final ServerPlayer player;
        final BlockPos table;
        final BlockState previousTable;

        BulkEnvironment(GameTestHelper helper, boolean mixed) {
            player = PlanningTestSupport.player(helper);
            table = player.blockPosition().north();
            previousTable = player.serverLevel().getBlockState(table);
            player.serverLevel().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
            if (mixed) player.getInventory().setItem(1, new ItemStack(Items.BIRCH_LOG, 64));
        }

        @Override public void close() {
            player.serverLevel().setBlock(table, previousTable, 2);
            PlanningTestSupport.remove(player);
        }
    }
}
