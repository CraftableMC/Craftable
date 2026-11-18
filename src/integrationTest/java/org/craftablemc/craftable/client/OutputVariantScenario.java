package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.*;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：规划入口中的输出变体反例；仅控制动画时钟，真实 C/Shift+C、根命中、选卡与确认仍走 UI/网络。
 * English: One planning-entry module. Only the animation clock is controlled; actual
 * C/Shift+C, root hit-testing, card selection and confirmation use UI/network. */
final class OutputVariantScenario {
    private static final ResourceLocation OAK = id("oak_boat"), BIRCH = id("birch_boat"), SPRUCE = id("spruce_boat");
    private enum Phase {
        CONFIGURE_VARIANT_RESOURCES,
        OPEN_INVENTORY,
        SEARCH_BOAT_VARIANTS,
        CAPTURE_OTHER_VARIANT_COLOR,
        CLEAR_VARIANT_RESOURCES,
        CAPTURE_BLOCKED_COLOR,
        RESTORE_VARIANT_RESOURCES,
        CRAFT_DISPLAYED_BIRCH_VARIANT,
        VERIFY_BIRCH_AND_OPEN_DETAILS,
        OPEN_MATERIAL_CHOICES,
        APPLY_MATERIAL_CHOICE,
        OPEN_ROOT_CHOICES,
        APPLY_OAK_ROOT_VARIANT,
        REVIEW_AND_CONFIRM_OAK_VARIANT,
        VERIFY_OAK_COMMIT
    }
    private Phase phase = Phase.CONFIGURE_VARIANT_RESOURCES;
    private int age;
    private long detailsBefore;
    private volatile boolean configured;
    private Consumer<ScreenEvent.Render.Post> captureListener;
    private boolean captured;
    private AssertionError captureFailure;
    private int alternativePixel;

    boolean tick(Minecraft mc) throws Exception {
        try { return advance(mc); }
        catch (Exception | Error failure) {
            cancelCapture();
            throw failure;
        }
    }

