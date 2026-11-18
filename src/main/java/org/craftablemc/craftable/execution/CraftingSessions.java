package org.craftablemc.craftable.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.planner.CraftPlan;
import org.craftablemc.craftable.planner.CraftRequest;

/**
 * 服务端主线程拥有的连接级短状态：只保存意图、不可变指纹/判据和浏览值，不保存计划或材料预留。
 * 容器只以弱身份参与浏览绑定；保留菜单身份用于失效，不能借旧引用绕过新鲜授权扫描。
 * Small connection-local, server-thread-owned state: intent, immutable fingerprints/evidence and browsing values, not plans or reservations.
 * Weak container identities bind browsing; menu identity invalidates state, never bypasses fresh authorized discovery.
 */
public final class CraftingSessions {
    private static final Map<UUID, State> STATES = new HashMap<>();
    private CraftingSessions() {}

    public static void clear(UUID player) { STATES.remove(player); }
    static void discardOffer(ServerPlayer player) { state(player).confirmation = null; }

    /**
     * 在调用者持有的 admission 内同步扫描并投影浏览值，不自行排队或补充额度。
     * Capture browsing values synchronously within the caller's admission lease; do not schedule or replenish allowance here.
     */
    public static org.craftablemc.craftable.environment.BrowsingSnapshot refreshBrowsing(ServerPlayer player) {
        if (!CraftingService.validContext(player)) {
            if (player.getServer().isSameThread()) closeBrowsing(player);
            throw new IllegalStateException("Invalid browsing context");
        }
        var world = org.craftablemc.craftable.environment.EnvironmentSnapshotService.fresh(player);
        return refreshBrowsing(player, world);
    }

    static org.craftablemc.craftable.environment.BrowsingSnapshot refreshBrowsing(ServerPlayer player,
            org.craftablemc.craftable.environment.EnvironmentSnapshot world) {
        State owner = state(player);
        boolean workbench = world.supports(org.craftablemc.craftable.workstation.WorkstationCapability.CRAFTING_3X3)
                || player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;
        var recipes = new org.craftablemc.craftable.recipe.CraftingRecipes(player, workbench);
        if (owner.recipeIdentity.get() != recipes.generation()) {
            owner.recipeIdentity = new java.lang.ref.WeakReference<>(recipes.generation());
            owner.recipeVersion = Math.incrementExact(owner.recipeVersion);
            owner.browsing = null;
        }
        if (owner.browsing == null || owner.browsing.dimension != player.level().dimension())
            owner.browsing = new BrowsingState(player.level().dimension());
        try {
            return owner.browsing.capture(player, world, recipes, owner.recipeVersion);
        } catch (RuntimeException failure) {
            // 捕获中断或超限不能续命旧授权。
            // An interrupted/oversized capture cannot renew the old grant.
            owner.browsing = null;
            throw failure;
        }
    }

    public static void closeBrowsing(ServerPlayer player) { state(player).browsing = null; }

    /**
     * 扫描前低成本拒绝陈旧见证；通过此检查不授予权限，随后仍须扫描并匹配绑定。
     * Cheap stale-witness rejection before capture; passing is not authority and still requires fresh binding checks.
     */
    static boolean acceptsWitness(ServerPlayer player, CraftPlan.Witness witness) {
        var browsing = state(player).browsing;
        return browsing != null && browsing.snapshot != null
                && browsing.validUntil >= player.level().getGameTime()
                && browsing.session.equals(witness.session())
                && browsing.snapshot.recipes() == witness.recipes()
                && browsing.snapshot.resources() == witness.resources();
    }

    // 只在 refreshBrowsing 已成功匹配当前扫描之后调用；返回值副本，不向客户端暴露真实端点。
    // Call only after refreshBrowsing has matched the fresh scan; return copied source values, never expose real endpoints to clients.
    static Map<String, org.craftablemc.craftable.planner.ResourceLedger.Source> witnessBindings(ServerPlayer player,
            org.craftablemc.craftable.environment.EnvironmentSnapshot world) {
        var result = new HashMap<String, org.craftablemc.craftable.planner.ResourceLedger.Source>();
        var browsing = state(player).browsing;
        for (var endpoint : world.endpoints()) {
            var binding = browsing.endpoints.get(endpoint.id());
            for (var reference : binding.references.entrySet()) {
                var stack = endpoint.container().getItem(reference.getKey());
                if (!stack.isEmpty() && !org.craftablemc.craftable.recipe.CraftingRecipes.protectedStack(stack))
                    result.put(reference.getValue().toString(), new org.craftablemc.craftable.planner.ResourceLedger.Source(
                            endpoint.id(), reference.getKey(), stack));
            }
        }
        return result;
    }

