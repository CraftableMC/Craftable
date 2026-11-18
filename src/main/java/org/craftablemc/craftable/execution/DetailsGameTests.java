package org.craftablemc.craftable.execution;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.PlanView;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/** 核对解释图与真实有序成本分离：共享展示不得制造材料、已完成根或截断后仍可执行的令牌。
 * Keeps explanations separate from actual ordered costs: shared display nodes
 * must not fabricate stock, completed roots or executable tokens after truncation. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DetailsGameTests {
    private static final String EMPTY = "bastion/mobs/empty";

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void oversizedDetailsReplaceBothGraphAndConfirmationToken(GameTestHelper helper) {
        // 不能只验证包大小；被隐藏的超大视图若仍带真实令牌，玩家便可能确认未见过的计划。
        // Size alone is insufficient: a hidden oversized view retaining a real
        // token could let the player confirm a plan they never reviewed.
        var player = PlanningTestSupport.player(helper);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess(), ConnectionType.OTHER);
        try {
            var large = new ItemStack(Items.DIAMOND);
            large.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("x".repeat(30000)));
            var view = new PlanView(org.craftablemc.craftable.api.CraftingResultCode.CREATED, true, true, 1,
                    List.of(), List.of(), List.of(large, large, large), List.of(new ItemStack(Items.STICK)),
                    List.of(), List.of(), List.of());
            var payload = new org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse(0, 7,
                    new CraftingService.Draft(java.util.UUID.randomUUID(), view, new PlanView.Choices(List.of(), false)));
            org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.encode(buffer, payload);
            helper.assertTrue(buffer.readableBytes() < 65536, "oversized detail escaped byte cap");
            var decoded = org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer);
            helper.assertValueEqual(decoded.draft().token(), org.craftablemc.craftable.network.CraftingDetailPayloads.NO_TOKEN,
                    "hidden plan retained executable token");
            helper.assertValueEqual(decoded.draft().view().code(),
                    org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "oversize not explicit");
            helper.assertFalse(decoded.draft().view().complete(), "truncated view called complete");
            var local = org.craftablemc.craftable.network.CraftingDetailPayloads.boundedLocal(payload.draft(), player.registryAccess());
            helper.assertValueEqual(local.view().code(), org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED,
                    "local display bypassed the wire size bound");
        } finally { buffer.release(); PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void graphKeepsActualSharedOriginsAndNetCosts(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var result = PlanningTestSupport.search(player, "diamond_pickaxe", 1, false, true,
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.DIAMOND, 3));
            var view = PlanView.from(new CraftingRecipes(player, true), request("diamond_pickaxe"), result, List.of(), java.util.List.of());
            helper.assertTrue(view.complete(), "incomplete ordinary display");
            helper.assertValueEqual(view.operations().size(), 3, "operation count");
            helper.assertValueEqual(PlanningTestSupport.count(view.consumed(), Items.OAK_LOG), 1, "net log cost");
            helper.assertValueEqual(PlanningTestSupport.count(view.consumed(), Items.OAK_PLANKS), 0, "intermediate counted as stock");
            helper.assertValueEqual(view.operations().get(2).inputOrigins().stream().filter(i -> i == 1).count(),
                    2L, "both stick inputs must reference the same producing batch");
            view.consumed().getFirst().setCount(60);
            helper.assertValueEqual(PlanningTestSupport.count(view.consumed(), Items.OAK_LOG), 1, "mutable display cost escaped");
            var stickPath = result.plan().orElseThrow().steps().get(1).path();
            var choices = view.choices(new CraftingRecipes(player, true), request("diamond_pickaxe"), stickPath);
            helper.assertTrue(choices.candidates().stream().anyMatch(c -> c.recipe().getPath().equals("stick"))
                    && choices.candidates().stream().anyMatch(c -> c.recipe().getPath().equals("stick_from_bamboo_item")),
                    "missing alternative stick recipes");
            helper.assertTrue(view.choices(new CraftingRecipes(player, true), request("diamond_pickaxe"), "0.8.8.8")
                    .candidates().isEmpty(), "undisplayed demand exposed candidates");
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess(), ConnectionType.OTHER);
            try {
                var payload = new org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse(0, 1,
                        new CraftingService.Draft(java.util.UUID.randomUUID(), view, choices));
                org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.encode(buffer, payload);
                helper.assertTrue(buffer.readableBytes() < 65536, "ordinary graph exceeds byte cap");
                var decoded = org.craftablemc.craftable.network.CraftingDetailPayloads.PreviewResponse.CODEC.decode(buffer);
                helper.assertValueEqual(decoded.draft().view().operations().get(2).inputOrigins(),
                        view.operations().get(2).inputOrigins(), "wire lost shared origins");
                helper.assertValueEqual(org.craftablemc.craftable.planner.CraftPlan.stackKeys(decoded.draft().view().consumed()),
                        org.craftablemc.craftable.planner.CraftPlan.stackKeys(view.consumed()), "wire changed costs");
                helper.assertValueEqual(decoded.draft().view().reviewIdentity(), view.reviewIdentity(),
                        "new stack objects or wire token changed the reviewed values");
                var changed = new PlanView(view.code(), view.workbench(), view.complete(), view.completedBatches(),
                        view.nodes(), view.operations(), view.consumed(), view.primary(), view.surplus(),
                        List.of(new ItemStack(Items.OAK_PLANKS)), view.missing());
                helper.assertFalse(changed.reviewIdentity().equals(view.reviewIdentity()),
                        "new overflow must require another explicit review");
            } finally { buffer.release(); }
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void blockedExplanationNeverFabricatesCostsOrCompletedRoot(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var result = PlanningTestSupport.search(player, "diamond_pickaxe", 1, true, true,
                    new ItemStack(Items.OAK_LOG));
            var view = PlanView.from(new CraftingRecipes(player, true), request("diamond_pickaxe").withPartial(true), result, List.of(), java.util.List.of());
            helper.assertTrue(view.complete(), "bounded partial display");
            helper.assertValueEqual(view.completedBatches(), 0, "invented completed root");
            helper.assertValueEqual(PlanningTestSupport.count(view.primary(), Items.STICK), 4, "partial frontier");
            helper.assertValueEqual(PlanningTestSupport.count(view.consumed(), Items.DIAMOND), 0, "explanation consumed diamonds");
            helper.assertTrue(view.nodes().stream().anyMatch(n -> n.path().equals("0") && n.explanation()),
                    "blocked root shown as actual operation");
            helper.assertValueEqual(view.operations().size(), 2, "explanation appended fake operations");
            helper.assertTrue(view.nodes().stream().anyMatch(n -> !n.reference().isEmpty()),
                    "partial explanation did not share actual stick frontier");
            helper.assertTrue(view.nodes().stream().noneMatch(n -> n.recipes().stream().anyMatch(id -> id.getPath().equals("diamond_block"))),
                    "missing diamonds explained as a redundant conversion cycle");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void missingWoodExplanationUsesRawDemandAndSelectedOutput(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var blocked = org.craftablemc.craftable.planner.SearchResult.blocked(
                    org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS);
            var boat = PlanView.from(catalog, request("oak_boat"), blocked, List.of(), java.util.List.of());
            helper.assertTrue(boat.nodes().stream().noneMatch(n -> n.recipes().stream()
                    .anyMatch(id -> id.getPath().equals("oak_wood"))), "default missing log was processed into wood first");
            helper.assertTrue(boat.operations().isEmpty() && boat.consumed().isEmpty(), "explanation fabricated execution costs");
            var explicitWood = new CraftRequest(ResourceLocation.withDefaultNamespace("oak_planks"), 1, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of("0.0", ResourceLocation.withDefaultNamespace("oak_wood")));
            var wood = PlanView.from(catalog, explicitWood, blocked, List.of(), java.util.List.of());
            var selected = wood.nodes().stream().filter(n -> n.path().equals("0.0")).findFirst().orElseThrow();
            helper.assertTrue(selected.needs().size() == 1 && selected.needs().getFirst().is(Items.OAK_WOOD)
                    && !selected.alternatives(), "explicit wood recipe was still labeled as an oak log");
            helper.assertTrue(selected.recipes().contains(ResourceLocation.withDefaultNamespace("oak_wood")),
                    "display simplification suppressed an explicit selection");
            helper.assertValueEqual(wood.nodes().stream().filter(n -> n.path().startsWith("0.0."))
                    .flatMap(n -> n.needs().stream()).filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum(),
                    4, "explicit wood inputs are not four logs");
            var birchRequest = new CraftRequest(ResourceLocation.withDefaultNamespace("stick"), 1, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of("0.0", ResourceLocation.withDefaultNamespace("birch_planks")));
            var birch = PlanView.from(catalog, birchRequest, blocked, List.of(), java.util.List.of());
            var planks = birch.nodes().stream().filter(n -> n.path().equals("0.0")).findFirst().orElseThrow();
            helper.assertTrue(planks.needs().size() == 1 && planks.needs().getFirst().is(Items.BIRCH_PLANKS),
                    "changing planks recipe did not change its displayed output");
            helper.assertTrue(birch.operations().isEmpty() && birch.consumed().isEmpty()
                    && birch.code() == blocked.code(), "selection explanation became an executable plan");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void missingDiamondBranchesShareRoundedBatchesWithoutExecution(GameTestHelper helper) {
        // 变化批数和现有零散钻石以暴露逐需求取整的重复块成本；共享仅用于解释，不能生成真实操作。
        // Vary batches and loose stock to expose duplicated per-demand rounding;
        // explanation sharing must never manufacture actual operations.
        var player = PlanningTestSupport.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var split = producer(catalog, Items.DIAMOND, Items.DIAMOND_BLOCK);
            var pins = new java.util.TreeMap<String, ResourceLocation>();
            catalog.find(ResourceLocation.withDefaultNamespace("diamond_pickaxe")).requirements().stream()
                    .filter(r -> r.ingredient().test(new ItemStack(Items.DIAMOND)))
                    .forEach(r -> pins.put("0." + r.slot(), split.id()));
            for (int batches : List.of(1, 6)) for (int existing : List.of(0, 1, 4)) {
                var req = new CraftRequest(ResourceLocation.withDefaultNamespace("diamond_pickaxe"), batches,
                        false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
                var view = PlanView.from(catalog, req, org.craftablemc.craftable.planner.SearchResult.blocked(
                        org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS), List.of(),
                        existing == 0 ? List.of() : List.of(new ItemStack(Items.DIAMOND, existing)));
                int expected = (Math.max(0, batches * 3 - existing) + 8) / 9;
                helper.assertValueEqual(view.nodes().stream().flatMap(n -> n.needs().stream())
                        .filter(s -> s.is(Items.DIAMOND_BLOCK)).mapToInt(ItemStack::getCount).sum(), expected,
                        "per-demand rounding duplicated diamond blocks (batches=" + batches + ", existing=" + existing + ")");
                helper.assertValueEqual(view.nodes().stream().filter(n -> pins.containsKey(n.path()))
                        .flatMap(n -> n.needs().stream()).filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),
                        batches * 3, "shared production lost individual diamond demands");
                helper.assertTrue(view.operations().isEmpty() && view.consumed().isEmpty() && view.primary().isEmpty()
                        && view.code() == org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS,
                        "hypothetical batch authorized execution");
                if (batches == 1 && existing == 0) helper.assertValueEqual(view.nodes().stream()
                        .filter(n -> pins.containsKey(n.path()) && !n.reference().isEmpty()).count(), 2L, "shared demands lost references");
            }
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void repeaterExplanationSharesBlockAcrossTreeDepths(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var split = producer(catalog, Items.REDSTONE, Items.REDSTONE_BLOCK);
            var pins = redstonePins(catalog, split.id());
            var req = new CraftRequest(ResourceLocation.withDefaultNamespace("repeater"), 1,
                    false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
            var view = PlanView.from(catalog, req, org.craftablemc.craftable.planner.SearchResult.blocked(
                    org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS), List.of(),
                    List.of(new ItemStack(Items.STICK, 2), new ItemStack(Items.STONE, 3)));
            helper.assertValueEqual(view.nodes().stream().flatMap(n -> n.needs().stream())
                    .filter(s -> s.is(Items.REDSTONE_BLOCK)).mapToInt(ItemStack::getCount).sum(), 1,
                    "torch and direct dust demands duplicated a redstone block");
            helper.assertValueEqual(view.nodes().stream().filter(n -> n.recipes().contains(split.id()))
                    .flatMap(n -> n.needs().stream()).filter(s -> s.is(Items.REDSTONE)).mapToInt(ItemStack::getCount).sum(), 3,
                    "three dust demands lost their quantities");
            helper.assertTrue(view.operations().isEmpty() && view.consumed().isEmpty(), "missing block fabricated real steps");
            var boat = PlanView.from(catalog, request("oak_boat"), org.craftablemc.craftable.planner.SearchResult.blocked(
                    org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS), List.of(), java.util.List.of());
            helper.assertValueEqual(boat.nodes().stream().flatMap(n -> n.needs().stream())
                    .filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum(), 2,
                    "five plank demands should use two batches, not five logs");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void sharedBlockPlansKeepExactTransactionCostsAndSurplus(GameTestHelper helper) {
        for (String target : List.of("diamond_pickaxe", "repeater")) {
            var player = PlanningTestSupport.player(helper);
            var block = target.equals("repeater") ? Items.REDSTONE_BLOCK : Items.DIAMOND_BLOCK;
            var loose = target.equals("repeater") ? Items.REDSTONE : Items.DIAMOND;
            var output = target.equals("repeater") ? Items.REPEATER : Items.DIAMOND_PICKAXE;
            var source = new net.minecraft.world.SimpleContainer(new ItemStack(block), new ItemStack(Items.STICK, 2),
                    target.equals("repeater") ? new ItemStack(Items.STONE, 3) : ItemStack.EMPTY);
            try {
                var result = PlanningTestSupport.search(player, target, 1, false, true,
                        List.of(source.getItem(0), source.getItem(1), source.getItem(2)).stream()
                                .filter(s -> !s.isEmpty()).toArray(ItemStack[]::new));
                var plan = result.plan().orElseThrow(() -> new AssertionError(result));
                var view = PlanView.from(new CraftingRecipes(player, true), request(target), result, List.of(), java.util.List.of());
                helper.assertValueEqual(PlanningTestSupport.count(view.consumed(), block), 1, "actual block cost");
                helper.assertValueEqual(PlanningTestSupport.count(plan.surplus(), loose), 6, "shared batch surplus");
                var snapshot = TransactionTestSupport.snapshot(player, source);
                var delivery = MainInventoryInsertion.simulate(player.getInventory(), plan, snapshot, false);
                helper.assertValueEqual(CraftingTransaction.execute(player, plan, snapshot, delivery),
                        org.craftablemc.craftable.api.CraftingResultCode.CREATED, "shared batch transaction");
                helper.assertTrue(source.isEmpty(), "transaction left input behind");
                helper.assertValueEqual(player.getInventory().countItem(output), 1, "target quantity");
                helper.assertValueEqual(player.getInventory().countItem(loose), 6, "transaction lost surplus");
            } finally { PlanningTestSupport.remove(player); }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void defaultExplanationStopsInverseButKeepsExplicitAndRealConversion(GameTestHelper helper) {
        // 同时检查默认解释、明确固定选择和真实可执行路线，防止展示去环被误用成求解器的全局裁剪。
        // Compare default explanation, explicit pins and executable routes so
        // display cycle simplification cannot become global solver pruning.
        var player = PlanningTestSupport.player(helper);
        try {
            var catalog = new CraftingRecipes(player, true);
            var split = producer(catalog, Items.IRON_INGOT, Items.IRON_BLOCK);
            var pins = new java.util.TreeMap<String, ResourceLocation>();
            catalog.find(ResourceLocation.withDefaultNamespace("iron_pickaxe")).requirements().stream()
                    .filter(r -> r.ingredient().test(new ItemStack(Items.IRON_INGOT)))
                    .forEach(r -> pins.put("0." + r.slot(), split.id()));
            var req = new CraftRequest(ResourceLocation.withDefaultNamespace("iron_pickaxe"), 1,
                    false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins);
            var blocked = org.craftablemc.craftable.planner.SearchResult.blocked(
                    org.craftablemc.craftable.api.CraftingResultCode.MISSING_INGREDIENTS);
            var view = PlanView.from(catalog, req, blocked, List.of(), List.of(new ItemStack(Items.STICK, 2)));
            helper.assertValueEqual(view.nodes().stream().flatMap(n -> n.needs().stream())
                    .filter(s -> s.is(Items.IRON_BLOCK)).mapToInt(ItemStack::getCount).sum(), 1, "missing block rounded twice");
            helper.assertTrue(view.nodes().stream().noneMatch(n -> n.recipes().contains(ResourceLocation.withDefaultNamespace("iron_block"))),
                    "default display rebuilt the block it proposed breaking");
            helper.assertTrue(view.operations().isEmpty() && view.consumed().isEmpty(), "explanation authorized a conversion");
            var source = view.nodes().stream().filter(n -> n.needs().stream().anyMatch(s -> s.is(Items.IRON_BLOCK)))
                    .findFirst().orElseThrow();
            pins.put(source.path(), ResourceLocation.withDefaultNamespace("iron_block"));
            var explicit = PlanView.from(catalog, new CraftRequest(req.recipe(), 1, false, false,
                    CraftRequest.PartialPolicy.EXPLICIT_SAFE, pins), blocked, List.of(), java.util.List.of());
            helper.assertTrue(explicit.nodes().stream().anyMatch(n -> n.path().equals(source.path())
                    && n.recipes().contains(ResourceLocation.withDefaultNamespace("iron_block"))),
                    "default simplification suppressed explicit block choice");
            for (String target : List.of("iron_pickaxe", "iron_block")) {
                var result = PlanningTestSupport.search(player, target, 1, false, true,
                        target.equals("iron_block") ? new ItemStack(Items.IRON_INGOT, 9) : new ItemStack(Items.IRON_BLOCK),
                        new ItemStack(Items.STICK, 2));
                helper.assertTrue(result.plan().isPresent(), "real conversion was incorrectly pruned: " + target);
                var real = PlanView.from(catalog, request(target), result, List.of(), java.util.List.of());
                helper.assertTrue(real.operations().stream().anyMatch(o -> o.recipe().equals(
                        target.equals("iron_block") ? ResourceLocation.withDefaultNamespace("iron_block") : split.id())),
                        "display hid executable conversion");
            }
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    private static org.craftablemc.craftable.recipe.CraftingRecipes.Entry producer(CraftingRecipes catalog,
            net.minecraft.world.item.Item output, net.minecraft.world.item.Item input) {
        return catalog.producing(net.minecraft.world.item.crafting.Ingredient.of(output)).stream()
                .filter(e -> e.requirements().size() == 1 && e.requirements().getFirst().ingredient().test(new ItemStack(input)))
                .findFirst().orElseThrow();
    }

    private static java.util.Map<String, ResourceLocation> redstonePins(CraftingRecipes catalog, ResourceLocation split) {
        var pins = new java.util.TreeMap<String, ResourceLocation>();
        var torch = catalog.find(ResourceLocation.withDefaultNamespace("redstone_torch"));
        for (var r : catalog.find(ResourceLocation.withDefaultNamespace("repeater")).requirements()) {
            String path = "0." + r.slot();
            if (r.ingredient().test(new ItemStack(Items.REDSTONE))) pins.put(path, split);
            else if (r.ingredient().test(new ItemStack(Items.REDSTONE_TORCH))) {
                pins.put(path, torch.id());
                torch.requirements().stream().filter(t -> t.ingredient().test(new ItemStack(Items.REDSTONE)))
                        .forEach(t -> pins.put(path + "." + t.slot(), split));
            }
        }
        return pins;
    }

    private static CraftRequest request(String id) {
        return CraftRequest.one(ResourceLocation.withDefaultNamespace(id));
    }
}
