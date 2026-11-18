package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.ResourceLedger;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.planner.SearchResult;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/** 包内共享的规划/执行测试夹具；统一生存玩家与会话清理，避免测试间复用旧授权。
 * Package-local planning/execution fixtures centralize survival players and
 * session cleanup, preventing authority from leaking between tests. */
final class PlanningTestSupport {
    private PlanningTestSupport() {}

    /** 来源值只供只读求解；宽松功能预算与无交付回调不代证真实准入限额或容量检查。
     * Source values feed read-only search; a generous functional budget and
     * absent delivery callback do not prove real admission or capacity checks. */
    static SearchResult search(ServerPlayer player, String id, int batches, boolean partial, boolean workbench, ItemStack... stacks) {
        var sources = new ArrayList<ResourceLedger.Source>();
        for (int i = 0; i < stacks.length; i++) sources.add(new ResourceLedger.Source("fixture", i, stacks[i]));
        var recipes = new CraftingRecipes(player, workbench);
        return new CraftSearch(recipes, new CraftRequest(ResourceLocation.withDefaultNamespace(id), batches, partial,
                false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, Map.of()), sources,
                new SearchBudget(1_000_000_000L), SearchBudget.DEFAULT_MAX_STEPS, plan -> null).run();
    }

    static int count(List<ItemStack> stacks, Item item) {
        return stacks.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("removal")
    static ServerPlayer player(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(0, 2, 0));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    /** 释放快照和会话后移除玩家；只从玩家表移除不足以隔离按 UUID 保留的状态。
     * Release snapshot/session state before removing the player; player-list
     * removal alone does not isolate state retained by UUID. */
    static void remove(ServerPlayer player) {
        org.craftablemc.craftable.environment.EnvironmentSnapshotService.remove(player.getUUID());
        CraftingSessions.clear(player.getUUID());
        player.getServer().getPlayerList().remove(player);
    }
}
