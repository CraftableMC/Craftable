package org.craftablemc.craftable.execution;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.network.FurnaceProgressPayload;
import org.craftablemc.craftable.network.FurnaceProgressPayload.Mode;
import org.craftablemc.craftable.planner.SearchBudget;
import org.craftablemc.craftable.workstation.FurnaceWorkstation;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 已付款的有界炉任务；不借用原炉槽、燃烧值或经验账本，不持有跨 tick 的活来源。
 * Bounded paid furnace orders: never borrow native slots, heat or XP, or retain live source handles across ticks. */
@EventBusSubscriber(modid = Craftable.MOD_ID)
public final class FurnaceJobs {
    private static final String DATA_ID = "craftable_furnace_jobs", TOKEN = "craftable_furnace_token";
    private static final int MAX_JOBS = 64, MAX_STATIONS = 16, MAX_TOTAL_XP = 1_000_000, MAX_REJECTED = MAX_JOBS * 2;
    private static final int MAX_STACKS = SearchBudget.HARD_MAX_STEPS * 10, MAX_BYTES = 1024 * 1024;
    private static final SavedData.Factory<State> FACTORY = new SavedData.Factory<>(State::new, State::load);

    private FurnaceJobs() {}

    public record Work(BlockPos position, WorkstationCapability capability, int ticks) {
        public Work {
            position = Objects.requireNonNull(position, "position").immutable();
            if (!Objects.requireNonNull(capability, "capability").cooking() || ticks <= 0)
                throw new IllegalArgumentException("Invalid furnace work");
        }
    }

    public record Reward(ResourceLocation recipe, int batches, float xpPerBatch) {
        public Reward {
            if (Objects.requireNonNull(recipe, "recipe").toString().length() > 512
                    || batches < 1 || batches > SearchBudget.HARD_MAX_STEPS
                    || !Float.isFinite(xpPerBatch) || xpPerBatch < 0
                    || (double) batches * xpPerBatch > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid furnace reward");
        }
    }

    /** 已验证步骤的提交后观察值，不是可执行路线或新的扣料授权。
     * Validated post-commit observer values, never an executable route or authority to debit. */
    public record CompletedStep(ResourceLocation recipe, int gridSize, List<ItemStack> inputs, ItemStack output) {
        public CompletedStep {
            boolean validInputs = gridSize == 1 ? !inputs.isEmpty() && inputs.size() <= 9
                    : (gridSize == 2 || gridSize == 3) && inputs.size() == gridSize * gridSize;
            if (Objects.requireNonNull(recipe, "recipe").toString().length() > 512 || !validInputs
                    || Objects.requireNonNull(output, "output").isEmpty())
                throw new IllegalArgumentException("Invalid completed furnace-chain step");
            for (var input : inputs) {
                if (input == null || (gridSize == 1 && input.isEmpty())
                        || !input.isEmpty() && (input.getCount() != 1
                        || org.craftablemc.craftable.recipe.CraftingRecipes.protectedStack(input)))
                    throw new IllegalArgumentException("Invalid completed step input");
            }
            if (inputs.stream().allMatch(ItemStack::isEmpty)) throw new IllegalArgumentException("Empty completed step");
            inputs = inputs.stream().map(ItemStack::copy).toList();
            output = output.copy();
        }

        @Override public List<ItemStack> inputs() { return inputs.stream().map(ItemStack::copy).toList(); }
        @Override public ItemStack output() { return output.copy(); }
    }

