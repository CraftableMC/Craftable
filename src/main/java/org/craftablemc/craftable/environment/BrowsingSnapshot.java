package org.craftablemc.craftable.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.ResourceLedger;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/**
 * 已授权浏览的纯值副本，无世界位置、容器名、活句柄或外部槽号；可安全供客户端同核规划。
 * 来源引用是不透明身份，不是提取能力；租约/连接校验由拥有者承担，快照自身不授权执行。
 * Authorized browsing value copies without positions, container names, live handles or external slot numbers, for shared client planning.
 * Source references are opaque identities, not extraction capabilities; the owner checks lease/connection, and snapshots cannot authorize execution.
 */
public record BrowsingSnapshot(UUID session, long recipes, long resources, boolean workbench,
        boolean limitedCrafting, Set<ResourceLocation> unlocked, boolean allowDrops,
        int maxBatches, int maxPlanSteps,
        org.craftablemc.craftable.config.DirectPreparationPolicy directPreparation, int inventoryMaximum,
        List<ItemStack> inventory, List<Input> inputs, int furnaceMask, boolean timeCompression, int maxFurnaceBatches) {
    public static final int MAX_INPUTS = 4096;
    public static final int MAX_UNLOCKED = 16_384;

    public BrowsingSnapshot {
        java.util.Objects.requireNonNull(session);
        java.util.Objects.requireNonNull(directPreparation);
        org.craftablemc.craftable.planner.SearchBudget.requireStepLimit(maxPlanSteps);
        if (recipes < 0 || resources < 0 || maxBatches < 1 || maxBatches > CraftRequest.MAX_BATCHES
                || inventoryMaximum < 1 || inventoryMaximum > 99 || inventory.size() != 36
                || inputs.size() > MAX_INPUTS || unlocked.size() > MAX_UNLOCKED)
            throw new IllegalArgumentException("Invalid browsing snapshot");
        if ((furnaceMask & ~org.craftablemc.craftable.workstation.WorkstationCapability.COOKING_MASK) != 0
                || maxFurnaceBatches < 1 || maxFurnaceBatches > org.craftablemc.craftable.config.CraftableServerConfig.MAX_MAX_FURNACE_BATCHES)
            throw new IllegalArgumentException("Invalid furnace browsing rules");
        unlocked = Set.copyOf(unlocked);
        inventory = CraftPlan.copies(inventory);
        inputs = List.copyOf(inputs);
        var references = new HashSet<UUID>();
        var inventorySlots = new HashSet<Integer>();
        for (var input : inputs) {
            if (!references.add(input.reference())) throw new IllegalArgumentException("Duplicate source reference");
            if (input.inventorySlot() >= 0 && (!inventorySlots.add(input.inventorySlot())
                    || !ItemStack.matches(input.stack(), inventory.get(input.inventorySlot()))))
                throw new IllegalArgumentException("Source does not match inventory capacity");
        }
        // 自身背包本就由原版同步；容量必须保留准确组件，普通占位栈会虚构可合并空间。
        // 外部受保护栈不进入 inputs，也不泄露组件数据。
        // Vanilla already syncs the player's inventory; exact components prevent fictitious merge space from plain placeholders.
        // Protected external stacks never enter inputs or expose component data.
    }

    /** 纯合成夹具/调用者不声明任何炉能力；有炉的运行路径必须显式传入新字段。 / Crafting-only callers claim no cooking capability; cooking runtime paths must supply the extra fields explicitly. */
    public BrowsingSnapshot(UUID session, long recipes, long resources, boolean workbench,
            boolean limitedCrafting, Set<ResourceLocation> unlocked, boolean allowDrops,
            int maxBatches, int maxPlanSteps, org.craftablemc.craftable.config.DirectPreparationPolicy directPreparation,
            int inventoryMaximum, List<ItemStack> inventory, List<Input> inputs) {
        this(session, recipes, resources, workbench, limitedCrafting, unlocked, allowDrops,
                maxBatches, maxPlanSteps, directPreparation, inventoryMaximum, inventory, inputs, 0, true,
                org.craftablemc.craftable.config.CraftableServerConfig.DEFAULT_MAX_FURNACE_BATCHES);
    }

    @Override public List<ItemStack> inventory() { return CraftPlan.copies(inventory); }

    public List<ResourceLedger.Source> sources() {
        return inputs.stream().map(input -> new ResourceLedger.Source(input.reference().toString(),
                Math.max(0, input.inventorySlot()), input.stack())).toList();
    }

    public Set<String> playerReferences() {
        return inputs.stream().filter(input -> input.inventorySlot() >= 0).map(input -> input.reference().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> references() {
        return inputs.stream().map(input -> input.reference().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 完整内容身份排除资源编号和租约/扫描时间，使同值续租不重算；服务端仍另验私有来源绑定。
     * Exact content identity excludes resource numbering and lease/scan time to reuse same-value math; server source bindings are checked separately.
     */
    public Object contentIdentity() {
        return List.of(recipes, workbench, limitedCrafting, unlocked, allowDrops,
                maxBatches, maxPlanSteps, directPreparation, furnaceMask, timeCompression, maxFurnaceBatches,
                inventoryMaximum, CraftPlan.stackKeys(inventory), inputs.stream()
                        .map(input -> List.of(input.reference(), input.inventorySlot(),
                                CraftPlan.stackKeys(List.of(input.stack())))).toList());
    }

    public record Input(UUID reference, int inventorySlot, ItemStack stack) {
        public Input {
            java.util.Objects.requireNonNull(reference);
            if (inventorySlot < -1 || inventorySlot >= 36 || stack.isEmpty()
                    || stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()
                    || CraftingRecipes.protectedStack(stack))
                throw new IllegalArgumentException("Invalid authorized source");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }
}