    static long browsingLease(ServerPlayer player) {
        var browsing = state(player).browsing;
        return browsing == null ? Long.MIN_VALUE : browsing.validUntil;
    }

    static FailureEvidence failure(ServerPlayer player, Object identity) {
        var state = state(player);
        var evidence = state.failure;
        long now = player.level().getGameTime();
        if (evidence != null && (!evidence.identity.equals(identity) || now < evidence.tick || now - evidence.tick > 40))
            state.failure = null;
        return state.failure;
    }

    static void rememberFailure(ServerPlayer player, Object identity,
            org.craftablemc.craftable.planner.CraftSearch.Reachability scope,
            org.craftablemc.craftable.planner.CraftSearch.FullEvidence evidence) {
        state(player).failure = evidence == null ? null
                : new FailureEvidence(identity, player.level().getGameTime(), scope, evidence);
    }

    record FailureEvidence(Object identity, long tick,
            org.craftablemc.craftable.planner.CraftSearch.Reachability scope,
            org.craftablemc.craftable.planner.CraftSearch.FullEvidence evidence) {}

    /**
     * 跨扫描只保留弱身份，不保活区块/容器，也不能据此提取材料；续租与执行仍须新鲜扫描。
     * Only weak identities survive scans: they pin no chunks/containers and cannot extract; renewal and execution still rescan.
     */
    private static final class BrowsingState {
        final UUID session = UUID.randomUUID();
        final Object dimension;
        Map<String, EndpointBinding> endpoints = Map.of();
        org.craftablemc.craftable.environment.BrowsingSnapshot snapshot;
        Object authorityIdentity;
        long version, validUntil;

        BrowsingState(Object dimension) { this.dimension = dimension; }

        org.craftablemc.craftable.environment.BrowsingSnapshot capture(ServerPlayer player,
                org.craftablemc.craftable.environment.EnvironmentSnapshot world,
                org.craftablemc.craftable.recipe.CraftingRecipes recipes, long recipeVersion) {
            var next = new java.util.LinkedHashMap<String, EndpointBinding>();
            var inputs = new java.util.ArrayList<org.craftablemc.craftable.environment.BrowsingSnapshot.Input>();
            int slots = 0;
            for (var endpoint : world.endpoints()) {
                slots = Math.addExact(slots, endpoint.slotCount());
                if (slots > 16_384)
                    throw new IllegalStateException("Browsing source range exceeds bounds");
                var parts = parts(player, endpoint);
                var binding = endpoints.get(endpoint.id());
                if (binding == null || !binding.matches(endpoint, parts)) binding = new EndpointBinding(endpoint, parts);
                next.put(endpoint.id(), binding);
                for (int slot = endpoint.firstSlot(); slot < endpoint.firstSlot() + endpoint.slotCount(); slot++) {
                    var stack = endpoint.container().getItem(slot);
                    if (stack.isEmpty() || org.craftablemc.craftable.recipe.CraftingRecipes.protectedStack(stack)) continue;
                    var reference = binding.references.computeIfAbsent(slot, ignored -> UUID.randomUUID());
                    inputs.add(new org.craftablemc.craftable.environment.BrowsingSnapshot.Input(reference,
                            endpoint.kind() == org.craftablemc.craftable.environment.EndpointKind.PLAYER ? slot : -1, stack));
                }
            }
            var rules = CraftableServerConfig.craftingRules();
            var candidate = new org.craftablemc.craftable.environment.BrowsingSnapshot(session, recipeVersion, Math.incrementExact(version),
                    recipes.workbench(), recipes.limitedCrafting(), recipes.unlockedRecipes(),
                    rules.surplusDelivery() == CraftableServerConfig.SurplusDelivery.DROP_OVERFLOW,
                    rules.maxBatches(), rules.maxPlanSteps(), rules.directPreparation(), player.getInventory().getMaxStackSize(),
                    MainInventoryInsertion.copyMainInventory(player.getInventory()), inputs);
            // 私有身份也包含空端点和权限边界；获准内容相同时，刻意排除时间、扫描号及玩家当前位置。
            // Include empty endpoints/permission boundaries in the private
            // identity too. Time, scan number and current player position are
            // deliberately excluded when authorized content stays identical.
            Object authority = List.of(world.dimension(), world.scanSettings(), recipes.accessIdentity(), rules,
                    next.values().stream().map(binding -> binding.identity).toList());
            if (snapshot == null || !authority.equals(authorityIdentity)
                    || !candidate.contentIdentity().equals(snapshot.contentIdentity())) {
                version = candidate.resources();
                snapshot = candidate;
                authorityIdentity = authority;
            }
            // 丢失/失权端点重新出现时必须获得新引用，不能恢复旧授权。
            // Reappearing lost/revoked endpoints get new references, never resurrect old grants.
            endpoints = next;
            validUntil = Math.addExact(player.level().getGameTime(), 10);
            return snapshot;
        }

