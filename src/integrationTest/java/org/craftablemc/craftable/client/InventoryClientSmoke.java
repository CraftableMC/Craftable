package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.inventory.RecipeBookType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ToastAddEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner;
import org.craftablemc.craftable.api.CraftingStatus;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.*;
import org.craftablemc.craftable.network.CreateRecipeRequestPayload;

/** 中文：显式启用的真实客户端/网络/渲染验收，不进入生产源集；覆盖临时菜单、输入归还及按钮连续性，不能用替代按钮掩盖失效。
 * English: Opt-in real client/network/render acceptance, excluded from production; checks temporary menus, input returns and button continuity without hiding invalidation behind replacement buttons. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class InventoryClientSmoke {
    private enum Step {
        CONFIGURE_WORLD,
        OPEN_INVENTORY,
        SEARCH_CHEST_STICKS,
        VERIFY_CHEST_PREVIEW,
        VERIFY_FIRST_CREATE,
        VERIFY_REPEAT_CRAFT_AND_FILTER,
        OPEN_NO_TABLE_INVENTORY,
        VERIFY_MISSING_WORKSTATION,
        STRESS_RECIPE_BOOK,
        OPEN_WORKBENCH,
        SETUP_WORKBENCH_BOOK,
        VERIFY_WORKBENCH_PREVIEW,
        VERIFY_WORKBENCH_CREATE,
        SWITCH_TO_CREATIVE,
        VERIFY_CREATIVE_RETURN,
        VERIFY_CREATIVE_REQUEST_ISOLATION,
        REOPEN_SURVIVAL_INVENTORY,
        SWITCH_TO_SPECTATOR,
        VERIFY_SPECTATOR_RETURN,
        VERIFY_SPECTATOR_REQUEST_ISOLATION,
        REOPEN_ADVENTURE_INVENTORY,
        CAPTURE_ADVENTURE_INVENTORY,
        CAPTURE_INVENTORY_GHOST,
        MEASURE_WARM_PREVIEW,
        SETUP_WIDE_EFFECTS,
        VERIFY_WIDE_EFFECTS,
        VERIFY_COMPACT_EFFECTS,
        VERIFY_HUD_RETURN,
        VERIFY_MENU_MOVES,
        VERIFY_LATE_CONFIRMATION,
        VERIFY_FEEDBACK_TOAST,
        PASSED,
        FAILED
    }
    private static Step step = Step.CONFIGURE_WORLD;
    private static int age;
    private static boolean started;
    private static volatile boolean configured;
    private static volatile String firstCreateOutcome;
    private static long quietSequence;
    private static long previewStart;
    private static int prewarmedCatalogBuilds;
    private static final RepeatCraftScenario repeatCraft = new RepeatCraftScenario();
    private static final MenuMoveScenario menuMoves = new MenuMoveScenario();
    private static final LateConfirmationScenario lateConfirmation = new LateConfirmationScenario();
    private static final FeedbackToastScenario feedbackToast = new FeedbackToastScenario();
    private static boolean wideEffectsRendered, compactEffectsRendered;
    private static int savedScale = -1;
    private static boolean savedPauseOnLostFocus;
    private static final java.util.List<Long> previewSamples = new java.util.ArrayList<>();
    private static final BlockPos TABLE = new BlockPos(2, -60, 0);
    private static final BlockPos CHEST = new BlockPos(1, -60, 1);
    private static final ResourceLocation STICK = ResourceLocation.withDefaultNamespace("stick");

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ClientTestRun.enabledFor("inventory") || step == Step.PASSED || step == Step.FAILED) return;
        var mc = Minecraft.getInstance();
        try {
            if (!started) {
                if (!(mc.screen instanceof TitleScreen)) return;
                started = true;
                // 中文：后台窗口不能暂停集成服却继续客户端断言计时，否则超时不代表产品停滞。
                // A background test window must not pause the integrated
                // server while the client keeps counting assertion ticks.
                savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
                ClientTestRun.start();
                Craftable.LOGGER.warn("INVENTORY_SMOKE creating isolated test world");
                mc.createWorldOpenFlows().createFreshLevel("craftable-inventory-smoke-" + System.currentTimeMillis(),
                        new LevelSettings("Craftable inventory smoke", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(42, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
            if (++age > 600) throw new AssertionError("Timeout at step " + step);
            if (step == Step.CONFIGURE_WORLD) {
                var uuid = mc.player.getUUID();
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                    var level = player.serverLevel();
                    player.teleportTo(0.5, -60, 0.5);
                    level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
                    level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                    level.setBlockAndUpdate(CHEST.above(), Blocks.AIR.defaultBlockState());
                    ((ChestBlockEntity) level.getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.OAK_PLANKS, 16));
                    player.getInventory().clearContent();
                    player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 24000));
                    player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 24000));
                    configured = true;
                });
                advance(Step.OPEN_INVENTORY);
            } else if (step == Step.OPEN_INVENTORY && configured && age > 60) {
                // 中文：检查真实进世界后的目录。首次打开背包前只有静态知识，尚无动态浏览授权。
                // Check the live join path: only static knowledge exists before the first inventory screen.
                require(mc.screen == null && ClientBrowsePlanner.catalogReady() && !ClientBrowsePlanner.ready(),
                        "Static recipe catalog was not ready in the world before opening inventory, or already granted browse access");
                prewarmedCatalogBuilds = ClientBrowsePlanner.catalogBuilds();
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                // 中文：使用真实原版配方书开关；仅改客户端标志会被首个解锁包的服务端设置覆盖，折叠页面并合法停止需求。
                // Match a real vanilla book toggle. A client-only flag is
                // overwritten by the first recipe-unlock packet's server
                // settings, folding the book and intentionally stopping demand.
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                        RecipeBookType.CRAFTING, true, false));
                mc.setScreen(new InventoryScreen(mc.player));
                advance(Step.SEARCH_CHEST_STICKS);
            } else if (step == Step.SEARCH_CHEST_STICKS && mc.screen instanceof AmbientInventoryScreen screen) {
                var book = screen.getRecipeBookComponent();
                if (!book.isVisible()) book.toggleVisibility();
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.STICK.getDescription().getString());
                book.recipesUpdated();
                advance(Step.VERIFY_CHEST_PREVIEW);
            } else if (step == Step.VERIFY_CHEST_PREVIEW && age > 80) {
                var button = button();
                Craftable.LOGGER.warn("INVENTORY_CLIENT stick status={} reason={} lifecycle={} generation={}",
                        RecipeButtonTargetResolver.status(button), ClientRecipeStatusStore.reason(STICK),
                        ClientRecipeStatusStore.lifecycle(STICK), ClientRecipeStatusStore.revision());
                require(RecipeButtonTargetResolver.status(button) == CraftingStatus.CRAFTABLE, "Chest-only stick not craftable");
                require(button.getCollection().hasCraftable(), "Vanilla collection not craftable");
                require(RecipeButtonTargetResolver.preferredRecipe(button).id().equals(STICK), "Wrong recipe variant selected");
                require(ClientBrowsePlanner.catalogBuilds() == prewarmedCatalogBuilds,
                        "The first browse lease rebuilt the prewarmed recipe catalog");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-craftable.png", mc.getMainRenderTarget(), ignored -> {});
                PacketDistributor.sendToServer(new CreateRecipeRequestPayload(STICK, ClientRequestSequence.next(), -1, true, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE));
                advance(Step.VERIFY_FIRST_CREATE);
            } else if (step == Step.VERIFY_FIRST_CREATE && age > 40) {
                if (firstCreateOutcome == null) {
                    firstCreateOutcome = "pending";
                    mc.getSingleplayerServer().execute(() -> {
                        try {
                            var statesField = org.craftablemc.craftable.execution.CraftingSessions.class.getDeclaredField("STATES");
                            statesField.setAccessible(true);
                            var state = ((java.util.Map<?, ?>) statesField.get(null)).get(serverPlayer().getUUID());
                            var lastField = state.getClass().getDeclaredField("last");
                            lastField.setAccessible(true);
                            firstCreateOutcome = String.valueOf(lastField.get(state));
                        } catch (ReflectiveOperationException failure) {
                            firstCreateOutcome = failure.toString();
                        }
                    });
                    return;
                }
                if (firstCreateOutcome.equals("pending")) return;
                require(mc.player.getInventory().countItem(Items.STICK) == 4,
                        "C did not create 4 sticks: " + firstCreateOutcome);
                Craftable.LOGGER.warn("INVENTORY_RECOVERY status={} reason={} lifecycle={} searches={} builds={}",
                        ClientRecipeStatusStore.get(STICK, false), ClientRecipeStatusStore.reason(STICK),
                        ClientRecipeStatusStore.lifecycle(STICK), org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.searches(),
                        org.craftablemc.craftable.client.recipebook.ClientBrowsePlanner.catalogBuilds());
                require(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent().isVisible(), "Fixture unexpectedly folded recipe book");
                require(ClientRecipeStatusStore.get(STICK, false) == CraftingStatus.CRAFTABLE, "Repeat craft did not recover state");
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, true);
                ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent().recipesUpdated();
                advance(Step.VERIFY_REPEAT_CRAFT_AND_FILTER);
            } else if (step == Step.VERIFY_REPEAT_CRAFT_AND_FILTER && age > 40) {
                if (!repeatCraft.tick(mc)) return;
                require(button().getCollection().hasCraftable(), "Craftable filter removed chest recipe");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-filtered.png", mc.getMainRenderTarget(), ignored -> {});
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setBlockAndUpdate(TABLE, Blocks.AIR.defaultBlockState()));
                advance(Step.OPEN_NO_TABLE_INVENTORY);
            } else if (step == Step.OPEN_NO_TABLE_INVENTORY && mc.player.containerMenu == mc.player.inventoryMenu && age > 20) {
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.setScreen(new InventoryScreen(mc.player));
                ((RecipeBookComponentAccessor) ((InventoryScreen) mc.screen).getRecipeBookComponent())
                        .craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
                ((InventoryScreen) mc.screen).getRecipeBookComponent().recipesUpdated();
                advance(Step.VERIFY_MISSING_WORKSTATION);
            } else if (step == Step.VERIFY_MISSING_WORKSTATION && age > 80) {
                require(mc.screen instanceof InventoryScreen, "No-table menu was not vanilla 2x2");
                var button = button();
                require(RecipeButtonTargetResolver.status(button) == CraftingStatus.BLOCKED, "Pickaxe not blocked without table");
                require(ClientRecipeStatusStore.reason(RecipeButtonTargetResolver.preferredRecipe(button).id())
                        == org.craftablemc.craftable.api.CraftingResultCode.MISSING_WORKSTATION, "Missing workstation reason lost");
                String concise = net.minecraft.network.chat.Component.translatable("tooltip.craftable.blocked_reason",
                        net.minecraft.network.chat.Component.translatable("status.craftable.blocked"),
                        net.minecraft.network.chat.Component.translatable("reason.craftable.missing_workstation")).getString();
                require(button.getTooltipText().stream().filter(line -> line.getString().equals(concise)).count() == 1,
                        "Missing single-line status/reason tooltip");
                // 中文：实际 2x2 菜单显示 3x3 ALL 时走真实左键保护路径，检查错误拓扑不会被放置。
                // Exercise the guarded left-click path while 3x3 ALL is shown on 2x2.
                mc.screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0);
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-no-table.png", mc.getMainRenderTarget(), ignored -> {});
                advance(Step.STRESS_RECIPE_BOOK);
            } else if (step == Step.STRESS_RECIPE_BOOK) {
                stressRecipeBook(mc);
                if (age > 200) advance(Step.OPEN_WORKBENCH);
            } else if (step == Step.OPEN_WORKBENCH) {
                mc.player.closeContainer();
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
                    player.openMenu(Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(level, TABLE));
                });
                advance(Step.SETUP_WORKBENCH_BOOK);
            } else if (step == Step.SETUP_WORKBENCH_BOOK && mc.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen screen) {
                searchSticks(screen.getRecipeBookComponent());
                showSwordGhost(screen.getRecipeBookComponent());
                advance(Step.VERIFY_WORKBENCH_PREVIEW);
            } else if (step == Step.VERIFY_WORKBENCH_PREVIEW && age > 60) {
                require(button().getCollection().hasCraftable(), "Physical workbench did not receive enhanced chest state");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-workbench.png", mc.getMainRenderTarget(), ignored -> {});
                PacketDistributor.sendToServer(new CreateRecipeRequestPayload(STICK, ClientRequestSequence.next(), -1, true, org.craftablemc.craftable.planner.CraftRequest.PartialPolicy.EXPLICIT_SAFE));
                advance(Step.VERIFY_WORKBENCH_CREATE);
            } else if (step == Step.VERIFY_WORKBENCH_CREATE && age > 30) {
                require(mc.player.getInventory().countItem(Items.STICK) == 24, "Physical workbench C failed");
                mc.player.closeContainer();
                mc.setScreen(new InventoryScreen(mc.player));
                advance(Step.SWITCH_TO_CREATIVE);
            } else if (step == Step.SWITCH_TO_CREATIVE && mc.screen instanceof AmbientInventoryScreen) {
                mc.getSingleplayerServer().execute(() -> {
                    var player = serverPlayer();
                    player.containerMenu.getSlot(1).set(new ItemStack(Items.DIAMOND, 3));
                    player.containerMenu.setCarried(new ItemStack(Items.EMERALD, 2));
                    player.containerMenu.broadcastChanges();
                    player.setGameMode(GameType.CREATIVE);
                });
                advance(Step.VERIFY_CREATIVE_RETURN);
            } else if (step == Step.VERIFY_CREATIVE_RETURN && age > 40) {
                require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen, "Creative catalog not restored");
                // 中文：原版创造目录客户端拥有 ItemPickerMenu，不能误判为临时 3x3 菜单。
                // Vanilla's creative catalog owns an ItemPickerMenu on the
                // client; it must not be confused with the temporary 3x3 menu.
                require(!(mc.player.containerMenu instanceof org.craftablemc.craftable.menu.AmbientInventoryMenu),
                        "Creative retained temporary menu");
                require(mc.player.getInventory().countItem(Items.DIAMOND) == 3, "Mode switch lost grid");
                require(mc.player.getInventory().countItem(Items.EMERALD) == 2, "Mode switch lost cursor");
                quietSequence = ClientRequestSequence.next();
                advance(Step.VERIFY_CREATIVE_REQUEST_ISOLATION);
            } else if (step == Step.VERIFY_CREATIVE_REQUEST_ISOLATION) {
                require(!RecipeBookProjection.active(), "Creative projection active");
                var key = new net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre(mc.screen,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_C, 0, 0);
                RecipeBookInputHandler.onKeyPressed(key);
                require(!key.isCanceled(), "Craftable consumed C in creative");
                if (age % 10 == 0) {
                    mc.player.closeContainer();
                    mc.setScreen(new InventoryScreen(mc.player)); // 中文：沿用 E 键相同的原版入口。 English: Same vanilla entry used by E.
                    require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen, "Creative E flicker/reopen failure");
                }
                if (age > 80) {
                    require(ClientRequestSequence.next() == quietSequence + 1, "Creative generated Craftable requests");
                    Screenshot.grab(mc.gameDirectory, "inventory-smoke-creative.png", mc.getMainRenderTarget(), ignored -> {});
                    mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.SURVIVAL));
                    advance(Step.REOPEN_SURVIVAL_INVENTORY);
                }
            } else if (step == Step.REOPEN_SURVIVAL_INVENTORY && age > 40) {
                require(mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu, "Survival return auto-opened crafting");
                mc.setScreen(new InventoryScreen(mc.player));
                advance(Step.SWITCH_TO_SPECTATOR);
            } else if (step == Step.SWITCH_TO_SPECTATOR && mc.screen instanceof AmbientInventoryScreen) {
                mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.SPECTATOR));
                advance(Step.VERIFY_SPECTATOR_RETURN);
            } else if (step == Step.VERIFY_SPECTATOR_RETURN && age > 40) {
                require(mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu, "Spectator retained custom screen/menu");
                require(!RecipeBookProjection.active(), "Spectator projection active");
                quietSequence = ClientRequestSequence.next();
                advance(Step.VERIFY_SPECTATOR_REQUEST_ISOLATION);
            } else if (step == Step.VERIFY_SPECTATOR_REQUEST_ISOLATION && age > 40) {
                require(ClientRequestSequence.next() == quietSequence + 1, "Spectator generated Craftable requests");
                mc.getSingleplayerServer().execute(() -> serverPlayer().setGameMode(GameType.ADVENTURE));
                advance(Step.REOPEN_ADVENTURE_INVENTORY);
            } else if (step == Step.REOPEN_ADVENTURE_INVENTORY && age > 40) {
                require(mc.screen == null, "Adventure return auto-opened inventory");
                mc.setScreen(new InventoryScreen(mc.player));
                advance(Step.CAPTURE_ADVENTURE_INVENTORY);
            } else if (step == Step.CAPTURE_ADVENTURE_INVENTORY && mc.screen instanceof AmbientInventoryScreen && age > 30) {
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-inventory.png", mc.getMainRenderTarget(), ignored -> {});
                showSwordGhost(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent());
                advance(Step.CAPTURE_INVENTORY_GHOST);
            } else if (step == Step.CAPTURE_INVENTORY_GHOST && age > 20) {
                // 中文：在真实渲染帧后截图，不在刚设幽灵配方的 tick 截图；用工作台图比较紧凑输出布局。
                // Capture after rendered frames, not in the tick that sets up
                // the ghost. Compare compact output against the workbench shot.
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-inventory-ghost.png", mc.getMainRenderTarget(), ignored -> {});
                searchSticks(((AmbientInventoryScreen) mc.screen).getRecipeBookComponent());
                advance(Step.MEASURE_WARM_PREVIEW);
            } else if (step == Step.MEASURE_WARM_PREVIEW) {
                if (previewStart == 0) {
                    RecipeBookStatusHandler.afterCreate(STICK);
                    previewStart = System.nanoTime();
                } else if (ClientRecipeStatusStore.lifecycle(STICK) == ClientRecipeStatusStore.Lifecycle.KNOWN
                        && ClientRecipeStatusStore.reason(STICK) != org.craftablemc.craftable.api.CraftingResultCode.SEARCH_BUDGET_EXCEEDED) {
                    previewSamples.add(System.nanoTime() - previewStart);
                    previewStart = 0;
                    if (previewSamples.size() < 40) return;
                    previewSamples.sort(Long::compare);
                    Craftable.LOGGER.warn("INVENTORY_PREVIEW warm_stick samples=40 p50={}ms p95={}ms max={}ms (invalidate through real network to known client state)",
                            previewSamples.get(19)/1e6, previewSamples.get(37)/1e6, previewSamples.get(39)/1e6);
                    require(previewSamples.get(37) <= 500_000_000L, "Warm visible target exceeded P95 500 ms");
                    advance(Step.SETUP_WIDE_EFFECTS);
                }
            } else if (step == Step.SETUP_WIDE_EFFECTS) {
                savedScale = mc.options.guiScale().get();
                mc.getWindow().setWindowed(1280, 960);
                mc.options.guiScale().set(2); mc.resizeDisplay();
                wideEffectsRendered = false;
                advance(Step.VERIFY_WIDE_EFFECTS);
            } else if (step == Step.VERIFY_WIDE_EFFECTS && age > 30) {
                require(((AmbientInventoryScreen) mc.screen).canSeeEffects() && wideEffectsRendered,
                        "Vanilla wide effect renderer/HUD suppression not reached");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-effects-wide.png", mc.getMainRenderTarget(), ignored -> {});
                mc.options.guiScale().set(3); mc.resizeDisplay();
                compactEffectsRendered = false;
                advance(Step.VERIFY_COMPACT_EFFECTS);
            } else if (step == Step.VERIFY_COMPACT_EFFECTS && age > 30) {
                require(((AmbientInventoryScreen) mc.screen).canSeeEffects() && compactEffectsRendered,
                        "Vanilla compact effect renderer/HUD suppression not reached");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-effects-compact.png", mc.getMainRenderTarget(), ignored -> {});
                mc.player.closeContainer();
                advance(Step.VERIFY_HUD_RETURN);
            } else if (step == Step.VERIFY_HUD_RETURN && age > 20) {
                require(mc.screen == null && mc.player.getActiveEffects().size() == 2, "Closing inventory changed active effects");
                Screenshot.grab(mc.gameDirectory, "inventory-smoke-effects-hud.png", mc.getMainRenderTarget(), ignored -> {});
                mc.options.guiScale().set(savedScale);
                advance(Step.VERIFY_MENU_MOVES);
            } else if (step == Step.VERIFY_MENU_MOVES) {
                if (!menuMoves.tick(mc)) return;
                advance(Step.VERIFY_LATE_CONFIRMATION);
            } else if (step == Step.VERIFY_LATE_CONFIRMATION) {
                if (!lateConfirmation.tick(mc)) return;
                advance(Step.VERIFY_FEEDBACK_TOAST);
            } else if (step == Step.VERIFY_FEEDBACK_TOAST) {
                if (!feedbackToast.tick(mc)) return;
                Craftable.LOGGER.warn("INVENTORY_SMOKE PASS: inventory layout and sword ghost, chest preview/filter/create, 200 ticks tab/index stress, physical workbench ghost, repeated creative E without requests, mode-switch returns, spectator isolation, explicit survival/adventure reopen, native wide/compact effects and HUD return, and failure toast reuse");
                ClientTestRun.pass();
                step = Step.PASSED;
                mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
                mc.stop();
            }
        } catch (Throwable failure) {
            try { lateConfirmation.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (savedScale >= 0) mc.options.guiScale().set(savedScale);
            if (started) mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
            ClientTestRun.fail();
            Craftable.LOGGER.error("INVENTORY_SMOKE FAIL step " + step, failure);
            step = Step.FAILED;
            mc.stop();
        }
    }
    @SubscribeEvent public static void effects(net.neoforged.neoforge.client.event.ScreenEvent.RenderInventoryMobEffects event) {
        if (!ClientTestRun.enabledFor("inventory") || !(event.getScreen() instanceof AmbientInventoryScreen)) return;
        // 中文：观察真实原版渲染器，不另造测试专用效果布局。
        // Observe the real vanilla renderer; don't implement a test-only effect layout.
        if (event.isCompact()) compactEffectsRendered = true;
        else wideEffectsRendered = true;
    }
    @SubscribeEvent public static void toastAdded(ToastAddEvent event) {
        if (step == Step.VERIFY_FEEDBACK_TOAST) feedbackToast.added(event.getToast());
    }
    @SubscribeEvent public static void frameRendered(RenderFrameEvent.Post event) {
        if (step == Step.VERIFY_FEEDBACK_TOAST && !Minecraft.getInstance().options.hideGui) feedbackToast.rendered();
    }
    private static void advance(Step next) {
        step = next;
        age = 0;
        Craftable.LOGGER.warn("INVENTORY_SMOKE step {}", step);
    }
    private static void showSwordGhost(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book) {
        var mc = Minecraft.getInstance();
        // 中文：夹具仅用于渲染，不放置合成配方或修改库存。
        // Rendering-only fixture; no crafting placement or inventory mutation.
        book.setupGhostRecipe(mc.level.getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("diamond_sword")).orElseThrow(), mc.player.containerMenu.slots);
    }
    private static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }
    private static void searchSticks(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent book) {
        if (!book.isVisible()) book.toggleVisibility();
        Minecraft.getInstance().player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
        ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.STICK.getDescription().getString());
        book.recipesUpdated();
    }
    private static void require(boolean condition, String description) { if (!condition) throw new AssertionError(description); }
    @SuppressWarnings("unchecked")
    private static void stressRecipeBook(Minecraft mc) throws ReflectiveOperationException {
        var book = RecipeBookProjection.component(mc.screen);
        var accessor = (RecipeBookComponentAccessor) book;
        // 中文：反射限于开发夹具，用集合重绑后残留的前动画帧索引重现历史越界反例。
        // Reflection is confined to this development-only harness. Deliberately
        // reproduce a previous animation frame's index after a collection rebind.
        var tabsField = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("tabButtons");
        tabsField.setAccessible(true);
        var tabs = (java.util.List<net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton>) tabsField.get(book);
        accessor.craftable$getSearchBox().setValue(age % 5 == 0 ? Items.STICK.getDescription().getString() : "");
        mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, age % 3 == 0);
        book.recipesUpdated();
        var tab = tabs.get(age % tabs.size());
        if (tab.visible) book.mouseClicked(tab.getX() + 5, tab.getY() + 5, 0);
        var indexField = RecipeButton.class.getDeclaredField("currentIndex");
        indexField.setAccessible(true);
        for (var button : ((RecipeBookPageAccessor) accessor.craftable$getRecipeBookPage()).craftable$getButtons()) {
            if (!button.visible) continue;
            indexField.setInt(button, 9);
            RecipeButtonTargetResolver.status(button);
            RecipeButtonTargetResolver.lifecycle(button);
            RecipeButtonTargetResolver.preferredRecipe(button);
            button.getRecipe();
            indexField.setInt(button, 3);
            button.getTooltipText();
        }
    }
    private static RecipeButton button() {
        var book = RecipeBookProjection.component(Minecraft.getInstance().screen);
        require(book != null, "No recipe book");
        var page = ((RecipeBookComponentAccessor) book).craftable$getRecipeBookPage();
        return ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible).findFirst()
                .orElseThrow(() -> new AssertionError("No visible recipe button"));
    }
}
