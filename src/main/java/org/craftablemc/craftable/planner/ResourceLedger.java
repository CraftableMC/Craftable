package org.craftablemc.craftable.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.craftablemc.craftable.recipe.CraftingRecipes;

/**
 * 分支独占的数量账本，基于复制后的源指纹，绝不修改容器；仅供所属游戏线程串行使用。
 * 分支复制共享只读源和批次物品身份，但复制可用/消耗数量；返回物品也不暴露内部栈。
 * Branch-local counts over copied source fingerprints; never mutates containers and is confined
 * to its owning game thread. Branch copies share read-only source/lot identities, copy mutable
 * availability/consumption counts, and do not expose internal stacks in returned values.
 */
public final class ResourceLedger {
    private final List<Source> sources;
    private final int[] available;
    private final int[] consumed;
    private final List<Lot> generated;

    public ResourceLedger(List<Source> sources) {
        this.sources = List.copyOf(sources);
        this.available = new int[sources.size()];
        this.consumed = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            if (!CraftingRecipes.protectedStack(sources.get(i).stack)) available[i] = sources.get(i).stack.getCount();
        }
        this.generated = new ArrayList<>();
    }

    private ResourceLedger(ResourceLedger from) {
        sources = from.sources;
        available = from.available.clone();
        consumed = from.consumed.clone();
        generated = new ArrayList<>(from.generated.size());
        for (Lot lot : from.generated) generated.add(lot.copy());
    }

    ResourceLedger copy() { return new ResourceLedger(this); }
    long retainedBytes() { return 128L + available.length * 8L + generated.size() * 256L; }
    long identityBytes() { return 128L + available.length * 32L + generated.size() * 384L; }

    List<Taken> choices(Ingredient ingredient) {
        List<Taken> result = new ArrayList<>();
        var seen = new LinkedHashSet<Key>();
        boolean singleDefaultItem = ingredient.isSimple() && ingredient.getItems().length == 1;
        // 先复用中间批次余量，再生产或消耗更多材料。
        // Reuse intermediate batch surplus before manufacturing/consuming more.
        for (int i = 0; i < generated.size(); i++) {
            Lot lot = generated.get(i);
            if (lot.available > 0 && !lot.terminal && !CraftingRecipes.protectedStack(lot.stack) && ingredient.test(lot.stack) && seen.add(Key.of(lot.stack))) {
                result.add(new Taken(-1, i, lot.stack.copyWithCount(1)));
                if (singleDefaultItem) return result;
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).stack;
            if (available[i] > 0 && ingredient.test(stack) && seen.add(Key.of(stack))) {
                result.add(new Taken(i, -1, stack.copyWithCount(1)));
                if (singleDefaultItem) return result;
            }
        }
        return result;
    }

    boolean has(Ingredient ingredient) {
        for (Lot lot : generated) if (lot.available > 0 && !lot.terminal && !CraftingRecipes.protectedStack(lot.stack) && ingredient.test(lot.stack)) return true;
        for (int i = 0; i < available.length; i++) {
            if (available[i] > 0 && ingredient.test(sources.get(i).stack)) return true;
        }
        return false;
    }

    /** 按候选配方需求计数，包含未被占用的中间产物。到达上限后停止，不用于否定证明。
     * Count up to a producer's demand, including unreserved intermediate stock. This is a ranking hint, not exclusion evidence. */
    int availableUpTo(Ingredient ingredient, int cap) {
        int count = 0;
        for (var lot : generated) {
            if (lot.available > 0 && !lot.terminal && !CraftingRecipes.protectedStack(lot.stack) && ingredient.test(lot.stack))
                count += Math.min(cap - count, lot.available);
            if (count == cap) return cap;
        }
        for (int slot = 0; slot < available.length; slot++) {
            if (available[slot] > 0 && ingredient.test(sources.get(slot).stack))
                count += Math.min(cap - count, available[slot]);
            if (count == cap) return cap;
        }
        return count;
    }

    /** 当前可用数量；根操作下界仅在初始账本、尚未预留网格或生成产物时调用。
     * Current usable count; the root step floor calls this only on the
     * initial ledger, before any grid reservations or generated production. */
    long quantity(Ingredient ingredient) {
        long count = 0;
        for (int i = 0; i < available.length; i++)
            if (available[i] > 0 && ingredient.test(sources.get(i).stack)) count += available[i];
        return count;
    }

    boolean hasProtected(Ingredient ingredient) {
        return sources.stream().anyMatch(s -> CraftingRecipes.protectedStack(s.stack) && ingredient.test(s.stack));
    }

    java.util.Set<Item> availableItems() {
        var result = new java.util.HashSet<Item>();
        for (int i = 0; i < sources.size(); i++) if (available[i] > 0) result.add(sources.get(i).stack.getItem());
        return result;
    }

    void take(Taken taken) {
        if (taken.source >= 0) {
            if (--available[taken.source] < 0) throw new IllegalStateException("Double source reservation");
            consumed[taken.source]++;
        } else if (--generated.get(taken.lot).available < 0) {
            throw new IllegalStateException("Double generated reservation");
        }
    }

    void reserveFrontier(Taken taken) {
        // 被阻塞的父配方未消耗原料；原物品仍在原槽，但必须为当前批次保留，避免多批次重复计数。
        // A blocked parent did not consume its inputs. Existing inputs stay in
        // their original slots, but remain reserved for this requested batch:
        // restoring availability would count the same two sticks for every
        // missing pickaxe in a multi-batch partial request.
        if (taken.source >= 0) {
            if (--consumed[taken.source] < 0) throw new IllegalStateException("Invalid frontier release");
        } else {
            Lot lot = generated.get(taken.lot);
            lot.reserved++;
            lot.primary = true;
        }
    }

    int origin(Taken taken) { return taken == null || taken.source >= 0 ? -1 : generated.get(taken.lot).origin; }

    public int produce(ItemStack stack, boolean terminal, boolean remainder, String path, int origin) {
        if (stack.isEmpty()) return -1;
        // 每个表示步骤最多一个产物和九个返还物；搜索/重放另行限制运行时步骤，此独立界也保护单独账本且不增内存预算。
        // One output and at most nine remainders per represented step. Runtime
        // limits are enforced by search/replay; this independent bound also
        // protects standalone ledgers and never increases their memory budget.
        if (generated.size() >= SearchBudget.HARD_MAX_STEPS * 10) throw new IllegalStateException("Generated lot limit");
        generated.add(new Lot(stack.copyWithCount(1), stack.getCount(), 0, terminal, terminal, remainder, path, origin));
        return generated.size() - 1;
    }

    Taken producedChoice(int lot, Ingredient ingredient) {
        if (lot < 0) return null;
        Lot entry = generated.get(lot);
        return !CraftingRecipes.protectedStack(entry.stack) && entry.available > 0 && ingredient.test(entry.stack)
                ? new Taken(-1, lot, entry.stack.copyWithCount(1)) : null;
    }

    public List<CraftPlan.Extraction> extractions() {
        List<CraftPlan.Extraction> result = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (consumed[i] > 0) {
                Source source = sources.get(i);
                result.add(new CraftPlan.Extraction(source.endpointId, source.slot, consumed[i], source.stack));
            }
        }
        return List.copyOf(result);
    }

    public List<ItemStack> delivery(boolean primary) {
        List<ItemStack> result = new ArrayList<>();
        for (Lot lot : generated) {
            int count = Math.addExact(lot.available, lot.reserved);
            if (count > 0 && lot.primary == primary) merge(result, lot.stack.copyWithCount(count));
        }
        return result;
    }

    static void merge(List<ItemStack> into, ItemStack stack) {
        for (ItemStack existing : into) {
            if (ItemStack.isSameItemSameComponents(existing, stack)) {
                existing.setCount(Math.addExact(existing.getCount(), stack.getCount()));
                return;
            }
        }
        into.add(stack.copy());
    }

    /** 失败状态记忆使用精确不可变身份；仅比较数量不足以保证等价。
     * Exact, immutable state identity for failed-state memoization. Counts alone are insufficient. */
    Object identity() {
        var lots = generated.stream().map(l -> List.of(Key.of(l.stack), l.available, l.reserved,
                l.terminal, l.primary, l.remainder, l.path, l.origin)).toList();
        return List.of(java.util.Arrays.stream(available).boxed().toList(),
                java.util.Arrays.stream(consumed).boxed().toList(), lots);
    }

    /** 仅在完整根批次之间、无未完绑定时有效：保留精确提取、交付顺序和批次角色，分支仍保留可验证来源。
     * Only valid between complete root batches, with no unfinished bindings.
     * Producer history no longer affects future feasibility there. Preserve
     * exact physical extraction counts, delivery order and every lot role;
     * the retained branch still owns its full, verifiable provenance. */
    Object batchBoundaryIdentity() {
        var lots = new java.util.LinkedHashMap<Object, Integer>();
        for (var lot : generated) if (lot.available + lot.reserved > 0) {
            Object key = List.of(Key.of(lot.stack), lot.terminal, lot.primary, lot.remainder);
            lots.merge(key, lot.available + lot.reserved, Math::addExact);
        }
        return List.of(java.util.Arrays.stream(available).boxed().toList(),
                java.util.Arrays.stream(consumed).boxed().toList(),
                lots.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue())).toList());
    }

    /** 用与搜索相同的账本重放具体绑定；空物理引用仅供客户端导出时选首个匹配源，服务端须传已授权引用。
     * Replay one concrete binding through the very same accounting as search.
     * A null physical reference selects the first matching source (client export
     * only); server validation supplies the authorized opaque reference. */
    public Binding consume(ItemStack stack, int origin, String reference) {
        if (stack.isEmpty() || stack.getCount() != 1 || CraftingRecipes.protectedStack(stack))
            throw new IllegalArgumentException("Invalid bound ingredient");
        if (origin >= 0) {
            for (int i = 0; i < generated.size(); i++) {
                var lot = generated.get(i);
                if (lot.origin == origin && !lot.terminal && lot.available > 0
                        && ItemStack.isSameItemSameComponents(lot.stack, stack)) {
                    take(new Taken(-1, i, stack));
                    return new Binding(null, lot.remainder);
                }
            }
        } else {
            for (int i = 0; i < sources.size(); i++) {
                var source = sources.get(i);
                if (available[i] > 0 && (reference == null || reference.equals(source.endpointId))
                        && ItemStack.isSameItemSameComponents(source.stack, stack)) {
                    take(new Taken(i, -1, stack));
                    return new Binding(source.endpointId, false);
                }
            }
        }
        throw new IllegalArgumentException("Unfunded ingredient");
    }

    /** 仅供 MAX 证明：无生成物、预留或修改，只读取初始账本拥有的源指纹和可用数，不暴露活背包/容器。
     * MAX certificate only: no generated stock, reservations or mutations.
     * Source fingerprints and usable counts belong to this initial ledger;
     * callers do not get access to a mutable inventory or live container. */
    java.util.OptionalLong initialPotential(java.util.Map<Item, Integer> weights,
            java.util.Set<Item> reachable, SearchBudget budget) {
        if (!generated.isEmpty() || sources.size() > 16_384) return java.util.OptionalLong.empty();
        long result = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (!budget.alive()) return java.util.OptionalLong.empty();
            var stack = sources.get(i).stack;
            int usable = CraftingRecipes.protectedStack(stack) ? 0 : stack.getCount();
            if (consumed[i] != 0 || available[i] != usable) return java.util.OptionalLong.empty();
            if (usable == 0) continue;
            if (!reachable.contains(stack.getItem())) return java.util.OptionalLong.empty();
            int weight = weights.getOrDefault(stack.getItem(), 0);
            if (weight < 0 || weight > 64) return java.util.OptionalLong.empty();
            result += (long) usable * weight;
        }
        return budget.alive() ? java.util.OptionalLong.of(result) : java.util.OptionalLong.empty();
    }

    public record Binding(String reference, boolean remainder) {}
    record Taken(int source, int lot, ItemStack stack) {}
    record Key(Item item, DataComponentPatch components) {
        static Key of(ItemStack stack) { return new Key(stack.getItem(), stack.getComponentsPatch()); }
    }

    /** 物理源的复制值；稳定引用只标识来源，不授予重新访问或扣除物品的权限。
     * Copied physical source value; the stable reference identifies provenance, not access/extraction authority. */
    public record Source(String endpointId, int slot, ItemStack stack) {
        public Source {
            if (slot < 0 || stack.isEmpty()) throw new IllegalArgumentException("Empty or invalid source");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    private static final class Lot {
        final ItemStack stack;
        int available;
        int reserved;
        final boolean terminal;
        boolean primary;
        final boolean remainder;
        final String path;
        final int origin;

        Lot(ItemStack stack, int available, int reserved, boolean terminal, boolean primary, boolean remainder, String path, int origin) {
            this.stack = stack;
            this.available = available;
            this.reserved = reserved;
            this.terminal = terminal;
            this.primary = primary;
            this.remainder = remainder;
            this.path = path;
            this.origin = origin;
        }
        Lot copy() { return new Lot(stack, available, reserved, terminal, primary, remainder, path, origin); }
    }
}
