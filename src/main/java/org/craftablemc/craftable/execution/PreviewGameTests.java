package org.craftablemc.craftable.execution;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.SearchBudget;

/** 经当前服务端审阅/确认边界验证新鲜度与否定证据，而非为退役的批量预览包装维持一套缓存。
 * Exercise freshness and negative evidence through current server review/confirm boundaries,
 * rather than maintaining another cache solely for the retired batch-preview facade. */
@GameTestHolder(Craftable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreviewGameTests {
    private static final String EMPTY = "bastion/mobs/empty";
    private static final UUID NO_TOKEN = new UUID(0, 0);

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fullFailureUpgradesToPartialWithoutAnotherFullSearch(GameTestHelper helper) {
        var player = playerWithTable(helper);
        var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
        try {
            // 普通完整意图可获得只读缺料说明，但不得以此签发部分执行令牌。
            // A full-only intent may receive missing-material diagnostics, never a partial-execution token.
            var full = review(player, request);
            helper.assertValueEqual(full.view().code(), CraftingResultCode.PARTIAL_CREATED, "full failure lost useful diagnostic");
            helper.assertValueEqual(full.token(), NO_TOKEN, "full intent authorized preparation");
            long searches = CraftingService.activeFullSearches;
            long diagnostics = CraftingService.activePartialSearches;

            var partial = review(player, request.withPartial(true));
            helper.assertValueEqual(partial.view().code(), CraftingResultCode.PARTIAL_CREATED, "diagnostic lost useful sticks");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches, "diagnostic repeated full search");
            helper.assertValueEqual(CraftingService.activePartialSearches, diagnostics + 1, "diagnostic did not rebuild exact counts");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "review consumed log");

            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            review(player, request.withPartial(true));
            helper.assertTrue(CraftingService.activeFullSearches > searches, "changed resources reused negative proof");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "changed-resource review consumed inputs");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void incompleteFullSearchCannotAuthorizePartialUpgrade(GameTestHelper helper) {
        var player = playerWithTable(helper);
        var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
        try {
            // 截断反例进入真实 fallback 审阅；后续诊断必须重新证明完整失败，不能信任未知结果。
            // The truncated counterexample enters live fallback review; later diagnosis must re-prove full failure, not trust an unknown.
            var unknown = CraftingService.preview(player, request, "", null, -1,
                    new SearchBudget(() -> 0L, 1, 1), null);
            helper.assertValueEqual(unknown.view().code(), CraftingResultCode.SEARCH_BUDGET_EXCEEDED, "truncated full search");
            helper.assertValueEqual(unknown.token(), NO_TOKEN, "unknown review authorized execution");
            long searches = CraftingService.activeFullSearches;
            var diagnosed = review(player, request.withPartial(true));
            helper.assertValueEqual(diagnosed.view().code(), CraftingResultCode.PARTIAL_CREATED, "complete retry lost diagnosis");
            helper.assertValueEqual(CraftingService.activeFullSearches, searches + 1, "unknown was used as negative proof");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_LOG), 1, "unknown/retry consumed input");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void reviewedInputsAndCapacityCannotSurviveFreshChanges(GameTestHelper helper) {
        var player = playerWithTable(helper);
        var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        try {
            var reviewed = review(player, request);
            helper.assertTrue(!reviewed.token().equals(NO_TOKEN), "initial review missing token");
            // 同种材料数量不变，只填满空位；真实确认仍必须拒绝先前可容纳的产物。
            // Keep ingredient counts unchanged and fill only empty slots; live confirmation must reject the previously deliverable output.
            for (int slot = 1; slot < 36; slot++)
                player.getInventory().setItem(slot, new ItemStack(Items.BEDROCK, 64));
            helper.assertValueEqual(confirm(player, reviewed.token()).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "capacity change retained old authority");
            helper.assertValueEqual(review(player, request).view().code(), CraftingResultCode.NO_OUTPUT_SPACE,
                    "fresh review ignored occupied capacity");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 3, "rejected capacity change consumed inputs");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "rejected capacity change published output");

            player.getInventory().setItem(35, ItemStack.EMPTY);
            var free = review(player, request);
            helper.assertTrue(!free.token().equals(NO_TOKEN), "new free slot did not permit review");
            player.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertValueEqual(confirm(player, free.token()).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "material change retained old authority");
            helper.assertValueEqual(review(player, request).view().code(), CraftingResultCode.MISSING_INGREDIENTS,
                    "fresh review reused stale material success");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "stale material review published output");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void changedMenuAndPermissionsCannotReusePreviewEvidence(GameTestHelper helper) {
        var player = playerWithTable(helper);
        var request = CraftRequest.one(ResourceLocation.withDefaultNamespace("stick"));
        var limited = player.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING);
        boolean original = limited.get();
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        try {
            var reviewed = review(player, request);
            helper.assertTrue(!reviewed.token().equals(NO_TOKEN), "initial review missing token");
            player.containerMenu = new net.minecraft.world.inventory.CraftingMenu(10, player.getInventory());
            helper.assertValueEqual(confirm(player, reviewed.token()).code(), CraftingResultCode.CONFIRMATION_EXPIRED,
                    "new menu kept review authority");
            var next = review(player, request);
            helper.assertTrue(!next.token().equals(NO_TOKEN), "new menu could not obtain a fresh review");

            // 同一菜单的权限收紧也必须由审阅和提交实际消费，不只改变缓存身份。
            // Tightening permissions within one menu must affect review and commit, not merely a cache identity.
            limited.set(true, player.getServer());
            helper.assertValueEqual(confirm(player, next.token()).code(), CraftingResultCode.ENVIRONMENT_CHANGED,
                    "permission tightening kept review authority");
            helper.assertValueEqual(review(player, request).view().code(), CraftingResultCode.RECIPE_LOCKED,
                    "fresh review ignored recipe permission");
            helper.assertValueEqual(player.getInventory().countItem(Items.OAK_PLANKS), 2, "rejected review consumed inputs");
            helper.assertValueEqual(player.getInventory().countItem(Items.STICK), 0, "rejected review published output");
        } finally {
            limited.set(original, player.getServer());
            player.containerMenu = player.inventoryMenu;
            PlanningTestSupport.remove(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = EMPTY)
    public static void fullEvidenceRequiresExactIntentAndScope(GameTestHelper helper) {
        var player = PlanningTestSupport.player(helper);
        try {
            var recipes = new org.craftablemc.craftable.recipe.CraftingRecipes(player, true);
            var scope = new org.craftablemc.craftable.planner.CraftSearch.Reachability();
            var request = org.craftablemc.craftable.planner.CraftRequest.one(ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
            var first = new org.craftablemc.craftable.planner.CraftSearch(recipes, request, List.of(), logicalBudget(), SearchBudget.DEFAULT_MAX_STEPS, p -> null)
                    .withReachability(scope);
            helper.assertValueEqual(first.run().code(), CraftingResultCode.MISSING_INGREDIENTS, "full fixture not negative");
            helper.assertTrue(first.fullEvidence() != null, "completed full failure lost evidence");
            var altered = List.of(request.withBatches(2),
                    new org.craftablemc.craftable.planner.CraftRequest(request.recipe(), 1, true, true, request.policy(), java.util.Map.of()),
                    new org.craftablemc.craftable.planner.CraftRequest(request.recipe(), 1, true, false, request.policy(),
                            java.util.Map.of("0.4", ResourceLocation.withDefaultNamespace("stick_from_bamboo_item"))),
                    org.craftablemc.craftable.planner.CraftRequest.one(ResourceLocation.withDefaultNamespace("wooden_pickaxe")));
            for (var intent : altered) {
                var search = new org.craftablemc.craftable.planner.CraftSearch(recipes, intent, List.of(), logicalBudget(), SearchBudget.DEFAULT_MAX_STEPS, p -> null)
                        .withReachability(scope).withFullEvidence(first.fullEvidence());
                search.run();
                helper.assertValueEqual(search.fullSearches(), 1, "mismatched intent skipped complete search: " + intent);
            }
            var newScope = new org.craftablemc.craftable.planner.CraftSearch(recipes, request.withPartial(true), List.of(), logicalBudget(), SearchBudget.DEFAULT_MAX_STEPS, p -> null)
                    .withReachability(new org.craftablemc.craftable.planner.CraftSearch.Reachability()).withFullEvidence(first.fullEvidence());
            newScope.run();
            helper.assertValueEqual(newScope.fullSearches(), 1, "old scope authorized a new resource view");
        } finally { PlanningTestSupport.remove(player); }
        helper.succeed();
    }

    private static ServerPlayer playerWithTable(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.CRAFTING_TABLE);
        return PlanningTestSupport.player(helper);
    }

    // 逻辑时钟只隔离功能反例的 JIT/调度噪声；不改变生产期限，也不作性能证据。
    // A logical clock isolates functional counterexamples from JIT/scheduling noise; it neither changes production caps nor proves performance.
    private static SearchBudget logicalBudget() { return new SearchBudget(() -> 0L, 1, SearchBudget.MAX_STATES); }
    private static CraftingService.Draft review(ServerPlayer player, CraftRequest request) {
        return CraftingService.preview(player, request, "", null, -1, logicalBudget(), null);
    }
    private static CraftingService.Outcome confirm(ServerPlayer player, UUID token) {
        return CraftingService.confirm(player, token, null, -1, logicalBudget(), null);
    }
}
