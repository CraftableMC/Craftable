package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.*;
import org.craftablemc.craftable.environment.BrowsingSnapshot;
import org.lwjgl.glfw.GLFW;

/** 中文：背包烟测末尾的隔离场景，用真实原版点击包而非假快照，在输入位置替换全过程逐 tick 检查连续性。
 * English: Final isolated inventory-smoke phase. Real vanilla click packets, not fake
 * client snapshots; asserts every tick throughout input-location replacement. */
final class MenuMoveScenario {
    private static final BlockPos TABLE = new BlockPos(2, -60, 0), CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation PLANKS = ResourceLocation.withDefaultNamespace("oak_planks");
    private enum Phase {
        CONFIGURE_MENU,
        OPEN_INVENTORY,
        CONFIGURE_RECIPE_BOOK,
        VERIFY_RESOURCE_MOVES
    }
    private Phase phase = Phase.CONFIGURE_MENU;
    private int kind, tick, mainSlot, settle;
    private volatile boolean configured;
    private RecipeButton anchor;
    private Object collections, session;
    private long revision, resources;
    private int pageNumber;

    boolean tick(Minecraft mc) throws ReflectiveOperationException {
        if (phase == Phase.CONFIGURE_MENU) {
            configured = false;
            int menuKind = kind;
            mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                var level = p.serverLevel();
                ((ChestBlockEntity) level.getBlockEntity(CHEST)).clearContent();
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 3));
                // 中文：隔离资源移动与原版配方解锁包，后者合法替换集合对象，不属于此次连续性反例。
                // Isolate resource movement from vanilla's recipe-unlock
                // packets, which legitimately replace collection objects.
                p.awardRecipes(level.getRecipeManager().getRecipes());
                level.setBlockAndUpdate(TABLE, menuKind == 1 ? Blocks.AIR.defaultBlockState() : Blocks.CRAFTING_TABLE.defaultBlockState());
                p.containerMenu.broadcastChanges();
                if (menuKind == 2) p.openMenu(Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(level, TABLE));
                configured = true;
            });
            advance(Phase.OPEN_INVENTORY); tick = 0;
            return false;
        }
        if (phase == Phase.OPEN_INVENTORY) {
            if (!configured || ++tick < 25) return false;
            if (kind != 2) mc.setScreen(new InventoryScreen(mc.player));
            advance(Phase.CONFIGURE_RECIPE_BOOK);
            return false;
        }
        if (phase == Phase.CONFIGURE_RECIPE_BOOK) {
            if (kind == 0 && !(mc.screen instanceof AmbientInventoryScreen)
                    || kind == 1 && !(mc.screen instanceof InventoryScreen)
                    || kind == 2 && !(mc.screen instanceof CraftingScreen)) return false;
            var book = RecipeBookProjection.component(mc.screen);
            if (!book.isVisible()) book.toggleVisibility();
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, true));
            ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.OAK_PLANKS.getDescription().getString());
            book.recipesUpdated();
            // 中文：前序烟测切过页签；搜索必须包含建筑配方，不能继承工具/杂项页签而漏目标。
            // Earlier smoke phases switch tabs. Search must include building
            // recipes, not inherit the last tools/misc tab from that phase.
            var tabs = (java.util.List<?>) read(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "tabButtons");
            var all = (net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton) tabs.getFirst();
            book.mouseClicked(all.getX() + 5, all.getY() + 5, 0);
            advance(Phase.VERIFY_RESOURCE_MOVES); tick = 0; settle = 20;
            return false;
        }
        var book = RecipeBookProjection.component(mc.screen);
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        if (anchor == null) {
            // 中文：先等配方书设置/配方包和渲染时结果合并稳定，再锚定仅资源变化的比较基线。
            // Let initial book settings/recipe packets and render-time result
            // merges settle before anchoring a resource-only change.
            if (settle-- > 0) return false;
            if (!ClientBrowsePlanner.ready() || ClientRecipeStatusStore.get(PLANKS, false) != CraftingStatus.CRAFTABLE) return false;
            anchor = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                    && RecipeButtonTargetResolver.preferredRecipe(b).id().equals(PLANKS)).findFirst().orElse(null);
            // 中文：结果在 tick 完成后一帧的渲染前合并，观察必须尊重该时序。
            // Results merge before render, one frame after a tick completes.
            if (anchor == null) return false;
            collections = read(RecipeBookPage.class, page, "recipeCollections");
            pageNumber = (int) read(RecipeBookPage.class, page, "currentPage");
            revision = (long) read(ClientBrowsePlanner.class, null, "revision");
            var snapshot = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
            session = snapshot.session(); resources = snapshot.resources();
            for (int i = 0; i < mc.player.containerMenu.slots.size(); i++) {
                var slot = mc.player.containerMenu.getSlot(i);
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == 0) mainSlot = i;
            }
        }
        // 中文：未筛选原版组会在橡木/其他木材间轮播；本例隔离资源位置与合法目标切换，材质帧由输出变体场景另测。
        // Unfiltered vanilla groups cycle oak alongside other wood outputs.
        // Isolate resource-location changes from that legitimate target change;
        // the output-variant module separately verifies material-specific frames.
        var orderedMethod = RecipeButton.class.getDeclaredMethod("getOrderedRecipes");
        orderedMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var ordered = (java.util.List<net.minecraft.world.item.crafting.RecipeHolder<?>>) orderedMethod.invoke(anchor);
        int shown = java.util.stream.IntStream.range(0, ordered.size()).filter(i -> ordered.get(i).id().equals(PLANKS)).findFirst().orElseThrow();
        var animation = RecipeButton.class.getDeclaredField("time"); animation.setAccessible(true); animation.setFloat(anchor, shown * 30F);
        var index = RecipeButton.class.getDeclaredField("currentIndex"); index.setAccessible(true); index.setInt(anchor, shown);
        var displayedStatus = RecipeButtonTargetResolver.status(anchor);
        require(anchor.visible && displayedStatus == CraftingStatus.CRAFTABLE,
                "Moved log disappeared/recolored: menu=" + kind + " tick=" + tick
                        + (!anchor.visible || displayedStatus != CraftingStatus.CRAFTABLE
                            ? failureContext(mc, page, ordered, displayedStatus) : ""));
        var currentCollections = read(RecipeBookPage.class, page, "recipeCollections");
        if (collections != currentCollections) Craftable.LOGGER.warn("MENU_MOVE changed menu={} tick={} filtering={} ready={} old={} new={}",
                kind, tick, mc.player.getRecipeBook().isFiltering(RecipeBookType.CRAFTING), ClientBrowsePlanner.ready(),
                collectionIds(collections), collectionIds(currentCollections));
        require(collections == currentCollections, "Move rebuilt recipe list: menu=" + kind + " tick=" + tick);
        require(pageNumber == (int) read(RecipeBookPage.class, page, "currentPage"), "Move reset page");
        require(revision == (long) read(ClientBrowsePlanner.class, null, "revision"), "Move reopened session");
        // 中文：每处停留到服务端替换资源到达，包含主背包/网格/光标共用拆分栈的情况。
        // Hold each location long enough to receive the server's replacement
        // resources, including a split stack shared by main/grid/cursor.
        if (tick == 0 || tick == 36 || tick == 48 || tick == 72) click(mc, mainSlot, tick == 48 ? 1 : 0);
        if (tick == 12 || tick == 24 || tick == 60) click(mc, 1, 0);
        if (tick == 80) {
            // 中文：使用真实开关并同步服务端配方书设置，不用会被后续原版包覆盖的客户端标志。
            // Use the real toggle (including server book-settings sync), not a
            // client-only flag that a later vanilla packet can overwrite.
            var filter = (net.minecraft.client.gui.components.StateSwitchingButton) read(
                    net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class, book, "filterButton");
            book.mouseClicked(filter.getX() + 5, filter.getY() + 5, 0);
            require(!mc.player.getRecipeBook().isFiltering(RecipeBookType.CRAFTING), "Real filter toggle failed");
            collections = read(RecipeBookPage.class, page, "recipeCollections");
        }
        if (tick == 84) {
            var hover = RecipeBookPage.class.getDeclaredField("hoveredButton"); hover.setAccessible(true); hover.set(page, anchor);
            var key = new ScreenEvent.KeyPressed.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0);
            RecipeBookInputHandler.onKeyPressed(key);
            require(key.isCanceled(), "C on grid/cursor sources not consumed");
            RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, GLFW.GLFW_KEY_C, 0, 0));
        }
        if (++tick < 125) return false;
        require(mc.player.getInventory().countItem(Items.OAK_PLANKS) == 4, "Grid-funded C output");
        require(mc.player.containerMenu.getCarried().is(Items.OAK_LOG) && mc.player.containerMenu.getCarried().getCount() == 1,
                "Cursor moved-item quantity lost");
        require(mc.player.containerMenu.getSlot(1).getItem().is(Items.OAK_LOG)
                && mc.player.containerMenu.getSlot(1).getItem().getCount() == 1, "Grid consumption count");
        var current = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
        require(ClientBrowsePlanner.ready() && current.session().equals(session) && current.resources() > resources,
                "No authoritative same-session updates for clicks");
        mc.player.closeContainer();
        Craftable.LOGGER.warn("MENU_MOVE menu={} PASS: real clicks main/cursor/grid/split, both filters, stable color/list/page/session, actual C", kind);
        anchor = null; advance(Phase.CONFIGURE_MENU);
        return ++kind == 3;
    }

    private void advance(Phase next) {
        phase = next;
        Craftable.LOGGER.warn("MENU_MOVE phase {} menu={}", phase, kind);
    }

    private static void click(Minecraft mc, int slot, int button) {
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot, button, ClickType.PICKUP, mc.player);
    }

    /** 中文：仅失败时采集事实，区分过期/轮播按钮、证据变动和真缺料，不靠继续等待掩盖失败。
     * English: Failure-only facts distinguish a stale/rotating button from changed
     * evidence or a genuinely missing input, without waiting away the failure. */
    private String failureContext(Minecraft mc, RecipeBookPage page,
            java.util.List<net.minecraft.world.item.crafting.RecipeHolder<?>> ordered,
            CraftingStatus displayedStatus) {
        try {
            var selected = anchor.getRecipe();
            var preferred = RecipeButtonTargetResolver.preferredRecipe(anchor);
            var values = (BrowsingSnapshot) read(ClientBrowsePlanner.class, null, "snapshot");
            var lease = (org.craftablemc.craftable.network.CraftingDetailPayloads.BrowseLease)
                    read(ClientBrowsePlanner.class, null, "grant");
            return " visible=" + anchor.visible + " selected=" + (selected == null ? null : selected.id())
                    + " preferred=" + (preferred == null ? null : preferred.id()) + " status=" + displayedStatus
                    + " oak=" + ClientRecipeStatusStore.get(PLANKS, false)
                    + "/" + ClientRecipeStatusStore.display(PLANKS, false)
                    + "/" + ClientRecipeStatusStore.lifecycle(PLANKS)
                    + " reason=" + ClientRecipeStatusStore.reason(PLANKS)
                    + "/" + ClientRecipeStatusStore.displayReason(PLANKS)
                    + " ready=" + ClientBrowsePlanner.ready() + " now=" + mc.level.getGameTime()
                    + " lease=" + (lease == null ? null : lease.session() + "/" + lease.resources() + " expires=" + lease.expires())
                    + " snapshot=" + (values == null ? null : values.session() + "/" + values.resources()
                        + " inputs=" + values.inputs().stream().map(i -> i.inventorySlot() + ":" + i.stack()).toList())
                    + " liveMain=" + mc.player.containerMenu.getSlot(mainSlot).getItem()
                    + " cursor=" + mc.player.containerMenu.getCarried() + " grid1=" + mc.player.containerMenu.getSlot(1).getItem()
                    + " menu=" + mc.player.containerMenu.getClass().getSimpleName() + "/" + mc.player.containerMenu.containerId
                    + " sameCollections=" + (collections == read(RecipeBookPage.class, page, "recipeCollections"))
                    + " sameRevision=" + (revision == (long) read(ClientBrowsePlanner.class, null, "revision"))
                    + " ordered=" + ordered.stream().map(r -> r.id().toString()).toList();
        } catch (ReflectiveOperationException | RuntimeException error) {
            // 中文：诊断不能替代原行为断言或掩盖首个失败。
            // Diagnostics must not replace the original behavioral assertion.
            return " diagnosticContextFailure=" + error;
        }
    }
    private static Object read(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object collectionIds(Object value) {
        return ((java.util.List<?>) value).stream().map(c -> ((net.minecraft.client.gui.screens.recipebook.RecipeCollection) c)
                .getRecipes().stream().map(r -> r.id().toString()).toList()).toList();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
