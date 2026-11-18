package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.ResourceLedger;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/**
 * 只审阅给定路线，不搜索其它路线；在请求的共用预算内重建真实配方产出与返还物。
 * 账本逐份验证来源、贡献和祖先关系；客户端路径标签不构成材料或执行授权。
 * Review a supplied route without discovering alternatives; rebuild actual outputs/remainders within the shared request budget.
 * The ledger verifies sources, contributions and ancestors; client path labels grant neither materials nor execution authority.
 */
final class CraftWitnessValidator {
    private CraftWitnessValidator() {}

    static CraftPlan validate(CraftingRecipes recipes, CraftRequest request, CraftPlan.Witness witness,
            List<ResourceLedger.Source> sources, SearchBudget budget, int maxPlanSteps) {
        // 编解码的绝对上限只保护表示；分配重放账本前仍须遵守当前世界的更小上限。
        // The codec ceiling protects representation only; honor the current world's smaller limit before allocating replay state.
        SearchBudget.requireStepLimit(maxPlanSteps);
        require(witness.steps().size() <= maxPlanSteps);
        require(!request.partial());
        var ledger = new ResourceLedger(sources);
        var supported = recipes.planningInput().restrict(request.policy());
        var steps = new ArrayList<CraftPlan.Step>();
        var recipesByPath = new HashMap<String, Map<ResourceLocation, CraftingRecipes.Entry>>();
        int[] usedOutputs = new int[witness.steps().size()];
        boolean[] contributes = new boolean[usedOutputs.length];
        boolean[] anchored = new boolean[usedOutputs.length];
        int roots = 0;
        boolean safePartial = true;
        for (var claimed : witness.steps()) {
            require(budget.alive());
            var entry = recipes.find(claimed.recipe());
            require(recipes.unavailable(entry) == null);
            require(supported.unavailable(entry) == null);
            boolean root = claimed.path().equals("0");
            if (root) {
                require(claimed.recipe().equals(request.recipe()));
                roots++;
            } else if (request.selections().containsKey(claimed.path())) {
                require(request.selections().get(claimed.path()).equals(claimed.recipe()));
            }
            var grid = claimed.inputs();
            require(grid.size() == entry.gridSize() * entry.gridSize());
            boolean[] occupied = new boolean[grid.size()];
            for (var requirement : entry.requirements()) {
                occupied[requirement.slot()] = true;
                require(requirement.ingredient().test(grid.get(requirement.slot())));
            }
            for (int slot = 0; slot < grid.size(); slot++) {
                var input = grid.get(slot);
                require(occupied[slot] == !input.isEmpty());
                if (input.isEmpty()) continue;
                var pinned = request.selections().get(claimed.path() + "." + slot);
                if (pinned != null) {
                    var choice = recipes.find(pinned);
                    require(choice != null
                            && ItemStack.isSameItemSameComponents(choice.output(), input));
                }
                int origin = claimed.origins().get(slot);
                var binding = ledger.consume(input, origin, origin < 0 ? claimed.references().get(slot).toString() : null);
                if (origin >= 0 && !binding.remainder()) {
                    // 父需求可先预留产物，但在子步骤之后执行；锚定真实需求边，而非重放时首次使用的位置。
                    // A parent may reserve a producer's output before a child
                    // uses its surplus, but the parent executes AFTER that child.
                    // Anchor to an actual demand edge, not first replayed use.
                    if (steps.get(origin).path().equals(claimed.path() + "." + slot)) anchored[origin] = true;
                    usedOutputs[origin]++;
                }
            }
            var actual = recipes.assemble(entry, claimed.path(), grid, claimed.origins());
            require(actual != null && budget.alive());
            int index = steps.size();
            steps.add(actual);
            var pathRecipes = recipesByPath.get(claimed.path());
            if (pathRecipes == null) {
                pathRecipes = new HashMap<>();
                recipesByPath.put(claimed.path(), pathRecipes);
            }
            pathRecipes.putIfAbsent(entry.id(), entry);
            var output = actual.output();
            var remainders = actual.remainders();
            ledger.produce(output, root, false, actual.path(), index);
            for (var remainder : remainders) ledger.produce(remainder, false, true, actual.path(), index);
            safePartial &= supported.allowDirectPreparation(entry);
            contributes[index] = root;
        }
        require(roots == request.batches());
        // 反向追踪真实贡献而非信任路径文字，禁止夹带不服务于根请求的处理步骤。
        // Trace actual provenance backwards, not path text, to reject processing unrelated to the requested root batches.
        for (int i = steps.size() - 1; i >= 0; i--) {
            require(contributes[i]);
            if (!steps.get(i).path().equals("0")) require(usedOutputs[i] > 0 && anchored[i]);
            for (int origin : steps.get(i).inputOrigins()) if (origin >= 0) contributes[origin] = true;
        }
        // 允许批次取整，但不允许多做整批可移除的同种中间物；这是有界收支验证，不是第二套优化器/重搜。
        // 同一路径可能包含多个批次及配方，不能只留一个条目而抹掉被禁止的祖先关系。
        // Batch rounding is allowed; a whole removable batch of identical
        // intermediate output is not. This is bounded accounting, not a second
        // optimizer or a search for a cheaper alternative recipe.
        // Paths may contain several batches and several selected recipes. A
        // single entry per path would silently erase a forbidden ancestor.
        for (var path : recipesByPath.entrySet()) {
            int separator = path.getKey().lastIndexOf('.');
            boolean direct = true;
            while (separator >= 0) {
                require(budget.alive());
                String ancestorPath = path.getKey().substring(0, separator);
                var ancestors = recipesByPath.get(ancestorPath);
                if (ancestors != null) for (var descendant : path.getValue().values()) {
                    require(!ancestors.containsKey(descendant.id()));
                    if (direct) for (var ancestor : ancestors.values())
                        require(!supported.isExactInverse(ancestor, descendant) && budget.alive());
                }
                separator = ancestorPath.lastIndexOf('.');
                direct = false;
            }
        }
        validateIntermediateOutputs(steps, usedOutputs, budget);
        var plan = new CraftPlan(request.recipe(), request.batches(), roots, steps, ledger.extractions(),
                ledger.delivery(true), ledger.delivery(false), List.of(), safePartial);
        require(plan.hasMaterialChange());
        return plan;
    }

