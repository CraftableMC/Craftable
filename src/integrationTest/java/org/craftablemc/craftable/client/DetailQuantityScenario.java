package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.planner.SearchBudget;

/** 中文：通过已有入口验证真实菜单/资源/滑块生命周期，不伪造租约、任务、执行路径或调度器；隔离编码检查只调用保留辅助并恢复唯一计划槽，不能授权/执行。
 * English: Real menu/resource/slider lifecycle through the existing client entry, with
 * no fake lease, task injection, private execution path, or second scheduler.
 * The isolated encoding boundary check invokes the retention helper directly
 * and restores the sole plan slot; it cannot authorize or execute a craft. */
final class DetailQuantityScenario {
    private static final ResourceLocation PICKAXE = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    private enum Phase {
        CONFIGURE_RESOURCES_AND_CAPTURE,
        OPEN_INVENTORY,
        SEARCH_PICKAXE_RECIPE,
        OPEN_INITIAL_DETAILS,
        REPLACE_RESOURCE_SCOPE,
        REOPEN_CHANGED_SCOPE_DETAILS,
        SELECT_INTERMEDIATE_QUANTITY,
        REVIEW_AND_DRAG_LATEST_QUANTITY,
        VERIFY_LATEST_QUANTITY_AND_CAPTURE,
        AWAIT_RESOURCE_INVALIDATION,
        VERIFY_STALE_PLAN_REJECTION
    }
    private Phase phase = Phase.CONFIGURE_RESOURCES_AND_CAPTURE;
    private int age;
    private volatile boolean configured;
    private volatile boolean refreshCaptured, quantityCaptured;
    private volatile Throwable captureFailure;
    private long previousScope, dragStarted, dragElapsed;
    private boolean maximumPending;
    private boolean captureRequested;
    private int captureStep;
    private AbstractWidget draggedSlider;
    private org.craftablemc.craftable.planner.CraftPlan.Witness nonLatestWitness;
    private final java.util.function.Consumer<ScreenEvent.Render.Post> captureListener = this::captureFrame;

