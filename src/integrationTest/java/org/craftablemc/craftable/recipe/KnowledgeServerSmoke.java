package org.craftablemc.craftable.recipe;

import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.planner.SearchBudget;

/** 中文：显式启用的隔离专服重启/重载夹具，不进入产品 JAR；第二 JVM 必须证明本批缓存可恢复，而非只证明文件存在。
 * English: Opt-in isolated dedicated-server restart/reload harness, excluded from the mod JAR; the second JVM proves this run's cache restores, not merely that a file exists. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class KnowledgeServerSmoke {
    private enum Step { BUILD_AND_RELOAD, AWAIT_RELOAD, STOPPED }
    private static Step step = Step.BUILD_AND_RELOAD;
    private static Object generation;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static long started;
    private static final String PHASE = System.getProperty("craftable.knowledgePhase", "");
    private static final String SCOPE = "phase=" + PHASE + " run=" + System.getProperty("craftable.knowledgeRun", "unspecified");

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (PHASE.isEmpty() || step == Step.STOPPED) return;
        var server = event.getServer();
        if (server.getTickCount() < 5) return;
        try {
            if (started == 0) {
                started = System.nanoTime();
                Craftable.LOGGER.warn("KNOWLEDGE_TEST START {}", SCOPE);
                if (!PHASE.equals("warmup") && !PHASE.equals("restart")) throw new AssertionError("unknown knowledge phase");
            }
            if (System.nanoTime() - started > 30_000_000_000L) throw new AssertionError("reload timeout at " + step);
            var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(server.overworld());
            if (step == Step.BUILD_AND_RELOAD) {
                var catalog = new CraftingRecipes(player, true);
                var initial = catalog.knowledgeStats();
                Craftable.LOGGER.warn("KNOWLEDGE_SERVER_SMOKE initial phase={} {}", PHASE, initial);
                // 中文：预热可正确拒绝其他配方代次的旧文件；只有第二 JVM 必须恢复本次生成的缓存。
                // Warmup may correctly reject a file from another recipe
                // generation. Only the second JVM must restore this run's cache.
                if (PHASE.equals("restart") && initial.relations() == 0)
                    throw new AssertionError("warmup cache did not load in restart JVM");
                catalog.producing(Ingredient.of(Items.STICK));
                if (PHASE.equals("restart") && (catalog.knowledgeStats().hits() != initial.hits() + 1
                        || catalog.knowledgeStats().builds() != initial.builds() || initial.builds() != 0))
                    throw new AssertionError("restart stick query rebuilt instead of hitting restored knowledge");
                Craftable.LOGGER.warn("KNOWLEDGE_SERVER_SMOKE stick phase={} {}", PHASE, catalog.knowledgeStats());
                var request = org.craftablemc.craftable.planner.CraftRequest.one(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
                var sources = List.of(new org.craftablemc.craftable.planner.ResourceLedger.Source("fixture", 0, new ItemStack(Items.OAK_LOG)),
                        new org.craftablemc.craftable.planner.ResourceLedger.Source("fixture", 1, new ItemStack(Items.DIAMOND, 3)));
                var result = new org.craftablemc.craftable.planner.CraftSearch(catalog, request, sources,
                        new org.craftablemc.craftable.planner.SearchBudget(1_000_000_000L), SearchBudget.DEFAULT_MAX_STEPS, plan -> null).run();
                if (result.plan().isEmpty() || result.plan().get().steps().size() != 3)
                    throw new AssertionError("restart recursive plan incorrect");
                generation = catalog.generation();
                reload = server.reloadResources(server.getPackRepository().getSelectedIds());
                step = Step.AWAIT_RELOAD;
            } else if (step == Step.AWAIT_RELOAD && reload.isDone()) {
                reload.join();
                var catalog = new CraftingRecipes(player, true);
                if (catalog.generation() == generation) throw new AssertionError("real reload retained old generation");
                catalog.producing(Ingredient.of(Items.STICK));
                Craftable.LOGGER.warn("KNOWLEDGE_SERVER_SMOKE PASS: phase={}, dedicated start, read-only recursive plan, actual reload, graceful shutdown", PHASE);
                Craftable.LOGGER.warn("KNOWLEDGE_TEST PASS {}", SCOPE);
                step = Step.STOPPED;
                server.halt(false);
            }
        } catch (Throwable failure) {
            Craftable.LOGGER.error("KNOWLEDGE_TEST FAIL {}", SCOPE);
            Craftable.LOGGER.error("KNOWLEDGE_SERVER_SMOKE FAIL at " + step, failure);
            step = Step.STOPPED;
            server.halt(false);
        }
    }

}