    private boolean advance(Minecraft mc) throws Exception {
        if (captureFailure != null) throw captureFailure;
        if (++age > 600) throw new AssertionError("Variant timeout phase=" + phase);
        if (phase == Phase.CONFIGURE_VARIANT_RESOURCES) {
            mc.setScreen(null);
            mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
                p.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS, 10));
                ((ChestBlockEntity) p.serverLevel().getBlockEntity(new BlockPos(1, -60, 1))).clearContent();
                p.serverLevel().setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                p.awardRecipes(p.serverLevel().getRecipeManager().getRecipes());
                p.containerMenu.broadcastChanges(); configured = true;
            });
            next(Phase.OPEN_INVENTORY); return false;
        }
        if (phase == Phase.OPEN_INVENTORY && configured && age > 30 && mc.gameMode.getPlayerMode() == GameType.SURVIVAL) {
            mc.setScreen(new InventoryScreen(mc.player)); next(Phase.SEARCH_BOAT_VARIANTS); return false;
        }
        if (phase == Phase.SEARCH_BOAT_VARIANTS && mc.screen instanceof AmbientInventoryScreen) {
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, false));
            var access = (RecipeBookComponentAccessor) book;
            access.craftable$getSearchBox().setValue(Items.OAK_BOAT.getDescription().getString());
            access.craftable$getSearchBox().setFocused(false);
            book.recipesUpdated();
            var tabs = (List<?>) read(book, "tabButtons");
            var tab = (AbstractWidget) tabs.getFirst();
            book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
            next(Phase.CAPTURE_OTHER_VARIANT_COLOR); return false;
        }
        if (phase == Phase.CAPTURE_OTHER_VARIANT_COLOR && age > 30 && variantsKnown()
                && ClientRecipeStatusStore.display(OAK, false) == CraftingStatus.CRAFTABLE
                && ClientRecipeStatusStore.display(BIRCH, false) == CraftingStatus.CRAFTABLE) {
            var button = boatButton(mc);
            show(button, BIRCH);
            require(visualState(button) == RecipeButtonVisualState.CRAFTABLE, "Craftable material lost its green state");
            show(button, SPRUCE);
            require(visualState(button) == RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE,
                    "Blocked spruce did not distinguish another craftable output");
            require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(SPRUCE), "Color selected a different output");
            // 中文：仅收窄显示投影输入，防止借用列表外的集合成功；这不冒充真实筛选操作。
            // Narrow only the projection input to catch collection-wide successes leaking in; this is not a real filter interaction.
            require(RecipeButtonTargetResolver.visualState(button, List.of(button.getRecipe())) == RecipeButtonVisualState.BLOCKED,
                    "An output outside the rotation changed the current target's color");
            if (captureListener == null && !captured) {
                // 中文：只撤销同一作用域的本地授权，确认陈旧的其它材质绿态不能生成粉色；这不是自然网络故障。
                // English: Revoke local authority in the same scope so stale alternate green cannot create pink; this is not a natural network failure.
                try {
                    ClientRecipeStatusStore.authorizeLocal(false);
                    require(visualState(button) != RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE,
                            "Expired alternate verdict still advertised another craftable material");
                } finally { ClientRecipeStatusStore.authorizeLocal(true); }
                capture(mc, button, RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE, "recipe-button-other-variant");
            }
            if (captured) {
                captured = false; configured = false;
                mc.getSingleplayerServer().execute(() -> { stock(mc, false); configured = true; });
                next(Phase.CLEAR_VARIANT_RESOURCES);
            }
            return false;
        }
        if (phase == Phase.CLEAR_VARIANT_RESOURCES && configured && age > 10 && variantsKnown()
                && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 0
                && mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 0
                && ClientRecipeStatusStore.display(OAK, false) == CraftingStatus.BLOCKED
                && ClientRecipeStatusStore.display(BIRCH, false) == CraftingStatus.BLOCKED) {
            next(Phase.CAPTURE_BLOCKED_COLOR); return false;
        }
        if (phase == Phase.CAPTURE_BLOCKED_COLOR && variantsKnown()) {
            var button = boatButton(mc); show(button, SPRUCE);
            require(visualState(button) == RecipeButtonVisualState.BLOCKED,
                    "A button with no craftable output kept the other-variant state");
            if (captureListener == null && !captured)
                capture(mc, button, RecipeButtonVisualState.BLOCKED, "recipe-button-blocked");
            if (captured) {
                captured = false; configured = false;
                mc.getSingleplayerServer().execute(() -> { stock(mc, true); configured = true; });
                next(Phase.RESTORE_VARIANT_RESOURCES);
            }
            return false;
        }
        if (phase == Phase.RESTORE_VARIANT_RESOURCES && configured && age > 10 && variantsKnown()
                && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 10
                && mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 10
                && ClientRecipeStatusStore.display(OAK, false) == CraftingStatus.CRAFTABLE
                && ClientRecipeStatusStore.display(BIRCH, false) == CraftingStatus.CRAFTABLE) {
            next(Phase.CRAFT_DISPLAYED_BIRCH_VARIANT); return false;
        }
        if (phase == Phase.CRAFT_DISPLAYED_BIRCH_VARIANT && age > 30 && ClientBrowsePlanner.ready()
                && ClientRecipeStatusStore.lifecycle(BIRCH) == ClientRecipeStatusStore.Lifecycle.KNOWN
                && ClientRecipeStatusStore.lifecycle(SPRUCE) == ClientRecipeStatusStore.Lifecycle.KNOWN) {
            var button = boatButton(mc);
            show(button, SPRUCE);
            require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(SPRUCE), "Unavailable output substituted oak");
            require(RecipeButtonTargetResolver.status(button) != CraftingStatus.CRAFTABLE, "Spruce frame inherited oak's green status");
            show(button, BIRCH);
            require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(BIRCH), "Birch frame substituted oak");
            hover(mc, button); key(0); next(Phase.VERIFY_BIRCH_AND_OPEN_DETAILS); return false;
        }
        if (phase == Phase.VERIFY_BIRCH_AND_OPEN_DETAILS && age > 25 && mc.player.getInventory().countItem(Items.BIRCH_BOAT) == 1) {
            require(mc.player.getInventory().countItem(Items.OAK_BOAT) == 0, "C produced wrong boat");
            require(mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 5
                    && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 10, "C used wrong material/cost");
            var button = boatButton(mc); show(button, BIRCH); hover(mc, button);
            detailsBefore = org.craftablemc.craftable.network.CraftablePayloadHandlers.detailRequests();
            key(org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
            require(CraftingPlanOverlay.active(), "Shift+C failed");
            require(intent().recipe().equals(BIRCH), "Detail opened wrong material"); next(Phase.OPEN_MATERIAL_CHOICES); return false;
        }
        if (phase == Phase.OPEN_MATERIAL_CHOICES && ready()) {
            // 中文：先经真实 UI 给旧根设置依赖固定项，才能验证确实清除，而非检查原本空表。
            // Give the old root real UI-selected dependency pins, so clearing
            // them is verified rather than asserting an already empty map.
            clickMaterial(); next(Phase.APPLY_MATERIAL_CHOICE); return false;
        }
        if (phase == Phase.APPLY_MATERIAL_CHOICE && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null) {
            require(draft().choices().candidates().getFirst().recipe().equals(id("birch_planks")), "Wrong material card");
            clickButton("applyChoice"); next(Phase.OPEN_ROOT_CHOICES); return false;
        }
        if (phase == Phase.OPEN_ROOT_CHOICES && ready()) {
            require(!intent().selections().isEmpty(), "Material UI failed to pin old dependencies");
            clickRoot(); next(Phase.APPLY_OAK_ROOT_VARIANT); return false;
        }
        if (phase == Phase.APPLY_OAK_ROOT_VARIANT && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null) {
            var choices = draft().choices().candidates();
            require(choices.stream().anyMatch(c -> c.recipe().equals(BIRCH))
                    && choices.stream().anyMatch(c -> c.recipe().equals(OAK))
                    && choices.stream().anyMatch(c -> c.recipe().equals(SPRUCE)), "Root chooser lost output variants");
            int wanted = java.util.stream.IntStream.range(0, choices.size()).filter(i -> choices.get(i).recipe().equals(OAK)).findFirst().orElseThrow();
            while ((int) read(overlay(), "choicePage") != wanted) clickButton("nextChoice");
            clickButton("applyChoice");
            require(intent().recipe().equals(OAK) && intent().selections().isEmpty(), "Root change retained old subtree pins");
            require(read(overlay(), "maximum") == null && read(overlay(), "draft") == null, "Root change retained old quantity/authority");
            next(Phase.REVIEW_AND_CONFIRM_OAK_VARIANT); return false;
        }
        if (phase == Phase.REVIEW_AND_CONFIRM_OAK_VARIANT && ready()) {
            require(org.craftablemc.craftable.network.CraftablePayloadHandlers.detailRequests() == detailsBefore,
                    "Root cards/candidate/MAX queried server instead of reusing local planner");
            require(draft().view().code() == CraftingResultCode.CREATED, "Oak variant not replanned");
            require(draft().view().primary().stream().anyMatch(s -> s.is(Items.OAK_BOAT)), "Graph still shows birch output");
            require(draft().view().consumed().stream().anyMatch(s -> s.is(Items.OAK_PLANKS) && s.getCount() == 5)
                    && draft().view().consumed().stream().noneMatch(s -> s.is(Items.BIRCH_PLANKS)), "Root switch retained old cost");
            require(((CraftingService.Maximum) read(overlay(), "maximum")).lowerBound() == 2, "Root switch retained birch MAX");
            clickButton("create"); next(Phase.VERIFY_OAK_COMMIT); return false;
        }
        if (phase == Phase.VERIFY_OAK_COMMIT && age > 25 && mc.player.getInventory().countItem(Items.OAK_BOAT) == 1) {
            require(mc.player.getInventory().countItem(Items.BIRCH_BOAT) == 1
                    && mc.player.getInventory().countItem(Items.BIRCH_PLANKS) == 5
                    && mc.player.getInventory().countItem(Items.OAK_PLANKS) == 5, "Root confirmation wrong output/cost");
            // 中文：背包烟测另证木棍/竹子仍属共享输出选择；本模块验证相反的不同输出情形。
            // Existing inventory smoke separately proves stick/bamboo animation
            // stays a shared-output choice. This module proves the opposite case.
            Craftable.LOGGER.warn("OUTPUT_VARIANTS PASS: explicit red/pink rendered colors, displayed C, per-output status, Shift+C root cards, cost/MAX reset, exact confirmation");
            return true;
        }
        return false;
    }

    private static RecipeButton boatButton(Minecraft mc) {
        var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(BIRCH))).findFirst().orElseThrow();
    }
    private static boolean variantsKnown() {
        return ClientBrowsePlanner.ready() && java.util.stream.Stream.of(OAK, BIRCH, SPRUCE)
                .allMatch(id -> ClientRecipeStatusStore.lifecycle(id) == ClientRecipeStatusStore.Lifecycle.KNOWN);
    }
    private static void stock(Minecraft mc, boolean materials) {
        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        player.getInventory().clearContent();
        if (materials) {
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
            player.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS, 10));
        }
        var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(new BlockPos(1, -60, 1));
        chest.clearContent(); chest.setChanged(); player.containerMenu.broadcastChanges();
    }
    @SuppressWarnings("unchecked") private static List<RecipeHolder<?>> rotatingRecipes(RecipeButton button) throws Exception {
        var method = RecipeButton.class.getDeclaredMethod("getOrderedRecipes"); method.setAccessible(true);
        return (List<RecipeHolder<?>>) method.invoke(button);
    }
    private static RecipeButtonVisualState visualState(RecipeButton button) throws Exception {
        return RecipeButtonTargetResolver.visualState(button, rotatingRecipes(button));
    }
    private static void show(RecipeButton button, ResourceLocation recipe) throws Exception {
        var ordered = rotatingRecipes(button);
        int index = java.util.stream.IntStream.range(0, ordered.size()).filter(i -> ordered.get(i).id().equals(recipe)).findFirst().orElseThrow();
        set(button, "currentIndex", index); set(button, "time", index * 30F);
    }
    /**
     * 中文：读取真实绘制后的空白角落，既能发现枚举选错，也能发现渲染仍依赖原版红底叠色；不在tick中截图上一帧。
     * English: Sample an empty corner after real drawing, catching wrong enum selection and vanilla red-base multiplication, not a previous tick's frame.
     */
    private void capture(Minecraft mc, RecipeButton button, RecipeButtonVisualState expected, String name) {
        var screen = mc.screen;
        captureListener = event -> {
            cancelCapture();
            try {
                require(event.getScreen() == screen, "Recipe-button capture lost its screen");
                require(visualState(button) == expected, "Captured a different output frame");
                event.getGuiGraphics().flush();
                try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    double scaleX = (double) pixels.getWidth() / mc.getWindow().getGuiScaledWidth();
                    double scaleY = (double) pixels.getHeight() / mc.getWindow().getGuiScaledHeight();
                    int sample = pixels.getPixelRGBA((int) ((button.getX() + 2.5) * scaleX),
                            (int) ((button.getY() + button.getHeight() - 3.5) * scaleY));
                    assertTint(sample, expected);
                    if (expected == RecipeButtonVisualState.OTHER_VARIANT_CRAFTABLE) alternativePixel = sample;
                    else require(sample != alternativePixel, "Rendered blocked/other-variant colors are identical");
                    var directory = mc.gameDirectory.toPath().resolve("screenshots");
                    java.nio.file.Files.createDirectories(directory);
                    pixels.writeToFile(directory.resolve(name + ".png"));
                    Craftable.LOGGER.warn("RECIPE_BUTTON_COLOR state={} rgba={} target={}", expected,
                            Integer.toUnsignedString(sample, 16), RecipeButtonTargetResolver.preferredRecipe(button).id());
                    captured = true;
                }
            } catch (Exception | AssertionError failure) {
                captureFailure = new AssertionError("Recipe-button rendered color capture failed: " + expected, failure);
            }
        };
        NeoForge.EVENT_BUS.addListener(captureListener);
    }
    private static void assertTint(int pixel, RecipeButtonVisualState expected) {
        int red = pixel & 255, green = pixel >>> 8 & 255, blue = pixel >>> 16 & 255;
        require(red > 40, "Recipe-button color sample missed its background");
        require(Math.abs((double) green / red - expected.green() / expected.red()) < 0.07
                        && Math.abs((double) blue / red - expected.blue() / expected.red()) < 0.07,
                "Rendered background did not use explicit " + expected + " tint: " + red + "," + green + "," + blue);
    }
    private void cancelCapture() {
        if (captureListener != null) {
            NeoForge.EVENT_BUS.unregister(captureListener);
            captureListener = null;
        }
    }
    void abort() { cancelCapture(); }
    private static void hover(Minecraft mc, RecipeButton button) throws Exception {
        var page = ((RecipeBookComponentAccessor) RecipeBookProjection.component(mc.screen)).craftable$getRecipeBookPage();
        set(page, "hoveredButton", button);
    }
    private static void key(int modifiers) {
        var screen = Minecraft.getInstance().screen;
        var event = new ScreenEvent.KeyPressed.Pre(screen, 67, 0, modifiers); NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "C leaked through UI");
        NeoForge.EVENT_BUS.post(new ScreenEvent.KeyReleased.Pre(screen, 67, 0, modifiers));
    }
    private static void clickRoot() throws Exception {
        var graph = read(overlay(), "graph");
        var root = ((List<?>) read(graph, "cells")).stream().filter(c -> {
            try { return read(c, "id").equals("0"); } catch (Exception e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        var widget = (AbstractWidget) graph;
        click(widget.getX() + (int) read(root, "x") + (int) read(graph, "panX") + 8,
                widget.getY() + (int) read(root, "y") + (int) read(graph, "panY") + 8);
    }
    @SuppressWarnings("unchecked") private static void clickMaterial() throws Exception {
        var graph = read(overlay(), "graph");
        var material = ((List<?>) read(graph, "cells")).stream().filter(c -> {
            try { return ((List<ItemStack>) read(c, "needs")).stream().anyMatch(s -> s.is(Items.BIRCH_PLANKS)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }).findFirst().orElseThrow();
        var widget = (AbstractWidget) graph;
        click(widget.getX() + (int) read(material, "x") + (int) read(graph, "panX") + 8,
                widget.getY() + (int) read(material, "y") + (int) read(graph, "panY") + 8);
    }
    private static void clickButton(String name) throws Exception {
        var button = (Button) read(overlay(), name);
        require(button.visible && button.active, "Inactive variant action " + name);
        click(button.getX() + 5, button.getY() + 5);
    }
    private static void click(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        var event = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "Overlay click leaked");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 0));
    }
    private static boolean ready() throws Exception {
        return overlay() != null && draft() != null && !(boolean) read(overlay(), "dirty")
                && read(overlay(), "pending") == null && read(overlay(), "maximum") != null
                && !((CraftingService.Maximum) read(overlay(), "maximum")).pending();
    }
    private static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) read(null, "current"); }
    private static CraftingService.Draft draft() throws Exception { return (CraftingService.Draft) read(overlay(), "draft"); }
    private static CraftRequest intent() throws Exception { return (CraftRequest) read(overlay(), "intent"); }
    private static Field field(Object target, String name) throws Exception {
        var type = target == null ? CraftingPlanOverlay.class : target.getClass();
        while (type != null) {
            try { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private void next(Phase next) {
        phase = next;
        age = 0;
        Craftable.LOGGER.warn("OUTPUT_VARIANTS phase {}", phase);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