    boolean tick(Minecraft mc) throws Exception {
        if (captureFailure != null) throw new AssertionError("Detail screenshot render failed", captureFailure);
        if (++age > 600) throw new AssertionError("Detail quantity timeout phase=" + phase);
        if (phase == Phase.CONFIGURE_RESOURCES_AND_CAPTURE) {
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                    ScreenEvent.Render.Post.class, captureListener);
            mc.setScreen(null);
            resources(mc, 2, 3, true);
            next(Phase.OPEN_INVENTORY); return false;
        }
        if (phase == Phase.OPEN_INVENTORY && configured && age > 30 && stock(mc, 2, 3)) {
            mc.setScreen(new InventoryScreen(mc.player));
            next(Phase.SEARCH_PICKAXE_RECIPE); return false;
        }
        if (phase == Phase.SEARCH_PICKAXE_RECIPE && mc.screen instanceof AmbientInventoryScreen) {
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, false));
            var access = (RecipeBookComponentAccessor) book;
            access.craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
            access.craftable$getSearchBox().setFocused(false);
            book.recipesUpdated();
            var tab = (AbstractWidget) ((List<?>) read(book, "tabButtons")).getFirst();
            book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
            next(Phase.OPEN_INITIAL_DETAILS); return false;
        }
        if (phase == Phase.OPEN_INITIAL_DETAILS && age > 15 && ClientBrowsePlanner.ready()) {
            openDetails(mc);
            next(Phase.REPLACE_RESOURCE_SCOPE); return false;
        }
        if (phase == Phase.REPLACE_RESOURCE_SCOPE && stableDraft() && maximum() != null && maximum().lowerBound() == 1) {
            require(draft().view().code() == CraftingResultCode.CREATED, "Initial single pickaxe not ready");
            // 中文：真实库存变化替换资源 scope 后才重开；未变的缓存租约不属于此回归反例。
            // Reopen only after a genuine inventory change has replaced the
            // resource scope; an unchanged cached lease is not this regression.
            var close = new ScreenEvent.KeyPressed.Pre(mc.screen, org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            NeoForge.EVENT_BUS.post(close);
            require(close.isCanceled() && !CraftingPlanOverlay.active(), "Detail close leaked/failed");
            previousScope = ClientBrowsePlanner.scopeVersion();
            resources(mc, 6, 9, false);
            next(Phase.REOPEN_CHANGED_SCOPE_DETAILS); return false;
        }
        if (phase == Phase.REOPEN_CHANGED_SCOPE_DETAILS && configured && stock(mc, 6, 9) && ClientBrowsePlanner.ready()
                && ClientBrowsePlanner.scopeVersion() != previousScope) {
            openDetails(mc);
            next(Phase.SELECT_INTERMEDIATE_QUANTITY); return false;
        }
        if (phase == Phase.SELECT_INTERMEDIATE_QUANTITY && stableDraft() && maximum() != null && maximum().lowerBound() >= 3) {
            require(maximum().lowerBound() == 3, "Stock 6 sticks/9 diamonds produced incorrect MAX");
            require(intent().batches() == 1, "Passive MAX changed displayed single quantity");
            require(((CraftRequest) plannerField("retainedRequest").get(null)).batches() == 3,
                    "Fixture did not let passive MAX replace its single retained plan");
            require(ClientBrowsePlanner.witness(intent()) != null
                    && ClientBrowsePlanner.witness(intent()) == read(overlay(), "displayedWitness"),
                    "Passive MAX erased the published one-pickaxe review witness");
            maximumPending = maximum().pending();
            var self = overlay();
            draggedSlider = (AbstractWidget) read(self, "slider");
            require(draggedSlider.active, "Known lower-bound slider disabled during MAX");
            double y = draggedSlider.getY() + 10;
            double middle = draggedSlider.getX() + 4 + (draggedSlider.getWidth() - 8) / 2.0;
            var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, middle, y, 0);
            NeoForge.EVENT_BUS.post(press);
            require(press.isCanceled() && intent().batches() == 2, "Slider did not capture first quantity");
            require(ClientBrowsePlanner.witness(intent()) == null && read(self, "displayedWitness") == null,
                    "Changed quantity retained an unrelated displayed witness");
            var release = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, middle, y, 0);
            NeoForge.EVENT_BUS.post(release);
            require(release.isCanceled(), "Intermediate slider release leaked");
            next(Phase.REVIEW_AND_DRAG_LATEST_QUANTITY); return false;
        }
        if (phase == Phase.REVIEW_AND_DRAG_LATEST_QUANTITY && stableDraft() && intent().batches() == 2) {
            require(draft().view().code() == CraftingResultCode.CREATED
                    && total(draft().view().primary(), Items.DIAMOND_PICKAXE) == 2,
                    "Non-latest fixed quantity did not publish its real full plan");
            require(total(draft().view().consumed(), Items.STICK) == 4
                    && total(draft().view().consumed(), Items.DIAMOND) == 6, "Wrong intermediate material quantities");
            if (nonLatestWitness == null) nonLatestWitness = ClientBrowsePlanner.witness(intent());
            require(nonLatestWitness != null && nonLatestWitness == read(overlay(), "displayedWitness")
                    && nonLatestWitness == ClientBrowsePlanner.witness(intent()),
                    "Non-latest quantity did not reuse its captured published witness");
            if (age < 6) return false; // 中文：允许被动 MAX 续算，不重新解释显示审阅。 English: Let passive MAX resume without reinterpreting the displayed review.
            require(maximum() != null && maximum().lowerBound() == 3 && stock(mc, 6, 9),
                    "Intermediate review changed MAX knowledge or stock");
            var self = overlay();
            require(read(self, "slider") == draggedSlider, "Intermediate preview rebuilt dragged widget");
            double y = draggedSlider.getY() + 10;
            double middle = draggedSlider.getX() + 4 + (draggedSlider.getWidth() - 8) / 2.0;
            double right = draggedSlider.getX() + draggedSlider.getWidth() - 4;
            dragStarted = System.nanoTime();
            var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, middle, y, 0);
            NeoForge.EVENT_BUS.post(press);
            require(press.isCanceled() && intent().batches() == 2, "Slider lost intermediate pointer capture");
            var drag = new ScreenEvent.MouseDragged.Pre(mc.screen, right, y, 0, right - middle, 0);
            NeoForge.EVENT_BUS.post(drag);
            require(drag.isCanceled() && intent().batches() == 3, "Drag did not keep latest quantity");
            var release = new ScreenEvent.MouseButtonReleased.Pre(mc.screen, right, y, 0);
            NeoForge.EVENT_BUS.post(release);
            require(release.isCanceled(), "Slider release leaked");
            require(read(self, "pending") == null, "Previous MAX still blocks explicit slider intent");
            require(!(boolean) read(self, "queuedAction"), "Changed quantity retained queued craft");
            require(read(self, "slider") == draggedSlider, "Drag rebuilt widget and lost pointer capture");
            next(Phase.VERIFY_LATEST_QUANTITY_AND_CAPTURE); return false;
        }
        if (phase == Phase.VERIFY_LATEST_QUANTITY_AND_CAPTURE && stableDraft() && intent().batches() == 3) {
            if (dragElapsed == 0) {
                // 中文：测量首个就绪值，不把后续渲染/PNG IO 混入计算延迟。
                // Measure the first ready value, not the later render/PNG IO.
                dragElapsed = System.nanoTime() - dragStarted;
                require(dragElapsed <= 500_000_000L, "Latest 3-pickaxe intent waited " + dragElapsed / 1_000_000 + " ms");
            }
            require(draft().view().code() == CraftingResultCode.CREATED, "Known MAX failed fixed-quantity preview");
            require(total(draft().view().primary(), Items.DIAMOND_PICKAXE) == 3, "Wrong latest output quantity");
            require(total(draft().view().consumed(), Items.STICK) == 6
                    && total(draft().view().consumed(), Items.DIAMOND) == 9, "Wrong latest material quantities");
            require(ClientBrowsePlanner.witness(intent()) != null, "Matching MAX plan lost current-scope witness");
            require(((AbstractWidget) read(overlay(), "create")).active, "Known full route cannot be crafted");
            require(stock(mc, 6, 9), "Read-only MAX/slider mutated inventory");
            captureRequested = true;
            if (!refreshCaptured || !quantityCaptured) return false;
            // 中文：PNG 必须来自真实菜单/嵌入层完成渲染，不能与 ClientTick 的下次资源更新竞争。
            // Each PNG now comes from an actual completed menu/overlay render.
            // Do not race it with the next resource update from ClientTick.
            previousScope = ClientBrowsePlanner.scopeVersion();
            resources(mc, 6, 8, false);
            next(Phase.AWAIT_RESOURCE_INVALIDATION); return false;
        }
        if (phase == Phase.AWAIT_RESOURCE_INVALIDATION && configured && stock(mc, 6, 8) && ClientBrowsePlanner.ready()
                && ClientBrowsePlanner.scopeVersion() != previousScope) {
            require(ClientBrowsePlanner.witness(intent()) == null, "Old MAX witness survived resource scope change");
            next(Phase.VERIFY_STALE_PLAN_REJECTION); return false;
        }
        if (phase == Phase.VERIFY_STALE_PLAN_REJECTION && stableDraft() && intent().batches() == 3) {
            require(draft().view().code() == CraftingResultCode.MISSING_INGREDIENTS
                    || draft().view().code() == CraftingResultCode.PARTIAL_CREATED, "Stale full MAX plan hid new missing diamond");
            int missing = draft().view().missing().stream().filter(m -> m.alternatives().size() == 1
                    && m.alternatives().getFirst().is(Items.DIAMOND)).mapToInt(m -> m.count()).sum();
            require(missing == 1, "Wrong changed-stock missing amount");
            require(total(draft().view().primary(), Items.DIAMOND_PICKAXE) < 3, "Old full quantity survived resource change");
            require(!((AbstractWidget) read(overlay(), "create")).active || intent().partial(),
                    "Stale full MAX enabled crafting after resource change");
            require(ClientBrowsePlanner.witness(intent()) == null && stock(mc, 6, 8), "Stale witness or preview mutation");
            verifyRetainedProjectionBound(mc);
            Craftable.LOGGER.warn("DETAIL_QUANTITY PASS: real resource reopen, pendingMAX={}, latest3={}ms, passive MAX preserves displayed witness, non-latest quantity witness, pointer capture, own-pass refresh tooltip, exact costs and scope invalidation",
                    maximumPending, dragElapsed / 1_000_000);
            NeoForge.EVENT_BUS.unregister(captureListener);
            return true;
        }
        return false;
    }

    private static void verifyRetainedProjectionBound(Minecraft mc) throws Exception {
        var snapshot = (org.craftablemc.craftable.environment.BrowsingSnapshot) plannerField("snapshot").get(null);
        var input = (org.craftablemc.craftable.recipe.PlanningInput) plannerField("input").get(null);
        var request = new CraftRequest(PICKAXE, 1, false, false, CraftRequest.PartialPolicy.EXPLICIT_SAFE, java.util.Map.of());
        var small = new org.craftablemc.craftable.planner.CraftSearch(input, request, snapshot.sources(),
                new org.craftablemc.craftable.planner.SearchBudget(() -> 0L, 1_000_000_000L,
                        org.craftablemc.craftable.planner.SearchBudget.MAX_STATES), SearchBudget.DEFAULT_MAX_STEPS, ignored -> null).run();
        require(small.code() == CraftingResultCode.CREATED, "Small retained-bound fixture did not create");

        // 中文：普通静态数据包也可能输入/证据很小但输出很大；隔离目录只测此编码边界，不替换世界配方、发租约或执行事务。
        // A normal static datapack output can be large even when every input
        // and the complete witness are tiny. This isolated catalog never
        // replaces world recipes, grants a lease, or performs a transaction.
        var giant = new ItemStack(Items.DIAMOND_PICKAXE);
        giant.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                java.util.stream.IntStream.range(0, 24).<Component>mapToObj(i -> Component.literal(i + ":" + "x".repeat(3200))).toList()));
        var ingredients = net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,
                net.minecraft.world.item.crafting.Ingredient.of(Items.DIAMOND),
                net.minecraft.world.item.crafting.Ingredient.of(Items.DIAMOND),
                net.minecraft.world.item.crafting.Ingredient.of(Items.DIAMOND),
                net.minecraft.world.item.crafting.Ingredient.of(Items.STICK),
                net.minecraft.world.item.crafting.Ingredient.of(Items.STICK));
        var recipe = new net.minecraft.world.item.crafting.RecipeHolder<>(PICKAXE,
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.EQUIPMENT,
                        giant, ingredients));
        var catalog = new org.craftablemc.craftable.recipe.PlanningInput.Catalog();
        catalog.observe(new Object(), new Object(), List.of(recipe), mc.level.registryAccess());
        catalog.advance(Long.MAX_VALUE / 2);
        require(catalog.state() == org.craftablemc.craftable.recipe.PlanningInput.Catalog.State.READY,
                "Oversized-output ordinary catalog unavailable");
        var large = new org.craftablemc.craftable.planner.CraftSearch(catalog.bind(true, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT), request,
                snapshot.sources(), new org.craftablemc.craftable.planner.SearchBudget(() -> 0L, 1_000_000_000L,
                        org.craftablemc.craftable.planner.SearchBudget.MAX_STATES), SearchBudget.DEFAULT_MAX_STEPS, ignored -> null).run();
        require(large.code() == CraftingResultCode.CREATED && large.completeSearch(), "Oversized-output full math not proved");
        var witness = org.craftablemc.craftable.planner.CraftPlan.Witness.from(large.plan().orElseThrow(), snapshot);
        require(org.craftablemc.craftable.network.CraftingDetailPayloads.fitsWitness(request, witness, mc.level.registryAccess()),
                "Fixture only proved a large witness, not a large output projection");

        var retainedRequest = plannerField("retainedRequest"); var retainedResult = plannerField("retainedResult");
        Object previousRequest = retainedRequest.get(null), previousResult = retainedResult.get(null);
        var verdict = org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.reason(request);
        var method = ClientBrowsePlanner.class.getDeclaredMethod("retainMaximum", CraftRequest.class,
                org.craftablemc.craftable.planner.SearchResult.class);
        method.setAccessible(true);
        try {
            method.invoke(null, request, small);
            require(retainedRequest.get(null) == request && retainedResult.get(null) == small,
                    "Small full plan failed eager MAX projection bound");
            method.invoke(null, request, large);
            require(retainedRequest.get(null) == request && retainedResult.get(null) == small,
                    "Oversized output replaced the bounded retained MAX plan");
            require(org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.reason(request) == verdict
                    && large.code() == CraftingResultCode.CREATED && large.completeSearch(),
                    "Cache byte refusal downgraded a complete mathematical verdict");
        } finally {
            retainedRequest.set(null, previousRequest); retainedResult.set(null, previousResult);
        }
        require(stock(mc, 6, 8), "Retained-bound fixture altered world inventory");
        Craftable.LOGGER.warn("RETAINED_PROJECTION_BOUND PASS: tiny witness/large static output, bounded slot preserved, full verdict unchanged");
    }

    private void resources(Minecraft mc, int sticks, int diamonds, boolean initial) {
        configured = false;
        mc.getSingleplayerServer().execute(() -> {
            var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            if (initial) {
                p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
                var chest = p.serverLevel().getBlockEntity(new BlockPos(1, -60, 1));
                if (chest instanceof ChestBlockEntity c) c.clearContent();
                p.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                p.awardRecipes(p.serverLevel().getRecipeManager().getRecipes());
            }
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.STICK, sticks));
            p.getInventory().setItem(1, new ItemStack(Items.DIAMOND, diamonds));
            p.containerMenu.broadcastChanges();
            configured = true;
        });
    }

    private static void openDetails(Minecraft mc) throws Exception {
        var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
        RecipeButton button = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(PICKAXE))).findFirst().orElseThrow();
        set(page, "hoveredButton", button);
        var event = new ScreenEvent.KeyPressed.Pre(mc.screen, 67, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        NeoForge.EVENT_BUS.post(event);
        NeoForge.EVENT_BUS.post(new ScreenEvent.KeyReleased.Pre(mc.screen, 67, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
        require(event.isCanceled() && CraftingPlanOverlay.active(), "Real Shift+C failed");
        require(intent().recipe().equals(PICKAXE), "Details opened wrong recipe");
    }

    private void captureFrame(ScreenEvent.Render.Post event) {
        if (!captureRequested || captureStep > 1 || captureStep == 1 && !refreshCaptured) return;
        try {
            var mc = Minecraft.getInstance();
            var self = overlay();
            if (self == null || event.getScreen() != read(self, "parent")) return;
            require(stableDraft() && intent().batches() == 3 && draft().view().code() == CraftingResultCode.CREATED,
                    "Render did not retain latest 3-pickaxe result");
            require(((AbstractWidget) read(self, "create")).active, "Render captured disabled old quantity");
            var graphics = event.getGuiGraphics();
            var previousInput = mc.getLastInputType();
            mc.setLastInputType(net.minecraft.client.InputType.MOUSE);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 800); // 中文：与嵌入层正常 Render.Post 使用同层。 English: Same layer as the embedded overlay's normal Render.Post.
            try {
                if (captureStep == 0) {
                    assertHoveredRefresh(self, graphics, event.getPartialTick());
                    Screenshot.grab(mc.gameDirectory, "detail-refresh-tooltip.png", mc.getMainRenderTarget(),
                            message -> completeScreenshot(message, true));
                } else {
                    self.renderWithTooltip(graphics, -1, -1, event.getPartialTick());
                    graphics.flush();
                    Screenshot.grab(mc.gameDirectory, "detail-quantity-three.png", mc.getMainRenderTarget(),
                            message -> completeScreenshot(message, false));
                }
                captureStep++;
            } finally {
                graphics.pose().popPose();
                mc.setLastInputType(previousInput);
            }
        } catch (Throwable failure) { captureFailure = failure; }
    }

    private void completeScreenshot(Component message, boolean refresh) {
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                && contents.getKey().equals("screenshot.failure")) {
            captureFailure = new AssertionError(message.getString());
        } else if (refresh) refreshCaptured = true;
        else quantityCaptured = true;
    }

    @SuppressWarnings("unchecked") private static void assertHoveredRefresh(CraftingPlanOverlay self,
            GuiGraphics graphics, float partialTick) throws Exception {
        var refresh = (AbstractWidget) read(self, "refresh");
        require(refresh.visible, "Refresh icon not visible");
        int x = refresh.getX() + refresh.getWidth() / 2, y = refresh.getY() + refresh.getHeight() / 2;
        self.render(graphics, x, y, partialTick);
        // 中文：只调 Tooltip getter 无法发现提示仅排到父屏幕的缺陷；此处检查实际嵌入 Screen 提示渲染路径。
        // This catches the embedded-Screen regression: a Tooltip getter alone
        // passes even when the native holder queued only on the parent screen.
        var queued = read(self, "deferredTooltipRendering");
        require(queued != null, "Hover did not queue tooltip on embedded overlay");
        var lines = (List<FormattedCharSequence>) read(queued, "tooltip");
        var actual = new StringBuilder();
        for (var line : lines) line.accept((index, style, codePoint) -> { actual.appendCodePoint(codePoint); return true; });
        require(actual.toString().contains(Component.translatable("screen.craftable.plan.refresh").getString()),
                "Refresh tooltip displayed wrong text: " + actual);
        self.renderWithTooltip(graphics, x, y, partialTick);
        graphics.flush();
        require(read(self, "deferredTooltipRendering") == null, "Overlay's final pass did not consume hover tooltip");
    }
    private static boolean stableDraft() throws Exception {
        return overlay() != null && draft() != null && !(boolean) read(overlay(), "dirty")
                && (read(overlay(), "pending") == null || read(overlay(), "pending").toString().equals("MAXIMUM"));
    }
    private static boolean stock(Minecraft mc, int sticks, int diamonds) {
        return mc.player.getInventory().countItem(Items.STICK) == sticks
                && mc.player.getInventory().countItem(Items.DIAMOND) == diamonds;
    }
    private static int total(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        return stacks.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) read(null, "current"); }
    private static CraftingService.Draft draft() throws Exception { return (CraftingService.Draft) read(overlay(), "draft"); }
    private static CraftingService.Maximum maximum() throws Exception { return (CraftingService.Maximum) read(overlay(), "maximum"); }
    private static CraftRequest intent() throws Exception { return (CraftRequest) read(overlay(), "intent"); }
    private static Field field(Object target, String name) throws Exception {
        var type = target == null ? CraftingPlanOverlay.class : target.getClass();
        while (type != null) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static Field plannerField(String name) throws Exception {
        var field = ClientBrowsePlanner.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private void next(Phase next) {
        phase = next;
        age = 0;
        Craftable.LOGGER.warn("DETAIL_QUANTITY phase {}", phase);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
