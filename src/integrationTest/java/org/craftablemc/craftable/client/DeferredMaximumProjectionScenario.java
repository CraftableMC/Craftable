package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.planner.CraftSearch;
import org.craftablemc.craftable.planner.SearchResult;

/** 中文：在真实授权 scope/求解结果上检验生产完成与投影钩子；反射只隔离同 tick 边界，不注入资源、授权、搜索结果或更大预算。
 * English: Exercises the production completion/projection hooks over a real authorized
 * scope and a real solver result. Reflection isolates the same-tick boundary;
 * it does not inject resources, authority, a search result or a larger budget. */
final class DeferredMaximumProjectionScenario {
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    private enum Phase {
        CONFIGURE_RESOURCES,
        OPEN_INVENTORY,
        OPEN_PICKAXE_DETAILS,
        COMPLETE_MAXIMUM_WITHOUT_PROJECTION,
        VERIFY_DEFERRED_PROJECTION_ISOLATION
    }
    private Phase phase = Phase.CONFIGURE_RESOURCES;
    private int age;
    private volatile boolean configured;
    private Object completedTask, owner;
    private SearchResult result;
    private long projectedBefore, foregroundBefore;

    boolean tick(Minecraft mc) throws Exception {
        if (++age > 400) throw new AssertionError("Deferred MAX fixture timeout phase=" + phase);
        if (phase == Phase.CONFIGURE_RESOURCES) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.closeContainer(); player.getInventory().clearContent();
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
                for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
                if (player.serverLevel().getBlockEntity(new BlockPos(1, -60, 1)) instanceof ChestBlockEntity chest) chest.clearContent();
                player.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                player.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
                player.containerMenu.broadcastChanges(); configured = true;
            });
            next(Phase.OPEN_INVENTORY); return false;
        }
        if (phase == Phase.OPEN_INVENTORY && configured && age > 20 && mc.player.getInventory().countItem(Items.DIAMOND) == 3) {
            mc.setScreen(new InventoryScreen(mc.player)); next(Phase.OPEN_PICKAXE_DETAILS); return false;
        }
        if (phase == Phase.OPEN_PICKAXE_DETAILS && age > 15) {
            CraftingPlanOverlay.open(mc.screen, PICK, false); next(Phase.COMPLETE_MAXIMUM_WITHOUT_PROJECTION); return false;
        }
        if (phase == Phase.COMPLETE_MAXIMUM_WITHOUT_PROJECTION && ClientBrowsePlanner.ready()) {
            var snapshot = (BrowsingSnapshot) read("snapshot");
            if (snapshot.sources().stream().mapToInt(s -> s.stack().is(Items.DIAMOND) ? s.stack().getCount() : 0).sum() != 3) return false;
            ClientBrowsePlanner.cancelDetails();
            ClientBrowsePlanner.scope(List.of(), List.of(), List.of());
            // 中文：现货在闭包/MAX 证书工作前满足直接配方；更大量仍是独立查询，不能当作已完成。
            // Existing stock satisfies this direct recipe before closure/MAX
            // certificate work; larger quantities remain separate queries.
            ClientBrowsePlanner.maximum(ClientRequestSequence.next(), CraftRequest.one(PICK));
            owner = read("detail");
            var request = (CraftRequest) member(owner, "request");
            completedTask = invoke("task", request, false, owner);
            var search = (CraftSearch) member(completedTask, "search");
            Optional<SearchResult> solved = Optional.empty();
            for (int slice = 0; slice < 32 && solved.isEmpty(); slice++) solved = search.advance(2_000_000L);
            result = solved.orElseThrow();
            require(result.plan().isPresent() && !result.plan().orElseThrow().partial(), "Real simple MAX solver did not produce a positive witness");
            projectedBefore = (long) read("maximumProjections"); foregroundBefore = ClientBrowsePlanner.foregroundSlices();
            invoke("finishDetail", completedTask, result);
            require(read("maximumProjection") != null && (long) read("maximumProjections") == projectedBefore,
                    "MAX completion projected immediately in the spent solver turn");
            require(!(boolean) invoke("dispatch", System.nanoTime()), "Not-before wait consumed a scheduler turn");
            require(ClientBrowsePlanner.foregroundSlices() == foregroundBefore, "Waiting MAX projection consumed foreground credit");
            next(Phase.VERIFY_DEFERRED_PROJECTION_ISOLATION); return false;
        }
        if (phase == Phase.VERIFY_DEFERRED_PROJECTION_ISOLATION) {
            // 中文：下一真实 tick 的生产调度器先于夹具运行；较大量仍待算，因此详情对象未换，但真实投影必须已计费。
            // The ordinary planner callback ran before this fixture on the
            // next real game tick. Larger counts remain pending, so detail is
            // still the same object; the actual projection must still count.
            require((long) read("maximumProjections") > projectedBefore, "Deferred positive MAX projection never ran");
            require(ClientBrowsePlanner.foregroundSlices() > foregroundBefore, "Actual projection did not consume foreground credit");
            require(read("retainedResult") == result, "Positive projection was not retained for quantity review");
            if (read("detail") == owner) {
                invoke("finishDetail", completedTask, result);
                require(read("maximumProjection") != null, "Same owner did not schedule the projection boundary probe");
            }
            ClientBrowsePlanner.preview(ClientRequestSequence.next(), CraftRequest.one(PICK), "");
            require(read("maximumProjection") == null, "Replacing MAX with preview retained stale projection");
            invoke("finishDetail", completedTask, result);
            require(read("maximumProjection") == null, "Old completion published into a new intent");
            ClientBrowsePlanner.maximum(ClientRequestSequence.next(), CraftRequest.one(PICK));
            require(read("maximumProjection") == null, "Replacing MAX retained stale projection");
            ClientBrowsePlanner.cancelDetails();
            require(read("maximumProjection") == null, "Cancellation retained stale projection");
            Craftable.LOGGER.warn("DEFERRED_MAXIMUM_PROJECTION PASS: real scope/solver, no same-turn projection, no waiting credit, later projection charged, replacement/cancellation isolation");
            return true;
        }
        return false;
    }

    private static Object read(String name) throws Exception { return member(ClientBrowsePlanner.class, name); }
    private static Object member(Object object, String name) throws Exception {
        var type = object instanceof Class<?> cls ? cls : object.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(object instanceof Class<?> ? null : object);
    }
    private static Object invoke(String name, Object... args) throws Exception {
        for (Method method : ClientBrowsePlanner.class.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            var types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) if (!types[i].isPrimitive() && !types[i].isInstance(args[i])) matches = false;
            if (!matches) continue;
            method.setAccessible(true); return method.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }
    private void next(Phase next) {
        phase = next;
        age = 0;
        Craftable.LOGGER.warn("DEFERRED_MAXIMUM_PROJECTION phase {}", phase);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
