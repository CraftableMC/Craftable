package org.craftablemc.craftable.execution;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.craftablemc.craftable.environment.EndpointKind;
import org.craftablemc.craftable.environment.EnvironmentSnapshot;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.api.CraftingResultCode;

/**
 * 以副本联合模拟提取、主产物与余料，仅发布玩家 36 个主背包槽。
 * 纯值模拟两端共用；真实写入只由服务端事务调用 publish，模拟成功本身不授予权限。
 * Simulate extraction, primary output and surplus together on copies of the 36 main inventory slots.
 * Both sides share value arithmetic; only the server transaction publishes, and a fit is not authorization.
 */
public final class MainInventoryInsertion {
    private static final int MAIN_SLOT_COUNT = 36;

    private MainInventoryInsertion() {}

    static Delivery simulate(Inventory inventory, CraftPlan plan, EnvironmentSnapshot snapshot, boolean allowDrops) {
        var mainSlots = copyMainInventory(inventory);
        int maximum = inventory.getMaxStackSize();
        var playerEndpoints = new java.util.HashSet<String>();
        var endpoints = new java.util.HashSet<String>();
        for (var endpoint : snapshot.endpoints()) {
            endpoints.add(endpoint.id());
            if (endpoint.kind() == EndpointKind.PLAYER) playerEndpoints.add(endpoint.id());
        }
        return simulate(mainSlots, maximum, plan, playerEndpoints, endpoints, allowDrops);
    }

    /**
     * 不修改输入；来源集合区分主背包与外部输入，不能把工作台/光标的消耗误算成背包空位。
     * Does not mutate inputs; source sets distinguish main inventory from external inputs, including grid/cursor slots.
     */
    public static Delivery simulate(List<ItemStack> mainSlots, int maximum, CraftPlan plan,
            java.util.Set<String> playerEndpoints, java.util.Set<String> endpoints, boolean allowDrops) {
        List<ItemStack> simulated = new ArrayList<>(CraftPlan.copies(mainSlots));
        for (var extraction : plan.extractions()) {
            if (!endpoints.contains(extraction.endpointId())) return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
            if (playerEndpoints.contains(extraction.endpointId())) {
                if (extraction.slot() < 0 || extraction.slot() >= simulated.size())
                    return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
                var stack = simulated.get(extraction.slot());
                if (stack.getCount() < extraction.count()) return Delivery.failed(CraftingResultCode.ENVIRONMENT_CHANGED);
                stack.shrink(extraction.count());
            }
        }
        return fit(simulated, maximum, plan.primary(), plan.surplus(), allowDrops);
    }

    static Delivery fit(List<ItemStack> slots, int maximum, List<ItemStack> primary,
            List<ItemStack> surplus, boolean allowDrops) {
        var working = new ArrayList<>(CraftPlan.copies(slots));
        // 主产物按来源用途判定：部分准备的四根木棍是这次请求的目的，不是可丢弃的镐子“余料”。
        // Primary is provenance-based: the four sticks being prepared are the
        // purpose of a partial request, not disposable "surplus" of a pickaxe.
        if (!insertAll(working, maximum, primary)) return Delivery.failed(CraftingResultCode.NO_OUTPUT_SPACE);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack stack : surplus) {
            ItemStack remaining = stack.copy();
            mergeIntoExisting(working, maximum, remaining);
            fillEmptySlots(working, maximum, remaining);
            if (!remaining.isEmpty() && !allowDrops) return Delivery.failed(CraftingResultCode.NO_OUTPUT_SPACE);
            while (!remaining.isEmpty()) {
                if (drops.size() >= 32) return Delivery.failed(CraftingResultCode.DROP_LIMIT_EXCEEDED);
                drops.add(remaining.split(Math.min(remaining.getMaxStackSize(), remaining.getCount())));
            }
        }
        return new Delivery(null, working, drops);
    }

    // 发布完整模拟结果，不执行逐步合成；事务负责之前的新鲜复验与失败补偿。
    // Publish the entire simulated result, not individual crafts; the transaction owns fresh validation and rollback.
    static void publish(Inventory inventory, Delivery delivery) {
        if (delivery.failure() != null) throw new IllegalArgumentException("Cannot publish failed delivery");
        var slots = delivery.slots();
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) inventory.setItem(slot, slots.get(slot));
    }

    /** 防御副本隔离模拟、提交和通知。 / Defensive copies isolate simulation, publication and observers. */
    public record Delivery(CraftingResultCode failure, List<ItemStack> slots, List<ItemStack> drops) {
        public Delivery {
            slots = CraftPlan.copies(slots);
            drops = CraftPlan.copies(drops);
        }
        @Override public List<ItemStack> slots() { return CraftPlan.copies(slots); }
        @Override public List<ItemStack> drops() { return CraftPlan.copies(drops); }
        static Delivery failed(CraftingResultCode code) { return new Delivery(code, List.of(), List.of()); }
    }

    /** 全部可放入才写回 slots。 / Mutates {@code slots} only when every produced stack fits. */
    static boolean insertAll(List<ItemStack> slots, int containerMaxStackSize, List<ItemStack> producedItems) {
        List<ItemStack> working = new ArrayList<>(slots.size());
        for (ItemStack slot : slots) working.add(slot.copy());
        for (ItemStack produced : producedItems) {
            ItemStack remaining = produced.copy();
            mergeIntoExisting(working, containerMaxStackSize, remaining);
            fillEmptySlots(working, containerMaxStackSize, remaining);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        for (int slot = 0; slot < slots.size(); slot++) {
            slots.set(slot, working.get(slot));
        }
        return true;
    }

    private static void mergeIntoExisting(
            List<ItemStack> slots, int containerMaxStackSize, ItemStack remaining) {
        for (ItemStack existing : slots) {
            if (remaining.isEmpty()) {
                return;
            }
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int maximum = Math.min(containerMaxStackSize, existing.getMaxStackSize());
            int moved = Math.min(Math.max(0, maximum - existing.getCount()), remaining.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
        }
    }

    private static void fillEmptySlots(
            List<ItemStack> slots, int containerMaxStackSize, ItemStack remaining) {
        for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
            if (!slots.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(
                    Math.min(containerMaxStackSize, remaining.getMaxStackSize()), remaining.getCount());
            slots.set(slot, remaining.split(moved));
        }
    }

    static List<ItemStack> copyMainInventory(Inventory inventory) {
        List<ItemStack> copy = new ArrayList<>(MAIN_SLOT_COUNT);
        for (int slot = 0; slot < MAIN_SLOT_COUNT; slot++) {
            copy.add(inventory.getItem(slot).copy());
        }
        return copy;
    }
}