        private static List<net.minecraft.world.Container> parts(ServerPlayer player,
                org.craftablemc.craftable.environment.ContainerEndpoint endpoint) {
            // 扫描重建的是活适配器而非菜单拥有的网格；用网格身份稳定不透明槽引用，不保留可执行视图。
            // Scans recreate the live adapter, not the menu-owned grid. Keep
            // opaque slot references stable without retaining executable views.
            if (endpoint.container() instanceof org.craftablemc.craftable.environment.PlayerMenuInputs inputs)
                return List.of(inputs.grid());
            if (!(endpoint.container() instanceof net.minecraft.world.CompoundContainer compound))
                return List.of(endpoint.container());
            var position = endpoint.position();
            if (position == null) throw new IllegalStateException("Missing double chest binding");
            var level = player.serverLevel();
            var state = level.getBlockState(position);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock))
                throw new IllegalStateException("Invalid double chest binding");
            var first = level.getBlockEntity(position);
            var second = level.getBlockEntity(position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
            if (!(first instanceof net.minecraft.world.Container a) || !(second instanceof net.minecraft.world.Container b)
                    || !compound.contains(a) || !compound.contains(b) || a == b)
                throw new IllegalStateException("Changed double chest binding");
            return List.of(a, b);
        }
    }

    private static final class EndpointBinding {
        final UUID identity = UUID.randomUUID();
        final int first, count;
        final List<java.lang.ref.WeakReference<net.minecraft.world.Container>> parts;
        final Map<Integer, UUID> references = new HashMap<>();

        EndpointBinding(org.craftablemc.craftable.environment.ContainerEndpoint endpoint,
                List<net.minecraft.world.Container> parts) {
            first = endpoint.firstSlot(); count = endpoint.slotCount();
            this.parts = parts.stream().map(java.lang.ref.WeakReference::new).toList();
        }

        boolean matches(org.craftablemc.craftable.environment.ContainerEndpoint endpoint, List<net.minecraft.world.Container> parts) {
            if (first != endpoint.firstSlot() || count != endpoint.slotCount() || parts.size() != this.parts.size()) return false;
            for (int i = 0; i < parts.size(); i++) if (this.parts.get(i).get() != parts.get(i)) return false;
            return true;
        }
    }

    /**
     * 周期性释放闲置状态；实际请求仍逐次检查有效期，不能依赖本清扫的 tick 频率授权。
     * Periodically release idle state; requests check expiry themselves, never authorize based on sweep cadence.
     */
    public static void expire(net.minecraft.server.MinecraftServer server) {
        STATES.entrySet().removeIf(entry -> {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            State state = entry.getValue();
            long now = player.level().getGameTime();
            if (state.confirmation != null && (state.confirmation.menu != player.containerMenu
                    || state.confirmation.expires < now || !CraftingService.validContext(player))) state.confirmation = null;
            if (state.last != null && (state.last.menu != player.containerMenu || now - state.last.tick > 40)) state.last = null;
            if (!CraftingService.validContext(player)) {
                state.clearDerivedState();
            }
            if (state.browsing != null && (state.menu != player.containerMenu
                    || state.browsing.dimension != player.level().dimension()
                    || now > state.browsing.validUntil + 100)) state.browsing = null;
            if (state.failure != null && (now < state.failure.tick || now - state.failure.tick > 40)) state.failure = null;
            return false;
        });
    }

    // 序号属于连接而非菜单；切菜单清理派生状态时不能重置重放水位。
    // Sequence ownership is the connection, not the menu; derived-state cleanup must not reset replay protection.
    static boolean acceptSequence(ServerPlayer player, long sequence) {
        State state = state(player);
        if (sequence < 0 || sequence <= state.highestCreate) return false;
        state.highestCreate = sequence;
        return true;
    }

    static boolean partialGesture(ServerPlayer player, ResourceLocation recipe, long previous) {
        Last last = state(player).last;
        // 链接首次服务端结果而非客户端猜测；第二按可早于首次回复到达，但首次成功不能开启部分准备。
        // Link the first server result, not a client guess; a second press may precede its reply, but first-press success cannot arm preparation.
        return last != null && previous >= 0 && last.sequence == previous
                && last.recipe.equals(recipe) && last.menu == player.containerMenu
                && last.code == CraftingResultCode.MISSING_INGREDIENTS
                && player.level().getGameTime() - last.tick <= 40
                && player.level().getGameTime() >= last.tick;
    }

    static void recordAttempt(ServerPlayer player, ResourceLocation recipe, long sequence,
            CraftingResultCode code, boolean partialIntent) {
        State state = state(player);
        state.last = partialIntent ? null : new Last(recipe, sequence, code,
                player.containerMenu, player.level().getGameTime());
        state.confirmation = null;
    }

    static UUID offer(ServerPlayer player, CraftingService.Prepared prepared, boolean witness) {
        State state = state(player);
        state.confirmation = null;
        var plan = prepared.result().plan().orElse(null);
        if (plan == null || !prepared.result().completeSearch() && plan.partial()) return new UUID(0, 0);
        UUID token = UUID.randomUUID();
        state.confirmation = new Confirmation(token, player.containerMenu, player.level().dimension(),
                player.level().getGameTime() + 200, prepared.request(), prepared.rules(),
                prepared.recipes().generation(), fingerprint(plan, prepared.delivery().drops()), witness);
        return token;
    }

    /** 消费后再验，失败也不复活令牌。 / Consume before validation; even failure must not revive the token. */
    static Confirmation take(ServerPlayer player, UUID token) {
        State state = state(player);
        Confirmation confirmation = state.confirmation;
        state.confirmation = null; // 先消费再验证/重规划。 / Consume before validation or re-planning.
        if (confirmation == null || !confirmation.token.equals(token)
                || confirmation.menu != player.containerMenu
                || confirmation.dimension != player.level().dimension()
                || player.level().getGameTime() > confirmation.expires
                || !CraftingService.validContext(player)) return null;
        return confirmation;
    }

    static Object fingerprint(CraftPlan plan, List<net.minecraft.world.item.ItemStack> drops) {
        return List.of(plan.fingerprint(), CraftPlan.stackKeys(drops));
    }

    private static State state(ServerPlayer player) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        if (state.menu != player.containerMenu) {
            state.menu = player.containerMenu;
            state.last = null;
            state.confirmation = null;
            state.clearDerivedState();
        }
        return state;
    }

    record Confirmation(UUID token, AbstractContainerMenu menu, Object dimension, long expires,
            CraftRequest request, CraftableServerConfig.CraftingRules rules, Object recipeGeneration, Object fingerprint, boolean witness) {
        boolean matches(CraftingService.Prepared fresh) {
            return request.equals(fresh.request()) && rules.equals(fresh.rules())
                    && recipeGeneration == fresh.recipes().generation()
                    && fresh.result().plan().map(p -> fingerprint.equals(
                            CraftingSessions.fingerprint(p, fresh.delivery().drops()))).orElse(false);
        }
    }
    private record Last(ResourceLocation recipe, long sequence, CraftingResultCode code,
            AbstractContainerMenu menu, long tick) {}
    private static final class State {
        long highestCreate = -1;
        AbstractContainerMenu menu;
        Last last;
        Confirmation confirmation;
        BrowsingState browsing;
        FailureEvidence failure;
        // 重载后闲置玩家也不能保活废弃配方世代。
        // An idle player must not pin a discarded recipe generation after reload.
        java.lang.ref.WeakReference<Object> recipeIdentity = new java.lang.ref.WeakReference<>(null);
        long recipeVersion;

        // 意图/令牌有各自生命周期；这里只释放依赖当前菜单/环境的派生判据，不重置连接序号。
        // Intent/offers have separate lifetimes; release menu/environment-derived evidence without resetting connection sequences.
        void clearDerivedState() {
            browsing = null;
            failure = null;
        }
    }
}