    static boolean canStart(ServerPlayer player, List<Work> work) {
        requireThread(player.getServer());
        if (!canReceive(player) || !validWork(work)) return false;
        var state = state(player.getServer());
        if (state.jobs.size() >= MAX_JOBS || !state.rejected.isEmpty() || state.jobs.containsKey(player.getUUID())) return false;
        var level = player.serverLevel();
        for (var item : work) {
            if (FurnaceWorkstation.capability(level, item.position()) != item.capability()
                    || !FurnaceWorkstation.ticking(level, item.position()) || !FurnaceWorkstation.idle(level, item.position())) return false;
            for (var existing : state.jobs.values())
                if (!existing.refund && existing.elapsed < existing.total
                        && existing.dimension.equals(level.dimension().location())
                        && existing.bindings.stream().anyMatch(binding -> binding.position.equals(item.position()))) return false;
        }
        return true;
    }

    /** 由唯一事务在扣料后调用；false 时未发布订单，调用方仍负责回滚扣料。
     * Called by the sole transaction after debit. False publishes no order; the caller compensates its debit. */
    static boolean start(ServerPlayer player, List<Work> work, List<ItemStack> paid,
            List<ItemStack> delivery, List<Reward> rewards, List<CompletedStep> completedSteps) {
        if (!canStart(player, work)) return false;
        Job job;
        try {
            var bindings = new LinkedHashMap<BlockPos, Binding>();
            for (var item : work) if (!bindings.containsKey(item.position())) {
                var entity = entity(player.serverLevel(), item.position());
                if (entity == null) return false;
                var data = entity.getPersistentData();
                UUID token = data.hasUUID(TOKEN) ? data.getUUID(TOKEN) : UUID.randomUUID();
                bindings.put(item.position(), new Binding(item.position(), item.capability(), token));
            }
            job = new Job(UUID.randomUUID(), player.getUUID(), player.serverLevel().dimension().location(),
                    work, List.copyOf(bindings.values()), stacks(paid), stacks(delivery), rewards, completedSteps, 0, false);
            bounded(job.save(player.registryAccess()));
            // 再检查出版前的站点与占用；只给真实已放置实体打持久标记，不写原生炉状态。
            // Recheck stations/reservations immediately before publication; mark only the actual placed entities.
            if (!canStart(player, work)) return false;
            for (var binding : job.bindings) {
                var entity = entity(player.serverLevel(), binding.position);
                if (entity == null) return false;
                entity.getPersistentData().putUUID(TOKEN, binding.token);
                entity.setChanged();
            }
        } catch (RuntimeException exception) {
            Craftable.LOGGER.warn("Rejected furnace order before publication ({})", exception.getClass().getSimpleName());
            return false;
        }
        var state = state(player.getServer());
        state.jobs.put(job.owner, job);
        state.setDirty();
        return true;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        var state = state(server);
        for (var job : List.copyOf(state.jobs.values())) {
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, job.dimension));
            if (!job.refund && job.elapsed < job.total && level != null && destroyed(level, job)) {
                job.refund = true;
                state.setDirty();
            }
            Mode mode = mode(level, job);
            var owner = server.getPlayerList().getPlayer(job.owner);
            if (owner != null && canReceive(owner) && (mode == Mode.READY || mode == Mode.REFUND)) {
                boolean delivered = false;
                try { delivered = CraftingTransaction.deliverDeferred(owner,
                        (job.refund ? job.paid : job.delivery).stream().map(ItemStack::copy).toList()); }
                catch (RuntimeException exception) { Craftable.LOGGER.error("Deferred furnace insertion failed", exception); }
                if (delivered) {
                    // 先终结所有权，再发不可逆经验/通知；观察者异常不得重发物品或退款。
                    // Retire ownership BEFORE irreversible rewards/observers, which must never replay items or refund.
                    state.jobs.remove(job.owner, job);
                    state.setDirty();
                    if (!job.refund) try { CraftingTransaction.notifyDeferred(owner, job.completedSteps); }
                    catch (RuntimeException exception) { Craftable.LOGGER.error("Post-delivery furnace observer failed", exception); }
                    if (!job.refund) try { CraftingTransaction.awardCookingExperience(owner, job.rewards); }
                    catch (RuntimeException exception) { Craftable.LOGGER.error("Post-delivery furnace reward failed", exception); }
                }
            }
        }
        for (var player : server.getPlayerList().getPlayers()) publish(state, player, server.getTickCount());
    }

    /** 只由原版炉或篝火的实际加工 tick 推进，未运行的区块不会追时。
     * Only a native furnace or campfire cooking tick advances work. An inactive chunk accrues no time. */
    public static void processingTick(ServerLevel level, BlockPos position, BlockEntity blockEntity) {
        if (!blockEntity.getPersistentData().hasUUID(TOKEN)) return;
        var server = level.getServer();
        requireThread(server);
        var capability = FurnaceWorkstation.capability(level, position);
        if (capability == null || entity(level, position) != blockEntity || !FurnaceWorkstation.ticking(level, position)
                || !FurnaceWorkstation.idle(level, position)) return;
        var state = state(server);
        int tick = server.getTickCount();
        for (var job : state.jobs.values()) {
            if (job.refund || job.elapsed == job.total || job.lastProgressTick == tick
                    || !job.dimension.equals(level.dimension().location())) continue;
            var current = current(job);
            if (!current.position().equals(position) || current.capability() != capability) continue;
            if (destroyed(level, job)) job.refund = true;
            else {
                // 多个绑定炉同 tick 触发时，也只允许当前有序步骤前进一次。
                // Several bound furnaces ticking together still advance this ordered job at most once per server tick.
                job.elapsed++;
                job.lastProgressTick = tick;
            }
            state.setDirty();
        }
    }

    private static boolean canReceive(ServerPlayer player) {
        return !player.isRemoved() && !player.isDeadOrDying()
                && org.craftablemc.craftable.api.CraftableModePolicy.allows(player.gameMode.getGameModeForPlayer());
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) state(player.getServer()).notices.remove(player.getUUID());
    }

    private static void publish(State state, ServerPlayer player, int tick) {
        var job = state.jobs.get(player.getUUID());
        var level = job == null ? null : player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, job.dimension));
        Mode mode = job == null ? Mode.NONE : mode(level, job);
        int elapsed = job == null ? 0 : job.elapsed, total = job == null ? 0 : job.total;
        var previous = state.notices.get(player.getUUID());
        var dimension = player.serverLevel().dimension().location();
        boolean changed = previous == null || previous.player != player || previous.mode != mode || previous.total != total
                || !previous.dimension.equals(dimension);
        if (!changed && (previous.elapsed == elapsed || tick - previous.tick < 20)) return;
        try {
            FurnaceProgressPayload.send(player, mode, elapsed, total);
            state.notices.put(player.getUUID(), new Notice(player, mode, elapsed, total, tick, dimension));
        } catch (RuntimeException exception) {
            Craftable.LOGGER.debug("Furnace progress unavailable for {}", player.getUUID(), exception);
        }
    }

    private static Mode mode(ServerLevel level, Job job) {
        if (job.refund) return Mode.REFUND;
        if (job.elapsed == job.total) return Mode.READY;
        if (level == null) return Mode.UNLOADED;
        var item = current(job);
        if (!FurnaceWorkstation.ticking(level, item.position())) return Mode.UNLOADED;
        if (item.capability() == WorkstationCapability.CAMPFIRE && !FurnaceWorkstation.litCampfire(level, item.position()))
            return Mode.EXTINGUISHED;
        if (!FurnaceWorkstation.idle(level, item.position())) return Mode.BUSY;
        return job.lastProgressTick == level.getServer().getTickCount() ? Mode.RUNNING : Mode.UNLOADED;
    }

    private static Work current(Job job) {
        int remaining = job.elapsed;
        for (var item : job.work) {
            if (remaining >= item.ticks()) { remaining -= item.ticks(); continue; }
            return item;
        }
        throw new IllegalStateException("Invalid furnace progress");
    }

    private static boolean destroyed(ServerLevel level, Job job) {
        for (var binding : job.bindings) {
            var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(binding.position.getX()),
                    SectionPos.blockToSectionCoord(binding.position.getZ()));
            if (chunk == null) continue;
            var entity = chunk.getBlockEntities().get(binding.position);
            if (entity == null) {
                // 尚未提升的同类 BE NBT 不是“确定已毁”；不为确认身份加载它。
                // Pending same-kind BE NBT is not definite destruction; never promote it just to prove identity.
                if (!FurnaceWorkstation.matchesBlock(chunk.getBlockState(binding.position), binding.capability)) return true;
                continue;
            }
            if (FurnaceWorkstation.capability(level, binding.position) != binding.capability
                    || !entity.getPersistentData().hasUUID(TOKEN)
                    || !binding.token.equals(entity.getPersistentData().getUUID(TOKEN))) return true;
        }
        return false;
    }

    private static BlockEntity entity(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? null : chunk.getBlockEntities().get(pos);
    }

    private static boolean validWork(List<Work> work) {
        if (work == null || work.isEmpty() || work.size() > SearchBudget.HARD_MAX_STEPS) return false;
        var positions = new LinkedHashMap<BlockPos, WorkstationCapability>();
        int total = 0;
        try {
            for (var item : work) {
                if (item == null) return false;
                total = Math.addExact(total, item.ticks());
                var previous = positions.putIfAbsent(item.position(), item.capability());
                if (previous != null && previous != item.capability()) return false;
            }
        } catch (ArithmeticException exception) { return false; }
        return positions.size() <= MAX_STATIONS;
    }

    private static List<ItemStack> stacks(List<ItemStack> input) {
        if (input == null || input.size() > MAX_STACKS) throw new IllegalArgumentException("Too many furnace stacks");
        var result = new ArrayList<ItemStack>();
        for (var stack : input) {
            if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("Empty furnace stack");
            int count = stack.getCount(), maximum = stack.getMaxStackSize();
            if (maximum < 1 || (long) result.size() + ((long) count + maximum - 1) / maximum > MAX_STACKS)
                throw new IllegalArgumentException("Too many furnace stacks");
            while (count > 0) {
                int portion = Math.min(count, maximum);
                result.add(stack.copyWithCount(portion));
                count -= portion;
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty furnace escrow");
        return List.copyOf(result);
    }

    private static State state(MinecraftServer server) {
        requireThread(server);
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    private static void requireThread(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Furnace orders require the server thread");
    }

    private static void bounded(CompoundTag tag) {
        try {
            NbtIo.write(tag, new DataOutputStream(new OutputStream() {
                private int count;
                @Override public void write(int value) throws IOException { add(1); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { add(length); }
                private void add(int length) throws IOException {
                    if (length < 0 || (long) count + length > MAX_BYTES) throw new IOException("Furnace order exceeds byte bound");
                    count += length;
                }
            }));
        } catch (IOException exception) { throw new IllegalArgumentException("Oversized furnace order", exception); }
    }

    private record Binding(BlockPos position, WorkstationCapability capability, UUID token) {}
    private record Notice(ServerPlayer player, Mode mode, int elapsed, int total, int tick, ResourceLocation dimension) {}

    private static final class Job {
        final UUID id, owner;
        final ResourceLocation dimension;
        final List<Work> work;
        final List<Binding> bindings;
        final List<ItemStack> paid, delivery;
        final List<Reward> rewards;
        final List<CompletedStep> completedSteps;
        final int total;
        int elapsed;
        int lastProgressTick = Integer.MIN_VALUE;
        boolean refund;

        Job(UUID id, UUID owner, ResourceLocation dimension, List<Work> work, List<Binding> bindings,
                List<ItemStack> paid, List<ItemStack> delivery, List<Reward> rewards,
                List<CompletedStep> completedSteps, int elapsed, boolean refund) {
            if (!validWork(work) || dimension.toString().length() > 512 || rewards.size() > SearchBudget.HARD_MAX_STEPS
                    || completedSteps.size() > SearchBudget.HARD_MAX_STEPS)
                throw new IllegalArgumentException("Invalid furnace order");
            this.id = id; this.owner = owner; this.dimension = dimension;
            this.work = List.copyOf(work); this.bindings = List.copyOf(bindings);
            this.paid = paid; this.delivery = delivery; this.rewards = List.copyOf(rewards);
            this.completedSteps = List.copyOf(completedSteps);
            double experience = 0;
            for (var reward : this.rewards) experience += (double) reward.batches() * reward.xpPerBatch();
            if (experience > MAX_TOTAL_XP) throw new IllegalArgumentException("Furnace experience exceeds bound");
            int sum = 0;
            for (var item : work) sum = Math.addExact(sum, item.ticks());
            total = sum;
            if (elapsed < 0 || elapsed > total) throw new IllegalArgumentException("Invalid furnace elapsed ticks");
            this.elapsed = elapsed; this.refund = refund;
            var identities = new LinkedHashMap<BlockPos, Binding>();
            for (var binding : bindings)
                if (identities.put(binding.position, binding) != null) throw new IllegalArgumentException("Duplicate furnace binding");
            for (var item : work) {
                var binding = identities.get(item.position());
                if (binding == null || binding.capability != item.capability()) throw new IllegalArgumentException("Missing furnace binding");
            }
            if (identities.size() > MAX_STATIONS || identities.size() != work.stream().map(Work::position).distinct().count())
                throw new IllegalArgumentException("Extraneous furnace binding");
        }

        CompoundTag save(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putUUID("Id", id); tag.putUUID("Owner", owner); tag.putString("Dimension", dimension.toString());
            tag.putInt("Elapsed", elapsed); tag.putBoolean("Refund", refund);
            var actions = new ListTag();
            for (var item : work) {
                var child = new CompoundTag();
                child.putLong("Pos", item.position().asLong()); child.putString("Capability", item.capability().name()); child.putInt("Ticks", item.ticks());
                actions.add(child);
            }
            tag.put("Work", actions);
            var stations = new ListTag();
            for (var binding : bindings) {
                var child = new CompoundTag();
                child.putLong("Pos", binding.position.asLong()); child.putString("Capability", binding.capability.name()); child.putUUID("Token", binding.token);
                stations.add(child);
            }
            tag.put("Stations", stations);
            tag.put("Paid", encodeStacks(paid, registries)); tag.put("Delivery", encodeStacks(delivery, registries));
            var experience = new ListTag();
            for (var reward : rewards) {
                var child = new CompoundTag();
                child.putString("Recipe", reward.recipe().toString()); child.putInt("Batches", reward.batches()); child.putFloat("XP", reward.xpPerBatch());
                experience.add(child);
            }
            tag.put("Rewards", experience);
            var completed = new ListTag();
            for (var step : completedSteps) {
                var child = new CompoundTag();
                child.putString("Recipe", step.recipe().toString()); child.putInt("Grid", step.gridSize());
                var inputs = new ListTag();
                for (var input : step.inputs()) inputs.add(input.saveOptional(registries));
                child.put("Inputs", inputs); child.put("Output", step.output().save(registries));
                completed.add(child);
            }
            tag.put("Completed", completed);
            return tag;
        }

        static Job load(CompoundTag tag, HolderLookup.Provider registries) {
            bounded(tag);
            var work = new ArrayList<Work>();
            for (var child : list(tag, "Work", SearchBudget.HARD_MAX_STEPS)) {
                var value = (CompoundTag) child;
                work.add(new Work(BlockPos.of(value.getLong("Pos")), WorkstationCapability.valueOf(value.getString("Capability")), value.getInt("Ticks")));
            }
            var bindings = new ArrayList<Binding>();
            for (var child : list(tag, "Stations", MAX_STATIONS)) {
                var value = (CompoundTag) child;
                bindings.add(new Binding(BlockPos.of(value.getLong("Pos")), WorkstationCapability.valueOf(value.getString("Capability")), value.getUUID("Token")));
            }
            var rewards = new ArrayList<Reward>();
            for (var child : list(tag, "Rewards", SearchBudget.HARD_MAX_STEPS)) {
                var value = (CompoundTag) child;
                rewards.add(new Reward(ResourceLocation.parse(value.getString("Recipe")), value.getInt("Batches"), value.getFloat("XP")));
            }
            var completed = new ArrayList<CompletedStep>();
            for (var child : list(tag, "Completed", SearchBudget.HARD_MAX_STEPS)) {
                var value = (CompoundTag) child;
                var inputs = new ArrayList<ItemStack>();
                for (var input : list(value, "Inputs", 9)) {
                    var stack = (CompoundTag) input;
                    inputs.add(stack.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, stack).orElseThrow());
                }
                completed.add(new CompletedStep(ResourceLocation.parse(value.getString("Recipe")), value.getInt("Grid"),
                        inputs, ItemStack.parse(registries, value.get("Output")).orElseThrow()));
            }
            return new Job(tag.getUUID("Id"), tag.getUUID("Owner"), ResourceLocation.parse(tag.getString("Dimension")),
                    work, bindings, decodeStacks(tag, "Paid", registries), decodeStacks(tag, "Delivery", registries), rewards, completed,
                    tag.getInt("Elapsed"), tag.getBoolean("Refund"));
        }
    }

    private static ListTag encodeStacks(List<ItemStack> stacks, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var stack : stacks) list.add(stack.save(registries));
        return list;
    }

    private static List<ItemStack> decodeStacks(CompoundTag tag, String key, HolderLookup.Provider registries) {
        var result = new ArrayList<ItemStack>();
        for (var child : list(tag, key, MAX_STACKS)) result.add(ItemStack.parse(registries, child).orElseThrow());
        return stacks(result);
    }

    private static ListTag list(CompoundTag tag, String key, int maximum) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing furnace order list");
        var list = tag.getList(key, Tag.TAG_COMPOUND);
        if (list.size() > maximum) throw new IllegalArgumentException("Oversized furnace order list");
        return list;
    }

    private static final class State extends SavedData {
        final Map<UUID, Job> jobs = new LinkedHashMap<>();
        final Map<UUID, Notice> notices = new LinkedHashMap<>();
        // 损坏记录隔离保留以便人工恢复，不把无法验证的内容变成退款或产物。
        // Quarantine malformed records for recovery, never convert unverifiable data into refunds/products.
        final List<CompoundTag> rejected = new ArrayList<>();

        static State load(CompoundTag tag, HolderLookup.Provider registries) {
            var state = new State();
            if (tag.contains("Rejected", Tag.TAG_LIST))
                for (var child : list(tag, "Rejected", MAX_REJECTED)) state.rejected.add(((CompoundTag) child).copy());
            for (var child : list(tag, "Jobs", MAX_JOBS)) {
                var record = (CompoundTag) child;
                try {
                    var job = Job.load(record, registries);
                    if (state.jobs.putIfAbsent(job.owner, job) != null) throw new IllegalArgumentException("Duplicate furnace owner");
                } catch (RuntimeException exception) {
                    if (state.rejected.size() < MAX_REJECTED) state.rejected.add(record.copy());
                    Craftable.LOGGER.error("Quarantined invalid saved furnace order", exception);
                }
            }
            return state;
        }

        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            var records = new ListTag();
            jobs.values().forEach(job -> records.add(job.save(registries)));
            tag.put("Jobs", records);
            var quarantine = new ListTag();
            rejected.forEach(record -> quarantine.add(record.copy()));
            tag.put("Rejected", quarantine);
            return tag;
        }
    }
}
