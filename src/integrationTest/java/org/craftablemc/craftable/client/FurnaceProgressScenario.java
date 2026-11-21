package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore;
import org.craftablemc.craftable.client.recipebook.RecipeBookProjection;
import org.craftablemc.craftable.client.recipebook.RecipeBookInputHandler;
import org.craftablemc.craftable.client.recipebook.RecipeButtonTargetResolver;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.execution.CraftingService;
import org.craftablemc.craftable.network.FurnaceProgressPayload;
import org.craftablemc.craftable.workstation.WorkstationCapability;

/** 真实网络入队和炉 tick/自动交付；三种菜单只观察同一服务端进度，不制造任务、进度或预算。
 * Real network submission, furnace ticks and automatic delivery; three menus observe one authoritative order without manufacturing work, progress or budgets. */
final class FurnaceProgressScenario {
    private enum Phase { SETUP, OPEN, BROWSE_ROOT, FILTER_ROOT, UNFILTERED_CLICK, LEFT_CLICK, ROOT_CHOICES, ADD_STATIONS, REMOVE_STATIONS,
        REQUEST, RUNNING, CLOSED, REOPEN, AMBIENT, WORKTABLE, DELIVERY, CAMPFIRE_OPEN, CAMPFIRE_BROWSE, CAMPFIRE_DELIVERY, DONE }
    private static final BlockPos TABLE = new BlockPos(2, -60, 0), CHEST = new BlockPos(1, -60, 1), FURNACE = new BlockPos(3, -60, 1);
    private static final BlockPos BLAST_FURNACE = FURNACE.east(), SMOKER = FURNACE.south(), CAMPFIRE = FURNACE.east().south();
    private static final ResourceLocation SMELT = ResourceLocation.withDefaultNamespace("iron_ingot_from_smelting_raw_iron");
    private static final ResourceLocation BLAST = ResourceLocation.withDefaultNamespace("iron_ingot_from_blasting_raw_iron");
    private static final ResourceLocation COOK_AT_CAMPFIRE = ResourceLocation.withDefaultNamespace("cooked_beef_from_campfire_cooking");
    private Phase phase = Phase.SETUP;
    private int age, frames, closedElapsed, renderedWorkstations;
    private Screen renderedScreen;
    private boolean queueObserved, deliveryObserved;
    private ModConfigSpec.ConfigValue<Boolean> compression;
    private Boolean originalCompression;

    void rendered() {
        var screen = Minecraft.getInstance().screen;
        if (renderedScreen != screen) { renderedScreen = screen; frames = 0; }
        frames++;
        renderedWorkstations = CraftingPlanOverlay.active() ? ClientBrowsePlanner.availableWorkstations() : 0;
    }

