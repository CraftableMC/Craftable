package org.craftablemc.craftable.recipe;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.planner.SearchBudget;

/** 中文：显式测量原归一化算法对真实客户端同步配方的处理；不实现第二目录或运行时缓存，也不证明扩展配方兼容性。
 * English: Opt-in measurement of the original normalization algorithm against actual
 * client-synchronized recipes. Not a second catalog implementation, runtime cache or proof of extended recipe compatibility. */
public final class ClientKnowledgeProbe {
    private ClientKnowledgeProbe() {}
    private static PlanningInput.Catalog sliced;
    private static final ArrayList<Double> sliceTimes = new ArrayList<>();
    private static int sample;
    private static int baseEntries;
    private static java.util.Set<net.minecraft.resources.ResourceLocation> baseRecipeIds;
    private static java.util.Collection<net.minecraft.world.item.crafting.RecipeHolder<?>> measuredRecipes;

    /** 中文：每真实客户端 tick 调用一次并测量初始化；不能把同帧循环多个分片冒充独立帧。
     * English: Called once per actual client tick. No loop pretending several slices
     * in one frame were independent frames; initialization is measured too. */
    public static boolean sliced(Minecraft minecraft) {
        long start = System.nanoTime();
        if (sliced == null) {
            measuredRecipes = minecraft.level.getRecipeManager().getRecipes();
            if (sample >= 5) {
                // 中文：普通配方倍增仅属测试，不装入实际管理器，也不据此声称模组兼容性。
                // Test-only ordinary recipe multiplication. Never install it
                // into the live manager or pretend this is mod compatibility.
                var ordinary = measuredRecipes.stream().filter(holder -> baseRecipeIds.contains(holder.id())).toList();
                var expanded = new ArrayList<>(measuredRecipes);
                for (int i = 0; i < (sample == 5 ? 1000 : 5000); i++)
                    expanded.add(new net.minecraft.world.item.crafting.RecipeHolder<>(
                            // 中文：当前归一化只接受原版命名空间；用该空间的测试 ID 并检查发布，否则测到的只是 5000 次拒绝。
                            // Current normalization accepts the vanilla namespace only.
                            // Use a test-only ID there and assert publication,
                            // otherwise this would measure 5000 rejected IDs.
                            net.minecraft.resources.ResourceLocation.withDefaultNamespace("craftable_scale_probe_" + i),
                            ordinary.get(i % ordinary.size()).value()));
                measuredRecipes = expanded;
            }
            sliced = new PlanningInput.Catalog();
            sliced.observe(minecraft.getConnection(), measuredRecipes, measuredRecipes, minecraft.level.registryAccess());
        }
        sliced.advance(Math.max(1, 2_000_000L - (System.nanoTime() - start)));
        sliceTimes.add((System.nanoTime() - start) / 1e6);
        if (sliced.state() == PlanningInput.Catalog.State.LIMITED) throw new AssertionError("Vanilla catalog limited");
        if (sliced.state() != PlanningInput.Catalog.State.READY) return false;
        var bound = sliced.bind(true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT);
        var identity = bound.generation();
        if (sample == 0) {
            baseEntries = bound.entries().size();
            baseRecipeIds = bound.entries().stream().map(CraftingRecipes.Entry::id).collect(java.util.stream.Collectors.toSet());
        }
        int added = sample < 5 ? 0 : sample == 5 ? 1000 : 5000;
        if (bound.entries().size() != baseEntries + added)
            throw new AssertionError("Scale fixture was filtered before normalization: " + bound.entries().size());
        for (int i = 0; i < 100; i++) {
            sliced.observe(minecraft.getConnection(), measuredRecipes, measuredRecipes, minecraft.level.registryAccess());
            if (sliced.bind(true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT).generation() != identity)
                throw new AssertionError("Same generation rebuilt during client binding");
        }
        double max = sliceTimes.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        Craftable.LOGGER.info("CLIENT_CATALOG sample={} addedOrdinary={} normalized={} actualTicks={} totalMs={} maxSliceMs={} repeatedBindings=100 builds={}",
                sample + 1, added, bound.entries().size(),
                sliceTimes.size(), sliceTimes.stream().mapToDouble(Double::doubleValue).sum(), max, sliced.builds());
        sample++;
        sliced.clear();
        sliced = null;
        measuredRecipes = null;
        sliceTimes.clear();
        return sample >= 7;
    }

    public static void measure(Minecraft minecraft) {
        var recipes = minecraft.level.getRecipeManager().getRecipes();
        var times = new ArrayList<Double>();
        long warmStart = 0;
        CraftingRecipes.Index index = null;
        for (int sample = 0; sample < 5; sample++) {
            long start = System.nanoTime();
            index = new CraftingRecipes.Index(recipes, minecraft.level.registryAccess());
            for (var entry : index.ordered)
                for (var requirement : entry.requirements()) index.producing(requirement.ingredient());
            times.add((System.nanoTime() - start) / 1e6);
        }
        if (index.ordered.isEmpty() || index.knowledge.isEmpty())
            throw new AssertionError("Client recipe/tag synchronization was not ready");
        long builds = index.builds;
        warmStart = System.nanoTime();
        for (int repeat = 0; repeat < 10; repeat++)
            for (var entry : index.ordered)
                for (var requirement : entry.requirements()) index.producing(requirement.ingredient());
        if (index.builds != builds) throw new AssertionError("Warm client knowledge rebuilt producer relations");
        double warmMs = (System.nanoTime() - warmStart) / 1e6;
        times.sort(Double::compare);
        Craftable.LOGGER.info("CLIENT_KNOWLEDGE recipes={} relations={} sameJvmSamples=5 medianMs={} maxMs={} warm10Ms={} additionalBuilds=0 estimatedBytes={}",
                index.ordered.size(), index.knowledge.size(), times.get(2), times.getLast(), warmMs, index.estimatedBytes);
        // 中文：使用真实同步客户端目录，不读集成服对象或世界组装回调，保持与生产求解器/输入模型一致。
        // Real synchronized client catalog, no integrated-server object or
        // world assembly callback: exactly the production solver/input model.
        var input = new PlanningInput(index, true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT);
        var request = org.craftablemc.craftable.planner.CraftRequest.one(net.minecraft.resources.ResourceLocation.withDefaultNamespace("diamond_pickaxe"));
        var sources = java.util.List.of(
                new org.craftablemc.craftable.planner.ResourceLedger.Source("a", 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG)),
                new org.craftablemc.craftable.planner.ResourceLedger.Source("a", 1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3)));
        var result = new org.craftablemc.craftable.planner.CraftSearch(input, request, sources,
                new org.craftablemc.craftable.planner.SearchBudget(() -> 0, 1, 2048), SearchBudget.DEFAULT_MAX_STEPS, p -> null).run();
        if (result.code() != org.craftablemc.craftable.api.CraftingResultCode.CREATED || result.plan().orElseThrow().steps().size() != 3)
            throw new AssertionError("Pure client solver failed synchronized recipe fixture: " + result.code());
        Craftable.LOGGER.info("CLIENT_KNOWLEDGE_VALUE plan=diamond_pickaxe steps=3 states={} noServerPlayer=true", result.visitedStates());
    }
}
