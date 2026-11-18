package org.craftablemc.craftable.recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.storage.LevelResource;
import org.craftablemc.craftable.Craftable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.config.CraftableServerConfig;

/** 普通原版配方边界；每个索引归所属游戏线程，执行与合成钩子只在服务端线程运行。
 * 静态索引不存玩家权限或材料结论；每个实例仅冻结本次操作的访问/确认规则，不授予提交权。
 * Ordinary vanilla recipe boundary. Each index belongs to its owning game thread;
 * execution and crafting hooks remain exclusively on the server thread.
 * Static indexes hold no player permissions or material verdicts; each instance only freezes
 * access/consent rules for one operation and does not grant commit authority.
 */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class CraftingRecipes {
    private static final Map<MinecraftServer, Index> INDEXES = new IdentityHashMap<>();
    private final ServerPlayer player;
    private final boolean workbench;
    private final Set<ResourceLocation> unlocked;
    private final boolean limited;
    private final Index index;
    private final DirectPreparationPolicy directPreparation;

    public CraftingRecipes(ServerPlayer player, boolean workbench) {
        this(player, workbench, CraftableServerConfig.directPreparationPolicy());
    }

    public CraftingRecipes(ServerPlayer player, boolean workbench, DirectPreparationPolicy directPreparation) {
        this.player = player;
        this.workbench = workbench;
        this.directPreparation = java.util.Objects.requireNonNull(directPreparation, "directPreparation");
        this.limited = player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING);
        var server = player.getServer();
        var manager = server.getRecipeManager();
        // RecipeManager 重载时替换不可变 byName 表；两次重载间 values 视图稳定，因此仅 O(1) 比身份。
        // RecipeManager replaces its immutable byName map on reload. Its values
        // view is stable between reloads, so checking identity is O(1), not an
        // O(all recipes) fingerprint for every visible button.
        Object identity = manager.getRecipes();
        Index cached = INDEXES.get(server);
        if (cached == null || cached.identity != identity) {
            cached = refresh(server, false);
        }
        this.index = cached;
        this.unlocked = new java.util.HashSet<>();
        if (limited) {
            for (var entry : index.byId.values()) {
                if (player.getRecipeBook().contains(entry.holder())) unlocked.add(entry.id());
            }
        }
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        refresh(event.getServer(), true);
    }

    @SubscribeEvent
    public static void reloaded(OnDatapackSyncEvent event) {
        // TagsUpdatedEvent 可能在集成客户端加载线程触发；此服务端事件在配方和标签绑定后运行，玩家加入不替换索引。
        // TagsUpdatedEvent can run on the integrated client's loading thread.
        // This server event runs after recipe AND tag binding; player joins do
        // not replace the shared index, even on an empty server.
        if (event.getPlayer() == null) {
            var server = event.getPlayerList().getServer();
            refresh(server, true);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        var index = INDEXES.remove(event.getServer());
        if (index != null && index.cache != null) {
            index.flush();
            index.cache.close();
            Craftable.LOGGER.info("Craftable knowledge: stopped lookups={} hits={} builds={} keys={} estimatedBytes={}",
                    index.lookups, index.hits, index.builds, index.knowledge.size(), index.estimatedBytes);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        var index = INDEXES.get(event.getServer());
        if (index != null && System.nanoTime() - index.lastFlush >= 30_000_000_000L) index.flush();
    }

    /** 游戏线程替换整代静态知识；只在生命周期事件中指纹化/读盘，旧 I/O 世代先失效再发布新索引。
     * Replace an entire static generation on the game thread; fingerprint/read disk only in lifecycle
     * events, invalidating the old I/O epoch before publishing the new index. */
    private static Index refresh(MinecraftServer server, boolean lifecycle) {
        var old = INDEXES.get(server);
        var next = new Index(server);
        next.cache = old == null ? null : old.cache;
        if (lifecycle && next.cache == null) next.cache = new RecipeKnowledgeCache(
                server.getWorldPath(LevelResource.ROOT).resolve("craftable/cache/recipe-knowledge-v1.json"),
                message -> Craftable.LOGGER.info("Craftable knowledge: {}", message));
        if (next.cache != null) next.epoch = next.cache.advance();
        // 仅生命周期调用计算指纹和读盘；意外运行时配方替换降级内存模式，C 请求绝不触发 I/O。
        // Only lifecycle calls fingerprint/read disk. An unexpected runtime
        // recipe replacement falls back to memory; a C request never does I/O.
        if (lifecycle) {
            long start = System.nanoTime();
            next.fingerprint = next.fingerprint(server);
            if (next.fingerprint != null) {
                var snapshot = old != null && next.fingerprint.equals(old.fingerprint)
                        ? old.snapshot() : next.cache.load(next.fingerprint);
                boolean restored = next.restore(snapshot);
                next.dirty = restored && old != null && next.fingerprint.equals(old.fingerprint);
                if (snapshot != null && !restored)
                    Craftable.LOGGER.info("Craftable knowledge: rejected incomplete or incompatible relations");
            }
            Craftable.LOGGER.info("Craftable knowledge: generation recipes={} restored={} setupMs={}",
                    next.ordered.size(), next.knowledge.size(), (System.nanoTime() - start) / 1e6);
        }
        INDEXES.put(server, next);
        return next;
    }

    public Object generation() { return index; }

    /** 在重建或搜索新世代之前拒绝过期审阅。
     * Reject a stale review before rebuilding or searching a new generation. */
    public static boolean isCurrentGeneration(MinecraftServer server, Object generation) {
        var current = INDEXES.get(server);
        return current == generation && current != null && current.identity == server.getRecipeManager().getRecipes();
    }
    public Object accessIdentity() { return List.of(index, workbench, limited, Set.copyOf(unlocked)); }
    public boolean workbench() { return workbench; }
    public boolean limitedCrafting() { return limited; }
    public Set<ResourceLocation> unlockedRecipes() { return Set.copyOf(unlocked); }
    public Entry find(ResourceLocation id) { return index.byId.get(id); }
    public List<Entry> entries() { return index.ordered; }
    public PlanningInput planningInput() { return new PlanningInput(index, workbench, limited, unlocked, directPreparation); }
    public boolean allowDirectPreparation(Entry entry) {
        return entry != null && directPreparation.allowsWithoutConfirmation(entry.id());
    }

    /** 依据服务端活配方行为复验已选路线，不在此重搜替代路线；纯模型不能授权执行。
     * Validate the chosen route against live authoritative behavior, never
     * search an alternative here. The pure model cannot authorize execution. */
    public boolean validate(CraftPlan plan) {
        for (var step : plan.steps()) {
            var entry = find(step.recipe().id());
            if (unavailable(entry) != null) return false;
            var actual = assemble(entry, step.path(), step.inputs());
            if (actual == null || !ItemStack.matches(actual.output(), step.output())
                    || !CraftPlan.stackKeys(actual.remainders()).equals(CraftPlan.stackKeys(step.remainders()))) return false;
        }
        return true;
    }

    /** 检查冻结的服务端配方访问权；不同于纯模型的支持范围门，此处的活配方仍须经 assemble/validate 复验。
     * Check frozen server recipe access; unlike the pure model's support-admission gate,
     * live recipes here still require assemble/validate revalidation. */
    public CraftingResultCode unavailable(Entry entry) {
        if (entry == null) return CraftingResultCode.UNSUPPORTED_RECIPE;
        if (limited && !unlocked.contains(entry.id())) return CraftingResultCode.RECIPE_LOCKED;
        if (entry.gridSize() == 3 && !workbench) return CraftingResultCode.MISSING_WORKSTATION;
        return null;
    }

    public List<Entry> producing(Ingredient ingredient) {
        return index.producing(ingredient);
    }

    // 原版 Ingredient.test 忽略组件和数量；只有该精确普通语义及已证明的集合表达式可共享物品集合键，simple 不足以证明。
    // Vanilla Ingredient.test ignores stack components and count. Only this
    // exact ordinary semantics (and the platform's proven set expressions)
    // may share an item-set key. "Simple" alone is NOT such a proof.
    static List<String> key(Ingredient ingredient) {
        if (!itemSetSemantics(ingredient, 0, new int[]{256})
                || ingredient.getItems().length > RecipeKnowledgeCache.MAX_REFERENCES) return null;
        var key = java.util.Arrays.stream(ingredient.getItems())
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).distinct().sorted().toList();
        return key.stream().anyMatch(s -> s.length() > 512) ? null : key;
    }

    private static boolean itemSetSemantics(Ingredient ingredient, int depth, int[] remaining) {
        if (depth > 16 || --remaining[0] < 0) return false;
        if (!ingredient.isCustom()) return true;
        var custom = ingredient.getCustomIngredient();
        if (custom instanceof net.neoforged.neoforge.common.crafting.DifferenceIngredient difference)
            return itemSetSemantics(difference.base(), depth + 1, remaining)
                    && itemSetSemantics(difference.subtracted(), depth + 1, remaining);
        List<Ingredient> children;
        if (custom instanceof net.neoforged.neoforge.common.crafting.CompoundIngredient compound) children = compound.children();
        else if (custom instanceof net.neoforged.neoforge.common.crafting.IntersectionIngredient intersection) children = intersection.children();
        else return false;
        for (var child : children) if (!itemSetSemantics(child, depth + 1, remaining)) return false;
        return true;
    }

    List<Entry> producingUncached(Ingredient ingredient) {
        return index.producingUncached(ingredient);
    }

    record KnowledgeStats(long lookups, long hits, long builds, int relations, int options, int producers, long estimatedBytes) {}
    KnowledgeStats knowledgeStats() {
        return new KnowledgeStats(index.lookups, index.hits, index.builds, index.knowledge.size(),
                index.optionReferences, index.producerReferences, index.estimatedBytes);
    }

    public CraftPlan.Step assemble(Entry entry, String path, List<ItemStack> grid) {
        return assemble(entry, path, grid, java.util.Collections.nCopies(grid.size(), -1));
    }

    /** 在复制后的权威步骤中保留重放来源；来源标记不取代活配方匹配、组装或返还物检查。
     * Preserve replay provenance in the already copied authoritative step.
     * Origins do not replace live recipe matching, assembly or remainder checks. */
    public CraftPlan.Step assemble(Entry entry, String path, List<ItemStack> grid, List<Integer> origins) {
        CraftingInput input = CraftingInput.of(entry.gridSize(), entry.gridSize(), CraftPlan.copies(grid));
        var recipe = entry.holder().value();
        if (!recipe.matches(input, player.serverLevel())) return null;
        ItemStack output = recipe.assemble(input, player.registryAccess()).copy();
        // 静态产物只是索引提示，不能凭空发明动态产物；含组件漂移的不支持行为必须拒绝。
        // Static output is an index hint, never permission to invent a dynamic
        // result. Unsupported behavior is rejected, including component drift.
        if (!ItemStack.matches(output, entry.output())) return null;
        List<ItemStack> remainders;
        CommonHooks.setCraftingPlayer(player);
        try {
            remainders = recipe.getRemainingItems(input);
        } finally {
            CommonHooks.setCraftingPlayer(null);
        }
        if (remainders.size() != input.size()) return null;
        return new CraftPlan.Step(entry.holder(), path, entry.gridSize(), grid, output, remainders, origins);
    }

    public static boolean protectedStack(ItemStack stack) {
        return !stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()));
    }

    public record Requirement(int slot, Ingredient ingredient) {}

    public record Entry(RecipeHolder<CraftingRecipe> holder, int gridSize,
            List<Requirement> requirements, ItemStack output) {
        public Entry {
            requirements = List.copyOf(requirements);
            output = output.copy();
        }
        public ResourceLocation id() { return holder.id(); }
        @Override public ItemStack output() { return output.copy(); }
    }

    /** 单代静态模型和完整生产者关系缓存，归所属游戏线程；异步工作者仅接收其字符串快照。
     * Generation-local static model and complete producer-relation cache, owned by the game thread;
     * asynchronous workers receive only its string snapshots. */
    static final class Index {
        static final long MAX_ESTIMATED_BYTES = 64L * 1024 * 1024;
        final Object identity;
        final Map<ResourceLocation, Entry> byId = new HashMap<>();
        final Map<Item, List<Entry>> byOutput = new HashMap<>();
        // 仅在 Builder 完成后发布；未完成索引绝不经 PlanningInput 或服务端世代表泄漏。
        // Published only when Builder finishes. An incomplete Index never
        // escapes through PlanningInput or the server's generation map.
        List<Entry> ordered = List.of();
        List<Entry> valueOrdered = List.of();
        final Map<List<String>, List<Entry>> knowledge = new HashMap<>();
        final Map<Ingredient, List<Entry>> aliases = new IdentityHashMap<>();
        final Set<ResourceLocation> valueRecipes = new java.util.HashSet<>();
        final Map<Item, ItemStack> remainders = new HashMap<>();
        final Set<Item> returnedItems = new java.util.HashSet<>();
        final Set<ResourceLocation> returningRecipes = new java.util.HashSet<>();
        int optionReferences, producerReferences;
        long lookups, hits, builds;
        long estimatedBytes;
        RecipeKnowledgeCache cache;
        String fingerprint;
        String planningFingerprint;
        long epoch, lastFlush = System.nanoTime();
        boolean dirty;

        Index(MinecraftServer server) {
            this(server.getRecipeManager().getRecipes(), server.registryAccess());
        }

        // 两端使用同一规范化算法，无需玩家、磁盘缓存或执行钩子；配方/标签属于游戏线程，不可作为工作线程 API。
        // One normalization algorithm for both sides. No player, disk cache or
        // execution hook is required to build static knowledge from synced data.
        // This is not a worker-thread API: recipes/tags belong to the game thread.
        Index(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
            this.identity = recipes;
            var builder = new Builder(this, recipes, registries, false);
            while (!builder.advance(Long.MAX_VALUE / 2, System::nanoTime)) { }
        }

        private Index(Object identity) { this.identity = identity; }

        @SuppressWarnings("unchecked")
        private void add(RecipeHolder<?> holder, HolderLookup.Provider registries) {
            if (!holder.id().getNamespace().equals("minecraft") || !(holder.value() instanceof CraftingRecipe recipe)
                    || (recipe.getSerializer() != RecipeSerializer.SHAPED_RECIPE
                    && recipe.getSerializer() != RecipeSerializer.SHAPELESS_RECIPE)
                    || recipe.isSpecial() || recipe.isIncomplete() || recipe.getIngredients().isEmpty()) return;
            var output = recipe.getResultItem(registries);
            if (output.isEmpty()) return;
            int size = recipe.canCraftInDimensions(2, 2) ? 2 : 3;
            if (!recipe.canCraftInDimensions(size, size)) return;
            var requirements = new ArrayList<Requirement>();
            boolean returnsContainer = false;
            boolean valueRecipe = recipe.getClass() == ShapedRecipe.class
                    || recipe.getClass() == net.minecraft.world.item.crafting.ShapelessRecipe.class;
            for (int i = 0; i < recipe.getIngredients().size(); i++) {
                Ingredient ingredient = recipe.getIngredients().get(i);
                if (ingredient.isEmpty()) continue;
                int slot = recipe instanceof ShapedRecipe shaped
                        ? i / shaped.getWidth() * size + i % shaped.getWidth() : requirements.size();
                if (slot >= size * size) { requirements.clear(); break; }
                boolean ordinary = key(ingredient) != null;
                valueRecipe &= ordinary;
                if (ordinary) {
                    // 每代仅冻结一次已解析标签成员；求解步骤不调用数据包或自定义原料代码。
                    // Freeze resolved tag membership once per generation.
                    // Solver steps never call data-pack or custom ingredient code.
                    var options = java.util.Arrays.stream(ingredient.getItems()).map(ItemStack::copy).toArray(ItemStack[]::new);
                    for (var option : options) {
                        if (!BuiltInRegistries.ITEM.getKey(option.getItem()).getNamespace().equals("minecraft")) valueRecipe = false;
                        else {
                            var remainder = remainders.computeIfAbsent(option.getItem(), item -> new ItemStack(item).getCraftingRemainingItem().copy());
                            if (!remainder.isEmpty()) {
                                returnedItems.add(remainder.getItem());
                                returnsContainer = true;
                            }
                        }
                    }
                    ingredient = Ingredient.of(options);
                }
                requirements.add(new Requirement(slot, ingredient));
            }
            if (requirements.isEmpty()) return;
            var entry = new Entry((RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder,
                    size, requirements, output);
            byId.put(holder.id(), entry);
            if (returnsContainer) returningRecipes.add(holder.id());
            if (valueRecipe) valueRecipes.add(holder.id());
            byOutput.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(entry);
        }

        /** 同步服务端构造及客户端切片初始化共用此规范化器；每单元在所属游戏线程原子完成，超时被测量而非假装可抢占。
         * The synchronous server constructor and sliced client initialization
         * execute this same normalizer. Each unit finishes atomically on the
         * owning game thread; overruns are measured, not hidden as hard deadlines. */
        static final class Builder {
            private final java.security.MessageDigest modelDigest = RecipeKnowledgeCache.digest();
            private final Map<Object, com.google.gson.JsonObject> componentValues = new HashMap<>();
            private final Index index;
            private final HolderLookup.Provider registries;
            private final java.util.Iterator<RecipeHolder<?>> recipes;
            private final java.util.TreeMap<String, Entry> sorted = new java.util.TreeMap<>();
            private final ArrayList<Entry> ordered = new ArrayList<>(), values = new ArrayList<>();
            private java.util.Iterator<Entry> publishing;
            private final boolean warm;
            private int entryCursor, requirementCursor, processed;
            private long normalizedOptions;
            private boolean done, cancelled;

            Builder(Object identity, Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
                this(new Index(identity), recipes, registries, true);
            }

            private Builder(Index index, Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries, boolean warm) {
                this.index = index;
                this.recipes = recipes.iterator();
                this.registries = registries;
                this.warm = warm;
                RecipeKnowledgeCache.field(modelDigest, "craftable-value-model-v2");
            }

            boolean advance(long nanos, java.util.function.LongSupplier clock) {
                if (nanos <= 0) throw new IllegalArgumentException("Positive build slice required");
                if (done || cancelled) return true;
                long start = clock.getAsLong();
                while (clock.getAsLong() - start < nanos) {
                    if (publishing == null) {
                        if (recipes.hasNext()) {
                            var holder = recipes.next();
                            index.add(holder, registries);
                            var entry = index.byId.get(holder.id());
                            if (entry != null) {
                                sorted.put(holder.id().toString(), entry);
                                for (var requirement : entry.requirements())
                                    normalizedOptions += requirement.ingredient().getItems().length;
                                if (warm && (sorted.size() > RecipeKnowledgeCache.MAX_KEYS
                                        || normalizedOptions > RecipeKnowledgeCache.MAX_REFERENCES))
                                    throw new IllegalStateException("Local catalog exceeds bounds");
                            }
                            processed++;
                            continue;
                        }
                        publishing = sorted.values().iterator();
                    }
                    if (publishing.hasNext()) {
                        var entry = publishing.next();
                        ordered.add(entry);
                        if (index.valueRecipes.contains(entry.id())) values.add(entry);
                        fingerprintEntry(entry);
                        continue;
                    }
                    if (warm && entryCursor < ordered.size()) {
                        var demands = ordered.get(entryCursor).requirements();
                        if (requirementCursor < demands.size()) {
                            index.producing(demands.get(requirementCursor++).ingredient());
                        } else {
                            entryCursor++;
                            requirementCursor = 0;
                        }
                        continue;
                    }
                    // 这些列表由 Builder 独占且发布后不再修改；发布 tick 不再复制/排序整个目录。
                    // Builder exclusively owns these lists and never mutates
                    // them again. No all-catalog copy/sort at the publish tick.
                    index.ordered = java.util.Collections.unmodifiableList(ordered);
                    index.valueOrdered = java.util.Collections.unmodifiableList(values);
                    index.planningFingerprint = RecipeKnowledgeCache.hex(modelDigest);
                    sorted.clear();
                    done = true;
                    return true;
                }
                return false;
            }

            Index result() {
                if (!done || cancelled) throw new IllegalStateException("Catalog is not complete");
                return index;
            }
            int processed() { return processed; }
            void cancel() { cancelled = true; }

            // 两端证明同一规范化模型而非仅配方 ID；每构建单元折叠一个条目，不建第二张图或末尾全量哈希，未知语义显式保留。
            // Both ends attest to the SAME normalized model, not merely recipe
            // IDs. Fold one entry per builder unit; no second graph or final
            // all-catalog hashing pass. Unknown semantics remain explicit.
            private void fingerprintEntry(Entry entry) {
                RecipeKnowledgeCache.field(modelDigest, entry.id().toString());
                RecipeKnowledgeCache.field(modelDigest, entry.gridSize() + ":" + index.valueRecipes.contains(entry.id()));
                fingerprintStack(entry.output());
                for (var demand : entry.requirements()) {
                    RecipeKnowledgeCache.field(modelDigest, "slot:" + demand.slot());
                    var options = key(demand.ingredient());
                    RecipeKnowledgeCache.field(modelDigest, options == null ? "unknown" : options.toString());
                    if (options != null) for (var option : options) {
                        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(option));
                        var remainder = index.remainders.get(item);
                        RecipeKnowledgeCache.field(modelDigest, remainder == null ? "unknown" : "known");
                        if (remainder != null) fingerprintStack(remainder);
                    }
                }
            }

            private void fingerprintStack(ItemStack stack) {
                if (stack.isEmpty()) { RecipeKnowledgeCache.field(modelDigest, "empty"); return; }
                RecipeKnowledgeCache.field(modelDigest, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                RecipeKnowledgeCache.field(modelDigest, Integer.toString(stack.getCount()));
                var key = List.of(stack.getItem(), stack.getComponentsPatch());
                var components = componentValues.get(key);
                if (components == null) {
                    var ops = registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                    components = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(ops,
                            stack.getComponents()).getOrThrow().getAsJsonObject();
                    normalizeComponentSets(components);
                    if (componentValues.size() < 4096) componentValues.put(key, components);
                }
                RecipeKnowledgeCache.json(modelDigest, components);
            }
        }

        List<Entry> producing(Ingredient ingredient) {
            lookups++;
            var alias = aliases.get(ingredient);
            if (alias != null) { hits++; return alias; }
            var key = key(ingredient);
            var result = key == null ? null : knowledge.get(key);
            if (result != null) hits++;
            else {
                result = producingUncached(ingredient);
                if (!retainRelation(key, result)) return result;
            }
            long aliasBytes = 64L + ingredient.getItems().length * 64L;
            if (aliases.size() < 4096 && estimatedBytes + aliasBytes <= MAX_ESTIMATED_BYTES) {
                aliases.put(ingredient, result);
                estimatedBytes += aliasBytes;
            }
            return result;
        }

        /** 仅缓存完整候选集合；缺键或缓存超限绝不意味着无配方，调用者仍返回完整的现算关系。
         * Retain only complete candidate sets; an absent key or full cache never proves no recipe,
         * and the caller still returns the complete freshly computed relation. */
        private boolean retainRelation(List<String> key, List<Entry> result) {
            if (key == null || knowledge.size() >= RecipeKnowledgeCache.MAX_KEYS
                    || optionReferences + key.size() > RecipeKnowledgeCache.MAX_REFERENCES
                    || producerReferences + result.size() > RecipeKnowledgeCache.MAX_REFERENCES) return false;
            long bytes = relationBytes(key, result.size());
            if (estimatedBytes + bytes > MAX_ESTIMATED_BYTES) return false;
            knowledge.put(key, result);
            estimatedBytes += bytes;
            optionReferences += key.size();
            producerReferences += result.size();
            dirty = true;
            return true;
        }

        List<Entry> producingUncached(Ingredient ingredient) {
            builds++;
            var result = new LinkedHashSet<Entry>();
            for (ItemStack option : ingredient.getItems()) {
                for (Entry entry : byOutput.getOrDefault(option.getItem(), List.of())) {
                    if (ingredient.test(entry.output())) result.add(entry);
                }
            }
            return result.stream().sorted(Comparator.comparing(e -> e.id().toString())).toList();
        }

        String fingerprint(MinecraftServer server) {
            if (ordered.size() > RecipeKnowledgeCache.MAX_KEYS) return null;
            try {
                var digest = RecipeKnowledgeCache.digest();
                RecipeKnowledgeCache.field(digest, "craftable-ordinary-knowledge-v2");
                RecipeKnowledgeCache.field(digest, server.getServerVersion());
                RecipeKnowledgeCache.field(digest,
                        net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion.getVersion());
                var ops = server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                long serializedChars = 0;
                for (var entry : ordered) {
                    RecipeKnowledgeCache.field(digest, entry.id().toString());
                    // 包含完整配方数据而非仅 ID/声明产物；下方解析后的普通物品集合还覆盖嵌套成员和空标签变化。
                    // Include full recipe data, not just IDs or declared result.
                    // Resolved ordinary item sets below additionally cover tag
                    // changes, including nested tag membership and empty tags.
                    var data = Recipe.CODEC.encodeStart(ops, entry.holder().value()).getOrThrow();
                    if (data.getAsJsonObject().get("result") instanceof com.google.gson.JsonObject result
                            && result.get("components") instanceof com.google.gson.JsonObject components)
                        normalizeComponentSets(components);
                    serializedChars += data.toString().length();
                    if (serializedChars > RecipeKnowledgeCache.MAX_BYTES) return null;
                    RecipeKnowledgeCache.json(digest, data);
                    var components = net.minecraft.core.component.DataComponentMap.CODEC
                            .encodeStart(ops, entry.output.getComponents()).getOrThrow().getAsJsonObject();
                    normalizeComponentSets(components);
                    RecipeKnowledgeCache.json(digest, components);
                    RecipeKnowledgeCache.field(digest, Integer.toString(entry.gridSize()));
                    for (var requirement : entry.requirements()) {
                        var items = key(requirement.ingredient());
                        if (items == null) {
                            Craftable.LOGGER.info("Craftable knowledge: noncanonical ingredient in {} ({})", entry.id(),
                                    requirement.ingredient().getCustomIngredient() == null ? "size limit"
                                            : requirement.ingredient().getCustomIngredient().getClass().getSimpleName());
                            return null; // 不猜测自定义原料的持久化语义。 / No guessed custom-ingredient persistence.
                        }
                        RecipeKnowledgeCache.field(digest, "slot:" + requirement.slot() + ":" + items.size());
                        items.forEach(item -> RecipeKnowledgeCache.field(digest, item));
                    }
                }
                return RecipeKnowledgeCache.hex(digest);
            } catch (RuntimeException exception) {
                Craftable.LOGGER.info("Craftable knowledge: fingerprint unavailable; memory index only ({})",
                        exception.getClass().getSimpleName());
                return null;
            }
        }

        RecipeKnowledgeCache.Snapshot snapshot() {
            if (fingerprint == null) return null;
            var relations = new HashMap<List<String>, List<String>>();
            var ids = new HashMap<ResourceLocation, String>();
            knowledge.forEach((key, entries) -> relations.put(key, entries.stream()
                    .map(e -> ids.computeIfAbsent(e.id(), ResourceLocation::toString)).toList()));
            return new RecipeKnowledgeCache.Snapshot(fingerprint, relations);
        }

        boolean restore(RecipeKnowledgeCache.Snapshot snapshot) {
            if (snapshot == null || !snapshot.fingerprint().equals(fingerprint)) return false;
            var restored = new HashMap<List<String>, List<Entry>>();
            int options = 0, producers = 0;
            long bytes = 0;
            for (var relation : snapshot.relations().entrySet()) {
                bytes += relationBytes(relation.getKey(), relation.getValue().size());
                if (bytes > MAX_ESTIMATED_BYTES) return false;
                int expected = 0;
                for (String id : relation.getKey()) {
                    var itemId = ResourceLocation.tryParse(id);
                    if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) return false;
                    expected += byOutput.getOrDefault(BuiltInRegistries.ITEM.get(itemId), List.of()).size();
                }
                // 每配方恰有一个索引产物；不交叠物品集合、唯一 ID 及此基数可证明完整性，无须重建去重排序并集。
                // Each recipe has exactly one indexed output item; disjoint
                // item sets + unique IDs + this cardinality prove completeness
                // without rebuilding, deduplicating and sorting the union.
                if (expected != relation.getValue().size()) return false;
                var entries = new ArrayList<Entry>();
                for (String id : relation.getValue()) {
                    var entry = byId.get(ResourceLocation.tryParse(id));
                    if (entry == null || java.util.Collections.binarySearch(relation.getKey(),
                            BuiltInRegistries.ITEM.getKey(entry.output.getItem()).toString()) < 0) return false;
                    entries.add(entry);
                }
                restored.put(relation.getKey(), List.copyOf(entries));
                options += relation.getKey().size();
                producers += entries.size();
            }
            // 不做部分发布：即使校验和正确，每条邻接也须相对当前索引证明完整后才能信任。
            // No partial publication: even a checksummed file is untrusted
            // until every adjacency is proven complete against today's index.
            knowledge.clear();
            aliases.clear();
            knowledge.putAll(restored);
            optionReferences = options;
            producerReferences = producers;
            estimatedBytes = bytes;
            return true;
        }

        private static long relationBytes(List<String> key, int producers) {
            return 128L + producers * 16L + key.stream().mapToLong(id -> 64L + id.length() * 2L).sum();
        }

        static void normalizeComponentSets(com.google.gson.JsonObject components) {
            // MobEffectInstance.Details 的 setOf(EffectCure.CODEC) 跨 JVM 迭代顺序会变（尤其金苹果）。
            // 只规范化这些已知类型路径，不改任意 custom_data 数组或配方输入/效果自身的顺序。
            // MobEffectInstance.Details uses setOf(EffectCure.CODEC). Its
            // iteration order changes between JVMs (notably golden apples).
            // Normalize only those typed paths, NEVER arbitrary custom_data
            // arrays or the order of recipe inputs/effects themselves.
            if (components.get("minecraft:food") instanceof com.google.gson.JsonObject food
                    && food.get("effects") instanceof com.google.gson.JsonArray effects) {
                for (var entry : effects) if (entry.getAsJsonObject().get("effect") instanceof com.google.gson.JsonObject effect)
                    normalizeCures(effect, 0);
            }
            if (components.get("minecraft:potion_contents") instanceof com.google.gson.JsonObject potion
                    && potion.get("custom_effects") instanceof com.google.gson.JsonArray effects) {
                for (var entry : effects) normalizeCures(entry.getAsJsonObject(), 0);
            }
        }

        private static void normalizeCures(com.google.gson.JsonObject effect, int depth) {
            if (depth > 32) throw new IllegalArgumentException("effect nesting");
            if (effect.get("neoforge:cures") instanceof com.google.gson.JsonArray cures) {
                var sorted = new com.google.gson.JsonArray();
                java.util.stream.StreamSupport.stream(cures.spliterator(), false)
                        .map(com.google.gson.JsonElement::getAsString).distinct().sorted().forEach(sorted::add);
                effect.add("neoforge:cures", sorted);
            }
            if (effect.get("hidden_effect") instanceof com.google.gson.JsonObject hidden) normalizeCures(hidden, depth + 1);
        }

        void flush() {
            lastFlush = System.nanoTime();
            if (cache == null || fingerprint == null || !dirty) return;
            try {
                cache.submit(epoch, snapshot());
                dirty = false;
            } catch (RuntimeException exception) {
                Craftable.LOGGER.info("Craftable knowledge: snapshot skipped ({})", exception.getClass().getSimpleName());
            }
        }
    }
}