    @SuppressWarnings("unchecked")
    boolean tick(Minecraft mc) throws ReflectiveOperationException {
        age++;
        var progress = FurnaceProgressOverlay.current();
        switch (phase) {
            case SETUP -> {
                mc.player.closeContainer();
                compression = (ModConfigSpec.ConfigValue<Boolean>) ConfigSettings.values(CraftableServerConfig.SPEC).stream()
                        .filter(value -> value.getPath().getLast().equals("timeCompression")).findFirst().orElseThrow();
                var id = mc.player.getUUID();
                mc.getSingleplayerServer().submit(() -> {
                    originalCompression = compression.getRaw();
                    compression.set(false);
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(TABLE, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(FURNACE, Blocks.FURNACE.defaultBlockState());
                    level.setBlockAndUpdate(FURNACE.above(), Blocks.AIR.defaultBlockState());
                    ((ChestBlockEntity) level.getBlockEntity(CHEST)).clearContent();
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON));
                    player.getInventory().setItem(1, new ItemStack(Items.COAL));
                    player.containerMenu.broadcastChanges();
                }).join();
                next(Phase.OPEN);
            }
            case OPEN -> {
                if (age < 5 || mc.player.containerMenu != mc.player.inventoryMenu
                        || mc.player.getInventory().countItem(Items.RAW_IRON) != 1) return false;
                mc.setScreen(new InventoryScreen(mc.player));
                var book = RecipeBookProjection.component(mc.screen);
                if (!book.isVisible()) book.toggleVisibility();
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.IRON_INGOT.getDescription().getString());
                book.recipesUpdated();
                next(Phase.BROWSE_ROOT);
            }
            case BROWSE_ROOT -> {
                if (frames < 2 || !ClientBrowsePlanner.ready()
                        || ClientRecipeStatusStore.reason(SMELT) != CraftingResultCode.CREATED) return false;
                require(mc.screen instanceof InventoryScreen && mc.player.containerMenu == mc.player.inventoryMenu,
                        "Furnace-only recipe book replaced the vanilla 2x2 menu");
                var button = routeButton(mc, SMELT);
                require(button != null && RecipeButtonTargetResolver.status(button) == CraftingStatus.CRAFTABLE,
                        "Supported furnace root was absent or blocked with raw iron and fuel");
                var book = RecipeBookProjection.component(mc.screen);
                var collection = button.getCollection();
                // 同一产物跨类型只占一个按钮，刷新后也保留路线和按钮位置。
                // A single output across recipe types keeps one button and all routes across refreshes.
                verifyOutputGroup(book, collection, Items.IRON_INGOT, RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING);
                book.recipesUpdated();
                var refreshed = routeButton(mc, SMELT);
                require(refreshed != null && refreshed.getCollection() == collection,
                        "Merged result did not retain its identity on refresh");
                require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(SMELT),
                        "Furnace root input chose an unfunded same-output route");
                shot(mc, "recipe-root");
                // 使用原版筛选按钮的真实坐标，保留其同步、选择及布局语义。
                // Click the vanilla filter's real position, retaining its synchronization, selection and layout behavior.
                var search = ((RecipeBookComponentAccessor) book).craftable$getSearchBox();
                require(book.mouseClicked(search.getX() + 90, search.getY() + 2, 0), "Vanilla craftable filter click was not routed");
                require(mc.player.getRecipeBook().isFiltering(mc.player.inventoryMenu), "Vanilla craftable filter did not toggle");
                next(Phase.FILTER_ROOT);
            }
            case FILTER_ROOT -> {
                if (frames < 2) return false;
                var button = routeButton(mc, SMELT);
                require(button != null && button.getCollection().hasCraftable(), "Craftable-only filter removed the furnace root");
                var book = RecipeBookProjection.component(mc.screen);
                var search = ((RecipeBookComponentAccessor) book).craftable$getSearchBox();
                require(book.mouseClicked(search.getX() + 90, search.getY() + 2, 0), "Could not restore all-recipes filter");
                require(!mc.player.getRecipeBook().isFiltering(mc.player.inventoryMenu), "All-recipes view did not restore");
                next(Phase.UNFILTERED_CLICK);
            }
            case UNFILTERED_CLICK -> {
                if (frames < 2) return false;
                var button = routeButton(mc, SMELT);
                require(button != null && button.getRecipe() != null, "Merged iron ingot button disappeared");
                // 等原版实际轮播到另一条路线，再验证左键采用绿色状态所指的熔炼路线。
                // Wait for real rotation to another route, then verify that left click uses the smelt represented by the green state.
                if (button.getRecipe().id().equals(SMELT) || ClientRecipeStatusStore.reason(SMELT) != CraftingResultCode.CREATED)
                    return false;
                var book = RecipeBookProjection.component(mc.screen);
                var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
                // 多路线按钮以前会打开小窗并截获下一次点击。先走真实右键，再检查炉类左键。
                // A multi-route button used to open a popup and intercept the next click. Exercise right click before the cooking left click.
                require(button.getCollection().getRecipes(false).size() > 1, "Popup suppression fixture lacks visible alternatives");
                var tooltipKeys = button.getTooltipText().stream().map(line -> line.getContents())
                        .filter(TranslatableContents.class::isInstance).map(TranslatableContents.class::cast)
                        .map(TranslatableContents::getKey).toList();
                require(tooltipKeys.contains("tooltip.craftable.recipe_details") && !tooltipKeys.contains("gui.recipebook.moreRecipes"),
                        "Merged button did not replace the vanilla popup hint with its details shortcut");
                book.mouseClicked(button.getX() + 5, button.getY() + 5, 1);
                require(!vanillaPopup(page).isVisible() && !CraftingPlanOverlay.active(),
                        "Enhanced recipe book right click opened a route popup or details");
                require(book.mouseClicked(button.getX() + 5, button.getY() + 5, 0), "Furnace root left click was not routed");
                require(page.getLastClickedRecipe() != null && page.getLastClickedRecipe().id().equals(SMELT),
                        "Merged button left click used an unavailable rotating route");
                require(CraftingPlanOverlay.active() && !vanillaPopup(page).isVisible(),
                        "Furnace left click did not open details without the native popup");
                next(Phase.LEFT_CLICK);
            }
            case LEFT_CLICK -> {
                var overlay = activeOverlay();
                if (age < 5 || read(CraftingPlanOverlay.class, overlay, "displayedView") == null) return false;
                require(mc.player.getInventory().countItem(Items.RAW_IRON) == 1 && mc.player.getInventory().countItem(Items.COAL) == 1
                                && mc.player.getInventory().countItem(Items.IRON_INGOT) == 0 && progress == null,
                        "Furnace root left click consumed inputs or started processing");
                for (int slot = 0; slot < 5; slot++) require(mc.player.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                        "Furnace left click placed cooking ingredients into a crafting slot");
                require(renderedWorkstations == WorkstationCapability.FURNACE.bit(),
                        "Furnace-only graph rendered incorrect nearby station availability: " + renderedWorkstations);
                // 从真实根节点进入选配方。两条炉路线使用同一种原料，仍有不同配方 ID。
                // Open the actual root node. Both stations use the same ingredient, with distinct recipe IDs.
                clickRoot(mc, overlay);
                next(Phase.ROOT_CHOICES);
            }
            case ROOT_CHOICES -> {
                var overlay = activeOverlay();
                require(read(CraftingPlanOverlay.class, overlay, "pane").toString().equals("CHOICES"), "Recipe node did not open the chooser");
                if ((boolean) read(CraftingPlanOverlay.class, overlay, "dirty")
                        || read(CraftingPlanOverlay.class, overlay, "pending") != null) return false;
                var draft = (CraftingService.Draft) read(CraftingPlanOverlay.class, overlay, "draft");
                if (draft == null) return false;
                var ids = draft.choices().candidates().stream().map(candidate -> candidate.recipe()).toList();
                require(ids.contains(SMELT) && ids.contains(BLAST), "Iron node chooser lost a workstation route: " + ids);
                var escape = new ScreenEvent.KeyPressed.Pre(mc.screen, org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                NeoForge.EVENT_BUS.post(escape);
                require(escape.isCanceled() && CraftingPlanOverlay.active(), "Esc did not return from chooser to the graph");
                // 用同一详情页观察服务器工作站变化，校验旧快照失效后会等新快照再显示可用状态。
                // Observe server workstation changes in the same details view. An invalidated snapshot must not keep showing old availability.
                mc.getSingleplayerServer().submit(() -> {
                    var world = mc.getSingleplayerServer().overworld();
                    world.setBlockAndUpdate(BLAST_FURNACE, Blocks.BLAST_FURNACE.defaultBlockState());
                    world.setBlockAndUpdate(SMOKER, Blocks.SMOKER.defaultBlockState());
                    world.setBlockAndUpdate(CAMPFIRE, Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                }).join();
                ClientBrowsePlanner.requestRefresh();
                require(ClientBrowsePlanner.availableWorkstations() == 0, "Unknown workstations were displayed after invalidating the lease");
                next(Phase.ADD_STATIONS);
            }
            case ADD_STATIONS -> {
                require(CraftingPlanOverlay.active(), "New nearby stations closed the existing details view");
                if (frames == 0 || renderedWorkstations != WorkstationCapability.COOKING_MASK) return false;
                shot(mc, "nearby-workstations");
                mc.getSingleplayerServer().submit(() -> {
                    var world = mc.getSingleplayerServer().overworld();
                    world.setBlockAndUpdate(BLAST_FURNACE, Blocks.AIR.defaultBlockState());
                    world.setBlockAndUpdate(SMOKER, Blocks.AIR.defaultBlockState());
                    world.setBlockAndUpdate(CAMPFIRE, world.getBlockState(CAMPFIRE).setValue(CampfireBlock.LIT, false));
                }).join();
                next(Phase.REMOVE_STATIONS);
            }
            case REMOVE_STATIONS -> {
                require(CraftingPlanOverlay.active(), "Removing nearby stations closed the existing details view");
                if (frames == 0 || renderedWorkstations != WorkstationCapability.FURNACE.bit()) return false;
                mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().overworld()
                        .setBlockAndUpdate(CAMPFIRE, Blocks.AIR.defaultBlockState())).join();
                var escape = new ScreenEvent.KeyPressed.Pre(mc.screen, org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                NeoForge.EVENT_BUS.post(escape);
                require(escape.isCanceled() && !CraftingPlanOverlay.active(), "Furnace detail did not close back to its original parent");
                var book = RecipeBookProjection.component(mc.screen);
                if (!book.isVisible()) book.toggleVisibility();
                next(Phase.REQUEST);
            }
            case REQUEST -> {
                if (frames < 2 || !(mc.screen instanceof InventoryScreen)) return false;
                var button = routeButton(mc, SMELT);
                require(button != null && RecipeButtonTargetResolver.preferredRecipe(button).id().equals(SMELT), "Furnace C target disappeared");
                pressCreate(mc, button);
                hideBook(mc);
                next(Phase.RUNNING);
            }
            case RUNNING -> {
                if (progress == null || progress.mode() != FurnaceProgressPayload.Mode.RUNNING) return false;
                // 收到真实入队状态便恢复原世界设置；已付款任务继续依照提交时的计时方式运行。
                // Restore the original world setting immediately after real queue acknowledgement; paid work keeps its submitted timing mode.
                if (!queueObserved) {
                    restoreCompression(mc);
                    require(progress.total() > 0 && progress.elapsed() < progress.total(), "Timed smelt was delivered immediately");
                    require(mc.player.getInventory().countItem(Items.IRON_INGOT) == 0, "Timed smelt delivered before completion");
                    var id = mc.player.getUUID();
                    require(mc.getSingleplayerServer().submit(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                        return player.getInventory().countItem(Items.RAW_IRON) == 0 && player.getInventory().countItem(Items.COAL) == 0
                                && ((AbstractFurnaceBlockEntity) player.serverLevel().getBlockEntity(FURNACE)).isEmpty();
                    }).join(), "Queued order did not escrow input/fuel or changed native furnace slots");
                    queueObserved = true;
                    frames = 0;
                    return false;
                }
                if (!rendered(mc)) return false;
                shot(mc, "two-by-two");
                closedElapsed = progress.elapsed();
                mc.player.closeContainer();
                next(Phase.CLOSED);
            }
            case CLOSED -> {
                require(mc.screen == null, "Closing timed processing retained a crafting screen");
                if (age < 30 || progress == null || progress.elapsed() <= closedElapsed) return false;
                require(progress.mode() == FurnaceProgressPayload.Mode.RUNNING && progress.elapsed() < progress.total(),
                        "Closing the UI stopped or prematurely completed the timed order");
                closedElapsed = progress.elapsed();
                mc.setScreen(new InventoryScreen(mc.player));
                hideBook(mc);
                next(Phase.REOPEN);
            }
            case REOPEN -> {
                if (!(mc.screen instanceof InventoryScreen) || !rendered(mc)) return false;
                require(progress != null && progress.elapsed() >= closedElapsed && progress.elapsed() < progress.total(),
                        "Reopened inventory lost or reset real furnace progress");
                shot(mc, "reopened");
                mc.player.closeContainer();
                mc.getSingleplayerServer().submit(() -> mc.getSingleplayerServer().overworld()
                        .setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState())).join();
                next(Phase.AMBIENT);
            }
            case AMBIENT -> {
                if (mc.screen == null && age == 5) { mc.setScreen(new InventoryScreen(mc.player)); hideBook(mc); }
                if (!(mc.screen instanceof AmbientInventoryScreen) || !rendered(mc)) return false;
                require(progress != null && progress.elapsed() >= closedElapsed, "Temporary 3x3 lost the player's furnace status");
                shot(mc, "temporary-three-by-three");
                mc.player.closeContainer();
                next(Phase.WORKTABLE);
            }
            case WORKTABLE -> {
                if (age == 5) {
                    var id = mc.player.getUUID();
                    mc.getSingleplayerServer().execute(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                        player.openMenu(Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(player.serverLevel(), TABLE));
                    });
                }
                if (!(mc.screen instanceof CraftingScreen) || !rendered(mc)) return false;
                require(progress != null && progress.elapsed() >= closedElapsed, "Physical worktable lost the player's furnace status");
                shot(mc, "worktable");
                next(Phase.DELIVERY);
            }
            case DELIVERY -> {
                if (mc.player.getInventory().countItem(Items.IRON_INGOT) != 1 || progress != null) return false;
                require(mc.player.getInventory().countItem(Items.RAW_IRON) == 0 && mc.player.getInventory().countItem(Items.COAL) == 0,
                        "Deferred delivery duplicated or refunded paid ingredients");
                if (!deliveryObserved) { deliveryObserved = true; frames = 0; return false; }
                if (!rendered(mc)) return false;
                shot(mc, "delivered");
                mc.player.closeContainer();
                var id = mc.player.getUUID();
                mc.getSingleplayerServer().submit(() -> {
                    originalCompression = compression.getRaw();
                    compression.set(true);
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(TABLE, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(FURNACE, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(CAMPFIRE, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, new ItemStack(Items.BEEF));
                    player.containerMenu.broadcastChanges();
                }).join();
                next(Phase.CAMPFIRE_OPEN);
            }
            case CAMPFIRE_OPEN -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu || mc.player.getInventory().countItem(Items.BEEF) != 1)
                    return false;
                mc.setScreen(new InventoryScreen(mc.player));
                var book = RecipeBookProjection.component(mc.screen);
                if (!book.isVisible()) book.toggleVisibility();
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.COOKED_BEEF.getDescription().getString());
                book.recipesUpdated();
                next(Phase.CAMPFIRE_BROWSE);
            }
            case CAMPFIRE_BROWSE -> {
                if (frames < 2 || ClientBrowsePlanner.availableWorkstations() != WorkstationCapability.CAMPFIRE.bit()
                        || ClientRecipeStatusStore.reason(COOK_AT_CAMPFIRE) != CraftingResultCode.CREATED) return false;
                var button = routeButton(mc, COOK_AT_CAMPFIRE);
                require(button != null && RecipeButtonTargetResolver.status(button) == CraftingStatus.CRAFTABLE,
                        "Cooked beef was absent or blocked with only a lit campfire and raw beef");
                verifyOutputGroup(RecipeBookProjection.component(mc.screen), button.getCollection(), Items.COOKED_BEEF,
                        RecipeType.SMELTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING);
                require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(COOK_AT_CAMPFIRE),
                        "Cooked beef button did not choose the lit campfire route");
                pressCreate(mc, button);
                next(Phase.CAMPFIRE_DELIVERY);
            }
            case CAMPFIRE_DELIVERY -> {
                require(progress == null, "Instant campfire request published timed processing status");
                if (mc.player.getInventory().countItem(Items.COOKED_BEEF) != 1) return false;
                require(mc.player.getInventory().countItem(Items.BEEF) == 0, "Campfire recipe did not consume the raw beef");
                close();
                Craftable.LOGGER.warn("FURNACE_PROGRESS PASS realRequest=true realStatus=true closedProgress=true menus=2x2/temporary3x3/worktable delivery=1 raw=0 coal=0 status=NONE campfireRecipeGrouping=true campfireDelivery=1");
                next(Phase.DONE);
                return true;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void restoreCompression(Minecraft mc) {
        if (originalCompression == null) return;
        var value = originalCompression;
        mc.getSingleplayerServer().submit(() -> compression.set(value)).join();
        originalCompression = null;
    }

    void close() { restoreCompression(Minecraft.getInstance()); }
    private void next(Phase next) { phase = next; age = frames = 0; renderedScreen = null; }
    private boolean rendered(Minecraft mc) {
        var book = RecipeBookProjection.component(mc.screen);
        if (book != null && book.isVisible()) { book.toggleVisibility(); frames = 0; return false; }
        return renderedScreen == mc.screen && frames >= 2;
    }
    private static void hideBook(Minecraft mc) {
        var book = RecipeBookProjection.component(mc.screen);
        if (book != null && book.isVisible()) book.toggleVisibility();
    }
    private static RecipeButton routeButton(Minecraft mc, ResourceLocation id) {
        var book = RecipeBookProjection.component(mc.screen);
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(button -> button.visible
                && button.getCollection().getRecipes().stream().anyMatch(recipe -> recipe.id().equals(id))).findFirst().orElse(null);
    }

    private static OverlayRecipeComponent vanillaPopup(RecipeBookPage page) throws ReflectiveOperationException {
        return (OverlayRecipeComponent) read(RecipeBookPage.class, page, "overlay");
    }

    private static CraftingPlanOverlay activeOverlay() throws ReflectiveOperationException {
        require(CraftingPlanOverlay.active(), "Recipe details closed unexpectedly");
        return (CraftingPlanOverlay) read(CraftingPlanOverlay.class, null, "current");
    }

    private static void clickRoot(Minecraft mc, CraftingPlanOverlay overlay) throws ReflectiveOperationException {
        var graph = (AbstractWidget) read(CraftingPlanOverlay.class, overlay, "graph");
        for (var cell : (java.util.List<?>) read(PlanGraphWidget.class, graph, "cells")) {
            if (!read(cell.getClass(), cell, "id").equals("0")) continue;
            double x = graph.getX() + (int) read(cell.getClass(), cell, "x") + (int) read(PlanGraphWidget.class, graph, "panX") + 8;
            double y = graph.getY() + (int) read(cell.getClass(), cell, "y") + (int) read(PlanGraphWidget.class, graph, "panY") + 8;
            var press = new ScreenEvent.MouseButtonPressed.Pre(mc.screen, x, y, 0);
            NeoForge.EVENT_BUS.post(press);
            NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(mc.screen, x, y, 0));
            require(press.isCanceled(), "Node click escaped details");
            return;
        }
        throw new AssertionError("Recipe details did not render a root node");
    }

    private static Object read(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void verifyOutputGroup(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book,
            RecipeCollection collection, Item output, RecipeType<?>... expectedRoutes) {
        var matches = RecipeBookProjection.scope(book).stream().filter(group -> group.getRecipes().stream().anyMatch(
                recipe -> recipe.value().getResultItem(group.registryAccess()).is(output))).toList();
        require(matches.equals(java.util.List.of(collection)), "Duplicate buttons for " + output);
        var routeTypes = collection.getRecipes().stream().map(recipe -> recipe.value().getType()).toList();
        require(routeTypes.containsAll(java.util.List.of(expectedRoutes)), "Merged button lost routes for " + output + ": " + routeTypes);
    }

    private static void pressCreate(Minecraft mc, RecipeButton button) throws ReflectiveOperationException {
        var book = RecipeBookProjection.component(mc.screen);
        var access = (RecipeBookComponentAccessor) book;
        access.craftable$getSearchBox().setFocused(false);
        var page = access.craftable$getRecipeBookPage();
        var hovered = page.getClass().getDeclaredField("hoveredButton");
        hovered.setAccessible(true);
        hovered.set(page, button);
        // 借用原版已绘制按钮的悬停观察点，真正的 C 处理器仍决定配方和请求参数。
        // Borrow the rendered button's hover observation; the actual C handler decides recipe and request fields.
        var key = CraftableKeyMappings.CREATE_ONE.getKey();
        var press = new ScreenEvent.KeyPressed.Pre(mc.screen, key.getValue(), 0, 0);
        RecipeBookInputHandler.onKeyPressed(press);
        RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, key.getValue(), 0, 0));
        require(press.isCanceled(), "C did not route the selected cooking recipe through recipe-book input");
    }
    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "inventory-smoke-furnace-" + name + ".png", mc.getMainRenderTarget(), ignored -> {});
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
