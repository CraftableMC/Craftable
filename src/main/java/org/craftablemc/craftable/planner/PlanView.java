package org.craftablemc.craftable.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.recipe.PlanningInput;

/**
 * 有界展示投影，不是第二份计划或可执行图。按路径合并重复批次，混合输入仍保留独立物品项。
 * 图附带精确有序操作，避免分组掩盖批次间配方、成本、产物或返还物的差异。
 * Bounded display projection, not a second plan or an executable graph.
 * Paths aggregate repeated batches; mixed inputs remain separate stack entries.
 * The exact ordered operations accompany the graph so grouping cannot conceal
 * a batch's different recipe, costs, outputs or remainders.
 */
public record PlanView(CraftingResultCode code, boolean workbench, boolean complete,
        int completedBatches, List<Node> nodes, List<Operation> operations,
        List<ItemStack> consumed, List<ItemStack> primary, List<ItemStack> surplus,
        List<ItemStack> drops, List<CraftPlan.Missing> missing) {
    public static final int MAX_NODES = 256;
    public static final int MAX_MISSING = 32;
    private static final int MAX_ALTERNATIVE_OPTIONS = 16_384;
    private static final java.util.regex.Pattern DISPLAY_PATH = java.util.regex.Pattern.compile("0(?:\\.[0-8]){0,12}");

    public PlanView {
        nodes = List.copyOf(nodes);
        operations = List.copyOf(operations);
        consumed = CraftPlan.copies(consumed);
        primary = CraftPlan.copies(primary);
        surplus = CraftPlan.copies(surplus);
        drops = CraftPlan.copies(drops);
        missing = List.copyOf(missing);
        if (nodes.size() > MAX_NODES || operations.size() > SearchBudget.HARD_MAX_STEPS || missing.size() > MAX_MISSING)
            throw new IllegalArgumentException("Oversized plan view");
        for (int i = 0; i < operations.size(); i++)
            for (int origin : operations.get(i).inputOrigins())
                if (origin < -1 || origin >= i) throw new IllegalArgumentException("Invalid display dependency");
    }

    @Override public List<ItemStack> consumed() { return CraftPlan.copies(consumed); }
    @Override public List<ItemStack> primary() { return CraftPlan.copies(primary); }
    @Override public List<ItemStack> surplus() { return CraftPlan.copies(surplus); }
    @Override public List<ItemStack> drops() { return CraftPlan.copies(drops); }

    /** 已审阅值的精确身份，与源 UUID/服务端令牌无关；路线、成本或返还物变化必须重新展示并确认。
     * Exact reviewed values, independent of source UUIDs and server tokens.
     * A changed route/cost/remainder must be shown again before confirmation. */
    public Object reviewIdentity() {
        return List.of(code, workbench, complete, completedBatches,
                operations.stream().map(o -> List.of(o.recipe(), o.path(), CraftPlan.stackKeys(o.inputs()),
                        CraftPlan.stackKeys(List.of(o.output())), CraftPlan.stackKeys(o.remainders()), o.inputOrigins())).toList(),
                CraftPlan.stackKeys(consumed), CraftPlan.stackKeys(primary), CraftPlan.stackKeys(surplus),
                CraftPlan.stackKeys(drops), missing.stream().map(m -> List.of(m.path(), m.count(),
                        CraftPlan.stackKeys(m.alternatives()))).toList());
    }

    public record Candidate(ResourceLocation recipe, ItemStack output, int gridSize, CraftingResultCode rejection) {
        public Candidate {
            if (output.isEmpty() || gridSize < 1 || gridSize > 3) throw new IllegalArgumentException("Invalid recipe candidate");
            output = output.copy();
        }
        @Override public ItemStack output() { return output.copy(); }
    }
    public record Choices(List<Candidate> candidates, boolean truncated) {
        public Choices {
            if (candidates.size() > 16) throw new IllegalArgumentException("Too many recipe candidates");
            candidates = List.copyOf(candidates);
        }
    }

    /** 仅已展示需求可请求候选；候选的工作台/解锁结论不是整体材料可行证明，选择后须由 CraftingService 重规划。
     * Only a displayed demand can request candidates. A candidate's local
     * workstation/lock verdict is not a proof of global material availability;
     * selecting it must replan the whole request through CraftingService. */
    public Choices choices(CraftingRecipes catalog, CraftRequest request, String path) {
        if ("".equals(path)) return new Choices(List.of(), false);
        return choices(catalog.planningInput(), request, path);
    }

    public Choices choices(PlanningInput catalog, CraftRequest request, String path) {
        Node displayed = null;
        for (var node : nodes) if (node.path().equals(path)) { displayed = node; break; }
        if (displayed == null) return new Choices(List.of(), false);
        var found = new java.util.TreeMap<ResourceLocation, CraftingRecipes.Entry>();
        if (path.equals("0")) {
            var root = catalog.find(request.recipe());
            if (root != null) for (var e : catalog.producing(net.minecraft.world.item.crafting.Ingredient.of(root.output())))
                if (ItemStack.isSameItemSameComponents(e.output(), root.output())) found.put(e.id(), e);
        } else {
            String parent = path.substring(0, path.lastIndexOf('.'));
            int slot = Integer.parseInt(path.substring(path.lastIndexOf('.') + 1));
            for (var node : nodes) if (node.path().equals(parent)) for (var id : node.recipes()) {
                var entry = catalog.find(id);
                if (entry == null) continue;
                for (var requirement : entry.requirements()) if (requirement.slot() == slot)
                    for (var e : catalog.producing(requirement.ingredient())) found.put(e.id(), e);
            }
        }
        return new Choices(found.values().stream().limit(SearchBudget.MAX_CANDIDATES)
                .map(e -> new Candidate(e.id(), e.output(), e.gridSize(), catalog.unavailable(e))).toList(),
                found.size() > SearchBudget.MAX_CANDIDATES);
    }

    /** 实际输入 needs 为 AND，alternatives=true 为说明用 OR；reference 仅共享真实/假设批次的规范来源，绝非资源/执行权。
     * needs are AND for actual inputs; alternatives=true is an explanatory OR.
     * reference identifies the canonical producing demand, for display sharing
     * of real or hypothetical batches. It is never resource/execute authority. */
    public record Node(String path, List<ItemStack> needs, List<ItemStack> made,
            List<ResourceLocation> recipes, boolean explanation, boolean alternatives, String reference) {
        public Node {
            if (!DISPLAY_PATH.matcher(path).matches() || needs.size() > 16 || made.size() > 16 || recipes.size() > 16)
                throw new IllegalArgumentException("Invalid display node");
            if (!reference.isEmpty() && (!DISPLAY_PATH.matcher(reference).matches() || reference.equals(path)))
                throw new IllegalArgumentException("Invalid display reference");
            needs = CraftPlan.copies(needs);
            made = CraftPlan.copies(made);
            recipes = List.copyOf(recipes);
        }
        @Override public List<ItemStack> needs() { return CraftPlan.copies(needs); }
        @Override public List<ItemStack> made() { return CraftPlan.copies(made); }
    }

    /** 截断 OR 叶子的可选展示证据，仅来自同一冻结目录；不序列化、不作为配方知识缓存或执行/审阅授权。
     * 没有此证据时，截断叶子必须保持分离。
     * Optional display evidence for capped OR leaves, from the same frozen
     * catalog as this projection. Not serialized, cached as recipe knowledge,
     * or used as execution/review authority. Without it a capped leaf stays separate. */
    public Map<String, Object> alternativeGroups(PlanningInput catalog) {
        var byPath = new java.util.HashMap<String, Node>();
        var parents = new java.util.HashSet<String>();
        for (var node : nodes) {
            byPath.put(node.path(), node);
            int dot = node.path().lastIndexOf('.');
            if (dot >= 0) parents.add(node.path().substring(0, dot));
        }
        var groups = new java.util.HashMap<String, Object>();
        var keys = new java.util.IdentityHashMap<net.minecraft.world.item.crafting.Ingredient, Object>();
        int totalOptions = 0;
        for (var node : nodes) {
            if (!node.alternatives() || node.needs().size() < 16 || !node.recipes().isEmpty()
                    || !node.made().isEmpty() || parents.contains(node.path())) continue;
            int dot = node.path().lastIndexOf('.');
            if (dot < 0) continue;
            var parent = byPath.get(node.path().substring(0, dot));
            if (parent == null || parent.recipes().size() != 1) continue;
            var entry = catalog.find(parent.recipes().getFirst());
            if (entry == null || catalog.unavailable(entry) == CraftingResultCode.UNSUPPORTED_RECIPE) continue;
            int slot = Integer.parseInt(node.path().substring(dot + 1));
            for (var requirement : entry.requirements()) if (requirement.slot() == slot) {
                var options = requirement.ingredient().getItems();
                // 可选展示工作也须有界；不能截短身份，或把可见前缀当作完整集合。
                // Bound this optional presentation work; never truncate an
                // identity or treat the visible prefix as the complete set.
                if (options.length < 16 || options.length > 4096) continue;
                var prefix = CraftPlan.stackKeys(java.util.Arrays.stream(options).limit(16)
                        .map(s -> s.copyWithCount(1)).toList());
                if (!new java.util.HashSet<>(prefix).equals(new java.util.HashSet<>(CraftPlan.stackKeys(
                        node.needs().stream().map(s -> s.copyWithCount(1)).toList())))) continue;
                var key = keys.get(requirement.ingredient());
                if (key == null) {
                    // 可选证明随展示视图保留；除单个 Ingredient 外，总工作量和存储也须设限。
                    // The optional proof is retained with the displayed view;
                    // cap total work/storage too, not just each Ingredient.
                    if (options.length > MAX_ALTERNATIVE_OPTIONS - totalOptions) continue;
                    totalOptions += options.length;
                    key = java.util.Set.copyOf(CraftPlan.stackKeys(java.util.Arrays.stream(options)
                            .map(s -> s.copyWithCount(1)).toList()));
                    keys.put(requirement.ingredient(), key);
                }
                groups.put(node.path(), key);
            }
        }
        return Map.copyOf(groups);
    }

    public record Operation(ResourceLocation recipe, String path, List<ItemStack> inputs,
            ItemStack output, List<ItemStack> remainders, List<Integer> inputOrigins) {
        public Operation {
            if (inputs.size() > 9 || inputOrigins.size() != inputs.size() || remainders.size() > 9 || output.isEmpty()
                    || !DISPLAY_PATH.matcher(path).matches())
                throw new IllegalArgumentException("Invalid displayed operation");
            inputs = CraftPlan.copies(inputs);
            output = output.copy();
            remainders = CraftPlan.copies(remainders);
            inputOrigins = List.copyOf(inputOrigins);
        }
        @Override public List<ItemStack> inputs() { return CraftPlan.copies(inputs); }
        @Override public ItemStack output() { return output.copy(); }
        @Override public List<ItemStack> remainders() { return CraftPlan.copies(remainders); }
    }

    public static PlanView from(CraftingRecipes catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops, List<ItemStack> available) {
        return from(catalog.planningInput(), request, result, drops, available);
    }

    /** 两端展示投影共用同一冻结目录；不创建执行权限，也不调用活配方钩子。
     * Display projection shares the same frozen catalog on either side.
     * It never creates execution authority or calls a live recipe hook. */
    public static PlanView from(PlanningInput catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops, List<ItemStack> available) {
        try { return project(catalog, request, result, drops, available); }
        catch (ArithmeticException oversized) {
            // 展示数量可能聚合多个源或假设批次；溢出时不得变成能提供令牌的完整审阅。
            // Display counts may aggregate many sources or hypothetical batches.
            // Never wrap them into a complete review that could offer a token.
            return limited(catalog);
        }
    }

    private static PlanView project(PlanningInput catalog, CraftRequest request, SearchResult result,
            List<ItemStack> drops, List<ItemStack> available) {
        var nodes = new LinkedHashMap<String, MutableNode>();
        var operations = new ArrayList<Operation>();
        var consumed = new ArrayList<ItemStack>();
        CraftPlan plan = result.plan().orElse(null);
        if (plan != null) {
            for (var step : plan.steps()) {
                var node = node(nodes, step.path());
                if (node == null) return limited(catalog);
                node.actual = true;
                node.fixed = true;
                var output = step.output();
                var remainders = step.remainders();
                ResourceLedger.merge(node.made, output);
                ResourceLedger.merge(node.remaining, output);
                for (var remainder : remainders) ResourceLedger.merge(node.remaining, remainder);
                if (!node.recipes.contains(step.recipe().id())) node.recipes.add(step.recipe().id());
                var inputs = step.inputs();
                for (int slot = 0; slot < inputs.size(); slot++) {
                    if (inputs.get(slot).isEmpty()) continue;
                    var child = node(nodes, step.path() + "." + slot);
                    if (child == null) return limited(catalog);
                    child.actual = true;
                    child.fixed = true;
                    ResourceLedger.merge(child.needs, inputs.get(slot));
                }
                operations.add(new Operation(step.recipe().id(), step.path(), inputs, output, remainders, step.inputOrigins()));
            }
            // 剩余生成数量来自实际步骤引用，不能用相同图标猜测；说明可共享前沿，不可虚构物品或执行步骤。
            // Remaining generated quantities come from actual step references,
            // not a guess based on equal icons. Explanation can share this
            // frontier, but cannot invent extra copies or execution steps.
            for (var op : operations) {
                var inputs = op.inputs();
                var origins = op.inputOrigins();
                for (int slot = 0; slot < inputs.size(); slot++) {
                    int origin = origins.get(slot);
                    if (origin >= 0) {
                        var input = inputs.get(slot);
                        int count = input.getCount();
                        takeStock(nodes.get(operations.get(origin).path()).remaining,
                                net.minecraft.world.item.crafting.Ingredient.of(input), count);
                    }
                }
            }
            for (var extraction : plan.extractions())
                ResourceLedger.merge(consumed, extraction.expected().copyWithCount(extraction.count()));
        }
        var root = catalog.find(request.recipe());
        if (root != null && catalog.unavailable(root) != CraftingResultCode.UNSUPPORTED_RECIPE) {
            var node = node(nodes, "0");
            if (node == null) return limited(catalog);
            ResourceLedger.merge(node.needs, root.output().copyWithCount(
                    Math.multiplyExact(root.output().getCount(), request.batches())));
            // 根被阻塞仍可解释；不能将假设链计入实际成本，成本只来自有序真实计划。
            // A blocked root is still explainable. Do not multiply a made-up
            // chain into actual costs: only the ordered plan contributes those.
            if (!node.actual) {
                node.recipes.add(root.id());
                var stock = new ArrayList<ItemStack>();
                available.forEach(s -> ResourceLedger.merge(stock, s));
                consumed.forEach(s -> takeStock(stock, net.minecraft.world.item.crafting.Ingredient.of(s), s.getCount()));
                if (!explain(catalog, request, nodes, root, "0", new java.util.HashSet<>(), 0, stock, request.batches()))
                    return limited(catalog);
            }
        }
        if (result.missing().size() > MAX_MISSING) return limited(catalog);
        for (var node : nodes.values())
            if (node.needs.size() > 16 || node.made.size() > 16 || node.recipes.size() > 16) return limited(catalog);
        var projectedNodes = new ArrayList<Node>(nodes.size());
        for (var entry : nodes.entrySet()) {
            var value = entry.getValue();
            projectedNodes.add(new Node(entry.getKey(), value.needs, value.made, value.recipes,
                    !value.actual, value.alternatives, value.reference));
        }
        return new PlanView(result.code(), catalog.workbench(), true, plan == null ? 0 : plan.completedBatches(),
                projectedNodes,
                operations, consumed, plan == null ? List.of() : plan.primary(),
                plan == null ? List.of() : plan.surplus(), drops, result.missing());
    }

    private static boolean explain(PlanningInput catalog, CraftRequest request, Map<String, MutableNode> nodes,
            CraftingRecipes.Entry entry, String path, java.util.Set<ResourceLocation> active, int depth, List<ItemStack> stock, int batches) {
        if (depth >= SearchBudget.MAX_DEPTH || !active.add(entry.id())) return true; // 如实保留解释叶节点。 / Honest explanatory leaf.
        for (var requirement : entry.requirements()) {
            String childPath = path + "." + requirement.slot();
            var child = node(nodes, childPath);
            if (child == null) return false;
            if (child.fixed) {
                // 部分前沿已有生产步骤但根消费尚未执行；另附说明需求，不虚构操作或计入净成本。
                // A partial frontier has a producing step, but no executed
                // root consumer yet. Supply its explanatory demand separately
                // without inventing an operation or adding to the net costs.
                if (child.needs.isEmpty()) child.made.stream().filter(requirement.ingredient()::test).findFirst()
                        .ifPresent(s -> child.needs.add(s.copyWithCount(batches)));
                takeStock(child.remaining, requirement.ingredient(), batches);
                continue;
            }
            // 此处只计算展示中的所选路线；假设产物不是操作/提取/库存授权，重复访问仅增加需求而非凭空新增整批。
            // This routine only accounts for the displayed, selected route.
            // Hypothetical output is never an operation, extraction or stock
            // authorization. Repeated visits add demand, not another full batch.
            child.demanded = Math.addExact(child.demanded, batches);
            child.alternatives = true;
            child.needs.clear();
            child.needs.addAll(java.util.Arrays.stream(requirement.ingredient().getItems()).limit(16)
                    .map(s -> s.copyWithCount(child.demanded)).toList());
            var chosen = request.selections().get(childPath);
            boolean shared = false;
            for (var source : nodes.entrySet()) {
                if (child.demanded != batches || !source.getValue().fixed || related(source.getKey(), childPath)
                        || chosen != null && !source.getValue().recipes.contains(chosen)) continue;
                var supplied = takeStock(source.getValue().remaining, requirement.ingredient(), batches);
                if (!supplied.isEmpty()) {
                    child.needs.clear(); child.needs.addAll(supplied); child.alternatives = false; child.actual = true;
                    child.reference = source.getKey(); shared = true; break;
                }
            }
            if (shared) continue;
            long inStock = count(stock, requirement.ingredient());
            var existing = takeStock(stock, requirement.ingredient(), Math.toIntExact(Math.min(batches, inStock)));
            existing.forEach(s -> ResourceLedger.merge(child.available, s));
            int deficit = Math.toIntExact((long) batches - count(existing));
            if (deficit == 0 && child.hypothetical == null && child.reference.isEmpty()) {
                child.needs.clear(); child.needs.addAll(CraftPlan.copies(child.available));
                child.alternatives = false; child.actual = true;
                continue; // 解释复用现有库存，不再次生产。 / Explanation uses available stock; it does not manufacture it again.
            }
            child.actual = false;
            // 优先复用已选生产路线；任一子树存在固定选择时保守禁用共享，避免一项需求悄悄改变另一项选择。
            // Reuse an already selected production route before discovering an
            // alternative. Pins on either subtree conservatively prevent sharing:
            // changing one demand must not silently change another demand's pins.
            Map.Entry<String, MutableNode> supply = null;
            for (var source : nodes.entrySet()) {
                var recipe = source.getValue().hypothetical;
                if (recipe != null && (child.hypothetical == null || child.hypothetical.id().equals(recipe.id()))
                        && !related(source.getKey(), childPath) && !active.contains(recipe.id())
                        && requirement.ingredient().test(recipe.output()) && (chosen == null || chosen.equals(recipe.id()))
                        && (chosen != null || canReuseProducer(catalog, source.getValue(), requirement.ingredient(), deficit, stock))
                        && !hasChildSelection(request, childPath) && !hasChildSelection(request, source.getKey())) {
                    supply = source; break;
                }
            }
            var producers = catalog.producing(requirement.ingredient());
            var actualRecipes = nodes.values().stream().filter(n -> n.actual).flatMap(n -> n.recipes.stream()).toList();
            var inputDeficits = new java.util.HashMap<ResourceLocation, Long>();
            var candidateStock = new int[stock.size()];
            java.util.function.ToLongFunction<CraftingRecipes.Entry> missingInputCount = e -> inputDeficits.computeIfAbsent(
                    e.id(), ignored -> missingInputs(e, deficit, stock, candidateStock));
            var current = child.hypothetical != null && (chosen != null
                    || canReuseProducer(catalog, child, requirement.ingredient(), deficit, stock)) ? child.hypothetical : null;
            var producer = supply != null ? supply.getValue().hypothetical : current != null ? current
                    : chosen == null ? producers.stream().filter(e -> !active.contains(e.id()))
                    .filter(e -> catalog.unavailable(e) != CraftingResultCode.UNSUPPORTED_RECIPE)
                    .filter(e -> !returnsToActiveConversion(catalog, e, active))
                    .filter(e -> !conversionCycleWithoutStock(catalog, e, stock))
                    .filter(e -> !onlyReprocessesAcceptedItems(e, requirement.ingredient()))
                    // 默认草图只说明有用的补料方向，不靠换色增殖需求，也不建议先制造装备再熔回金属粒。
                    // Default sketches explain useful supply paths, not recoloring loops or equipment manufactured solely for recycling.
                    .filter(e -> !catalog.cannotImproveDemand(e, requirement.ingredient(), entry))
                    .filter(e -> !catalog.requiresExistingProcessingInput(e) || missingInputCount.applyAsLong(e) == 0)
                    .min(java.util.Comparator.<CraftingRecipes.Entry, Boolean>comparing(e -> missingInputCount.applyAsLong(e) != 0)
                            .thenComparing(e -> catalog.unavailable(e) != null)
                            .thenComparingLong(missingInputCount)
                            .thenComparing(e -> !actualRecipes.contains(e.id()))
                            .thenComparing(CraftingRecipes.Entry::id)).orElse(null)
                    : catalog.find(chosen);
            if (producer != null && catalog.unavailable(producer) != CraftingResultCode.UNSUPPORTED_RECIPE
                    && requirement.ingredient().test(producer.output()) && !active.contains(producer.id())
                    && (chosen != null || !returnsToActiveConversion(catalog, producer, active))) {
                if (!child.recipes.contains(producer.id())) child.recipes.add(producer.id());
                // 所选配方限定产物变种；继续使用首个原料选项会把橡木标作橡木原木，或选择白桦后仍显示橡木图标。
                // A chosen recipe fixes the output variant. Keeping the first
                // Ingredient option here labels oak wood as oak log, or keeps
                // an oak icon after the player selected birch planks.
                child.needs.clear();
                child.available.forEach(s -> ResourceLedger.merge(child.needs, s));
                long needed = (long) child.demanded - count(child.available);
                if (needed > 0) ResourceLedger.merge(child.needs, producer.output().copyWithCount(Math.toIntExact(needed)));
                child.alternatives = false;
                String sourcePath = supply == null ? childPath : supply.getKey();
                var source = supply == null ? child : supply.getValue();
                source.hypothetical = producer;
                long remaining = count(source.remaining, requirement.ingredient());
                long requiredBatches = (Math.max(0L, (long) deficit - remaining)
                        + producer.output().getCount() - 1L) / producer.output().getCount();
                // 解释工作受固定表示上限约束，不授权绕过世界可能更小的步骤限额。
                // Explanatory work has a fixed representation bound, not a
                // grant to execute under a world's possibly smaller step cap.
                if (requiredBatches > SearchBudget.HARD_MAX_STEPS - source.hypotheticalBatches) return false;
                int upstreamBatches = Math.toIntExact(requiredBatches);
                if (upstreamBatches > 0) {
                    source.hypotheticalBatches = Math.addExact(source.hypotheticalBatches, upstreamBatches);
                    if (!explain(catalog, request, nodes, producer, sourcePath, active,
                            sourcePath.split("\\.").length - 1, stock, upstreamBatches)) return false;
                    ResourceLedger.merge(source.remaining, producer.output().copyWithCount(
                            Math.multiplyExact(upstreamBatches, producer.output().getCount())));
                }
                takeStock(source.remaining, requirement.ingredient(), deficit);
                if (supply != null) child.reference = sourcePath;
            }
        }
        active.remove(entry.id());
        return true;
    }

    private static boolean related(String a, String b) {
        return a.equals(b) || a.startsWith(b + ".") || b.startsWith(a + ".");
    }

    private static boolean hasChildSelection(CraftRequest request, String path) {
        return request.selections().keySet().stream().anyMatch(p -> p.startsWith(path + "."));
    }

    /** 已用掉的回收装备不能支撑下一份铁粒。 / Gear already counted by the sketch cannot supply the next nugget. */
    private static boolean canReuseProducer(PlanningInput catalog, MutableNode source,
            net.minecraft.world.item.crafting.Ingredient demand, int deficit, List<ItemStack> stock) {
        var producer = source.hypothetical;
        if (!catalog.requiresExistingProcessingInput(producer)) return true;
        long missing = Math.max(0L, (long) deficit - count(source.remaining, demand));
        int yield = producer.output().getCount();
        return count(stock, producer.requirements().getFirst().ingredient()) >= (missing + yield - 1) / yield;
    }

    private static boolean returnsToActiveConversion(PlanningInput catalog, CraftingRecipes.Entry producer,
            java.util.Set<ResourceLocation> active) {
        // 复用求解器的精确逆变换定义；默认解释不应重建刚建议拆解的铁块，但不裁剪真实计划或明确选中配方。
        // Reuse the solver's exact inverse definition. A default explanation
        // must not rebuild the iron block it just proposed breaking. Actual
        // plans and explicitly selected recipes are not pruned by this rule.
        return active.stream().map(catalog::find).anyMatch(a -> catalog.isExactInverse(a, producer));
    }

    // 这仅简化展示，不判定可行性；不能经存储循环把缺少钻石解释成还缺九个钻石。
    // This is only a display simplification, never a feasibility verdict. Do
    // not explain missing diamonds as nine more diamonds via a storage cycle.
    private static boolean conversionCycleWithoutStock(PlanningInput catalog, CraftingRecipes.Entry producer,
            List<ItemStack> stock) {
        return !producer.requirements().isEmpty() && producer.requirements().stream().allMatch(r ->
                stock.stream().noneMatch(s -> !s.isEmpty() && r.ingredient().test(s))
                && !catalog.producing(r.ingredient()).isEmpty()
                && catalog.producing(r.ingredient()).stream().allMatch(back -> !back.requirements().isEmpty()
                        && back.requirements().stream().allMatch(input -> input.ingredient().test(producer.output()))));
    }

    /** 默认说明不必为可接受原木/木头的需求，先把四原木转成三个木头；原始需求可诚实保留为叶子。
     * 显式选择和真实可执行步骤仍展示转换，有增益配方不适用此展示捷径。
     * Default explanation need not turn four accepted logs into three accepted
     * wood blocks just to feed a log/wood ingredient. Keep the raw demand as an
     * honest leaf. Explicit selections and real executable steps still show the
     * conversion, and gaining recipes are not covered by this display shortcut. */
    private static boolean onlyReprocessesAcceptedItems(CraftingRecipes.Entry producer,
            net.minecraft.world.item.crafting.Ingredient demand) {
        return !producer.requirements().isEmpty() && producer.output().getCount() <= producer.requirements().size()
                && producer.requirements().stream().allMatch(r -> r.ingredient().getItems().length > 0
                        && java.util.Arrays.stream(r.ingredient().getItems()).allMatch(demand::test));
    }

    /** 整批数量一次扣工作表；生产者间共用数量数组，不复制物品栈、不逐批循环或另做搜索。
     * Deduct whole-batch counts in a reusable quantity table: no stack copies, per-batch loops or extra search. */
    private static long missingInputs(CraftingRecipes.Entry producer, int deficit, List<ItemStack> stock, int[] remaining) {
        int batches = Math.toIntExact(((long) deficit + producer.output().getCount() - 1) / producer.output().getCount());
        for (int i = 0; i < stock.size(); i++) remaining[i] = stock.get(i).getCount();
        long missing = 0;
        for (var requirement : producer.requirements()) {
            int needed = batches;
            for (int i = 0; i < stock.size() && needed > 0; i++) {
                if (remaining[i] <= 0 || !requirement.ingredient().test(stock.get(i))) continue;
                int taken = Math.min(needed, remaining[i]);
                remaining[i] -= taken;
                needed -= taken;
            }
            missing = Math.addExact(missing, needed);
        }
        return missing;
    }

    private static List<ItemStack> takeStock(List<ItemStack> stock, net.minecraft.world.item.crafting.Ingredient ingredient, int count) {
        if (count(stock, ingredient) < count) return List.of();
        var used = new ArrayList<ItemStack>();
        for (var stack : stock) if (!stack.isEmpty() && ingredient.test(stack)) {
            int taken = Math.min(count, stack.getCount());
            if (taken == 0) break;
            ResourceLedger.merge(used, stack.copyWithCount(taken)); stack.shrink(taken); count -= taken;
        }
        return used;
    }

    private static long count(List<ItemStack> stacks) {
        return stacks.stream().mapToLong(ItemStack::getCount).sum();
    }

    private static long count(List<ItemStack> stacks, net.minecraft.world.item.crafting.Ingredient ingredient) {
        return stacks.stream().filter(ingredient::test).mapToLong(ItemStack::getCount).sum();
    }

    public static PlanView failed(CraftingResultCode code) {
        return new PlanView(code, false, false, 0, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlanView limited(PlanningInput catalog) {
        return new PlanView(CraftingResultCode.SEARCH_BUDGET_EXCEEDED, catalog.workbench(), false, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static MutableNode node(Map<String, MutableNode> nodes, String path) {
        if (nodes.containsKey(path)) return nodes.get(path);
        if (nodes.size() >= MAX_NODES) return null;
        var result = new MutableNode();
        nodes.put(path, result);
        return result;
    }

    private static final class MutableNode {
        final List<ItemStack> needs = new ArrayList<>(), made = new ArrayList<>();
        final List<ItemStack> remaining = new ArrayList<>();
        final List<ItemStack> available = new ArrayList<>();
        final List<ResourceLocation> recipes = new ArrayList<>();
        boolean actual, alternatives, fixed;
        int demanded, hypotheticalBatches;
        CraftingRecipes.Entry hypothetical;
        String reference = "";
    }
}