    /**
     * 最终整批余量约束，不能取代此前逐来源/配方/贡献/祖先校验。
     * Final batch-surplus accounting, never a substitute for source/recipe/provenance/ancestor validation above.
     */
    private static void validateIntermediateOutputs(List<CraftPlan.Step> steps, int[] usedOutputs, SearchBudget budget) {
        var groupsByItem = new HashMap<Item, List<OutputGroup>>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            require(budget.alive());
            if (step.path().equals("0")) continue;
            var output = step.output();
            var groups = groupsByItem.get(output.getItem());
            if (groups == null) {
                groups = new ArrayList<>();
                groupsByItem.put(output.getItem(), groups);
            }
            OutputGroup group = null;
            for (var candidate : groups) {
                require(budget.alive());
                if (ItemStack.isSameItemSameComponents(candidate.output, output)) { group = candidate; break; }
            }
            if (group == null) { group = new OutputGroup(output); groups.add(group); }
            group.spare = Math.addExact(group.spare, output.getCount() - usedOutputs[i]);
            group.minimum = Math.min(group.minimum, output.getCount());
        }
        for (var groups : groupsByItem.values()) for (var group : groups)
            require(group.spare < group.minimum && budget.alive());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid or expired crafting witness");
    }

    private static final class OutputGroup {
        final ItemStack output;
        int spare, minimum = Integer.MAX_VALUE;
        OutputGroup(ItemStack output) { this.output = output; }
    }
}
