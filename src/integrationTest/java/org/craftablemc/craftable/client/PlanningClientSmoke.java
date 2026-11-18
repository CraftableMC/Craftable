package org.craftablemc.craftable.client;

import static org.craftablemc.craftable.client.PlanningClientFixture.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;

/** 中文：隔离且显式启用的客户端/网络/UI 验收；反射仅观察 UI，所有确认仍走真实网络处理器，避免夹具绕过权威链。
 * English: Isolated, opt-in client/network/UI acceptance. Reflection inspects UI state
 * only; every confirmation still travels through the real network handler. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class PlanningClientSmoke {
    enum Stage {
        WORLD_FIXTURE,
        KNOWLEDGE_WARMUP,
        RECIPE_BOOK_SEARCH,
        BROWSE_AUTHORITY_AND_SHIFT_C,
        DIAGNOSTIC_LIFECYCLE_AND_GRAPH,
        CANDIDATE_NAVIGATION,
        UNAVAILABLE_PIN,
        FULL_CONFIRM_AND_LEASE_READINESS,
        FULL_CONFIRM_ACCOUNTING,
        PARTIAL_FIXTURE,
        PARTIAL_REVIEW,
        PARTIAL_CONFIRM,
        PARTIAL_ACCOUNTING,
        MIXED_FIXTURE,
        MIXED_REVIEW,
        QUANTITY_AND_COST_REVIEW,
        QUANTITY_CONFIRM,
        QUANTITY_ACCOUNTING,
        QUEUED_ACTION_FIXTURE,
        QUEUED_ACTION_CANCELLATION,
        GUI_SCALE_ONE,
        GUI_SCALE_THREE,
        ENGLISH_PRESENTATION,
        DETAIL_REFRESH_LATENCY,
        MENU_MODE_CHANGE,
        CREATIVE_RETURN,
        PROFESSIONAL_SETTINGS_INDEX,
        PROFESSIONAL_SETTINGS_CLIENT,
        OUTPUT_VARIANTS,
        DETAIL_QUANTITY,
        BULK_QUANTITY,
        NON_MONOTONE_MAXIMUM,
        DEFERRED_MAXIMUM_PROJECTION,
        SETTINGS,
        STOPPED
    }
    private static Stage stage = Stage.WORLD_FIXTURE;
    private static int age;
    private static boolean started;
    private static boolean oldPauseOnLostFocus;
    private static int oldScale, oldWidth, oldHeight;
    private static String oldLanguage;
    private static final BrowsingScenario browsing = new BrowsingScenario();
    private static final OutputVariantScenario outputVariants = new OutputVariantScenario();
    private static final DetailQuantityScenario detailQuantity = new DetailQuantityScenario();
    private static final BulkQuantityScenario bulkQuantity = new BulkQuantityScenario();
    private static final DeferredMaximumProjectionScenario maximumProjection = new DeferredMaximumProjectionScenario();
    private static final SettingsScenario settings = new SettingsScenario();
    private static final PlanningReviewScenario review = new PlanningReviewScenario();
    private static final PlanningQuantityScenario quantity = new PlanningQuantityScenario();
    private static final PlanningPresentationScenario presentation = new PlanningPresentationScenario();

    // 中文：MAX 证书可在请求的同一 Post 内完成；在规划器前驱动普通嵌入层 tick 并同步观察真实 pending，不能要求计算必须慢满一 tick。
    // A MAX certificate can finish in the same Post event in which the
    // overlay asks for it. Drive the ordinary overlay tick before the planner
    // callback and inspect its real pending request synchronously; the fixture
    // must not require a calculation to remain slow for a whole game tick.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void observeQueuedCancellation(ClientTickEvent.Post event) {
        if (!ClientTestRun.enabledFor("planning", "settings") || stage != Stage.QUEUED_ACTION_CANCELLATION) return;
        var mc = Minecraft.getInstance();
        try {
            quantity.observeQueuedCancellation(mc);
        } catch (Throwable error) {
            fail(mc, error);
        }
    }

    // 中文：在嵌入层本 tick 发请求后立即观察；若同优先级等到下个 tick，快集成服可能已回包，取消夹具会错过整个在途状态。
    // Observe after the overlay has sent this tick's request. At the same
    // priority a fast integrated-server reply can arrive before our next tick,
    // making the in-flight cancellation fixture miss the entire pending state.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!ClientTestRun.enabledFor("planning", "settings") || stage == Stage.STOPPED) return;
        var mc = Minecraft.getInstance();
        try {
            if (!started) {
                if (!(mc.screen instanceof TitleScreen)) return;
                started = true;
                ClientTestRun.start();
                // 中文：后台自动窗口也须让集成服与断言 tick 同步推进，避免暂停造成假超时。
                // Keep integrated-server progress aligned with assertion
                // ticks even if this automated window runs in the background.
                oldPauseOnLostFocus = mc.options.pauseOnLostFocus;
                mc.options.pauseOnLostFocus = false;
                oldScale = mc.options.guiScale().get();
                oldWidth = mc.getWindow().getScreenWidth(); oldHeight = mc.getWindow().getScreenHeight();
                oldLanguage = mc.getLanguageManager().getSelected();
                mc.createWorldOpenFlows().createFreshLevel("craftable-planning-smoke-" + System.currentTimeMillis(),
                        new LevelSettings("Craftable planning smoke", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
            quantity.verifyPendingMaximumControl();
            // 中文：设置组含真实语言重载和专业页截图；各步骤看门狗仍更严格，只为持续推进的全组留较长总上限。
            // The settings suite now includes additional real language reloads
            // and professional-page captures. Its per-phase watchdog remains
            // stricter; allow the full, progressing suite the long-stage cap.
            if (++age > (stage == Stage.BROWSE_AUTHORITY_AND_SHIFT_C || stage == Stage.BULK_QUANTITY || stage == Stage.SETTINGS ? 1400 : 600)) throw new AssertionError("Timeout at case " + failureCase() + ", pending="
                    + (overlay() == null ? "closed" : field(overlay(), "pending") + ", max=" + field(overlay(), "maximum")
                    + ", dirty=" + field(overlay(), "dirty") + ", draft=" + (draft() == null ? "none" : draft().view().code())));
            switch (stage) {
                case WORLD_FIXTURE, KNOWLEDGE_WARMUP, RECIPE_BOOK_SEARCH, BROWSE_AUTHORITY_AND_SHIFT_C -> setupAndBrowse(mc);
                case DIAGNOSTIC_LIFECYCLE_AND_GRAPH, CANDIDATE_NAVIGATION, UNAVAILABLE_PIN,
                        FULL_CONFIRM_AND_LEASE_READINESS, FULL_CONFIRM_ACCOUNTING,
                        PARTIAL_FIXTURE, PARTIAL_REVIEW, PARTIAL_CONFIRM, PARTIAL_ACCOUNTING -> {
                    if (review.tick(stage, age, mc)) {
                        if (stage == Stage.FULL_CONFIRM_AND_LEASE_READINESS && ClientTestRun.leaseDiagnostic()) complete(mc);
                        else next();
                    }
                }
                case MIXED_FIXTURE, MIXED_REVIEW, QUANTITY_AND_COST_REVIEW, QUANTITY_CONFIRM,
                        QUANTITY_ACCOUNTING, QUEUED_ACTION_FIXTURE, QUEUED_ACTION_CANCELLATION -> {
                    if (quantity.tick(stage, age, mc)) next();
                }
                case GUI_SCALE_ONE, GUI_SCALE_THREE, ENGLISH_PRESENTATION, DETAIL_REFRESH_LATENCY,
                        MENU_MODE_CHANGE, CREATIVE_RETURN, PROFESSIONAL_SETTINGS_INDEX, PROFESSIONAL_SETTINGS_CLIENT -> {
                    if (presentation.tick(stage, age, mc)) next();
                }
                case OUTPUT_VARIANTS -> { if (outputVariants.tick(mc)) next(); }
                case DETAIL_QUANTITY -> { if (detailQuantity.tick(mc)) next(); }
                case BULK_QUANTITY -> { if (bulkQuantity.tick(mc)) next(); }
                case NON_MONOTONE_MAXIMUM -> { if (quantity.nonMonotoneMaximum(mc)) next(); }
                case DEFERRED_MAXIMUM_PROJECTION -> { if (maximumProjection.tick(mc)) next(); }
                case SETTINGS -> { if (settings.tick(mc)) complete(mc); }
                case STOPPED -> {}
            }
        } catch (Throwable error) {
            fail(mc, error);
        }
    }

    private static void setupAndBrowse(Minecraft mc) throws Exception {
        if (stage == Stage.WORLD_FIXTURE) {
            mc.getSingleplayerServer().execute(() -> {
                var player = serverPlayer();
                player.teleportTo(0.5, -60, 0.5);
                var level = player.serverLevel();
                level.setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                level.setBlockAndUpdate(CHEST.above(), Blocks.AIR.defaultBlockState());
                stock(true);
                configured = true;
            });
            next();
        } else if (stage == Stage.KNOWLEDGE_WARMUP && configured && age > 60) {
            if (ClientTestRun.settingsOnly()) {
                // 中文：设置专项有意过滤，延迟 MAX 仍留在完整规划组，不能把专项冒充全量。
                // Settings is intentionally filtered; deferred MAX stays
                // in the full planning suite.
                stage = Stage.SETTINGS; age = 0;
                Craftable.LOGGER.warn("CLIENT_SETTINGS FILTERED: {}", caseName());
                return;
            }
            if (!org.craftablemc.craftable.recipe.ClientKnowledgeProbe.sliced(mc)) return;
            org.craftablemc.craftable.recipe.ClientKnowledgeProbe.measure(mc);
            mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
            mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
            // 新物品会触发原版解锁包并带回服务端书本设置；本地开关也须按真实 UI 同步，不能让旧关闭值覆盖夹具。
            // New items can unlock recipes and echo server book settings; sync this local toggle as real UI does, rather than letting an old closed flag overwrite it.
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(
                    RecipeBookType.CRAFTING, true, false));
            mc.setScreen(new InventoryScreen(mc.player));
            next();
        } else if (stage == Stage.RECIPE_BOOK_SEARCH && mc.screen instanceof AmbientInventoryScreen screen) {
            var book = screen.getRecipeBookComponent();
            if (!book.isVisible()) book.toggleVisibility();
            ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
            book.recipesUpdated();
            next();
        } else if (stage == Stage.BROWSE_AUTHORITY_AND_SHIFT_C && age > 80) {
            if (!browsing.tick(mc, age, PICK)) return;
            require(org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.lifecycle(PICK)
                    == org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.Lifecycle.KNOWN,
                    "Production local snapshot never became authorized");
            require(org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.get(PICK, false)
                    == org.craftablemc.craftable.api.CraftingStatus.CRAFTABLE,
                    "Production local recursive preview failed: " + org.craftablemc.craftable.client.recipebook.ClientRecipeStatusStore.reason(PICK));
            var book = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
            var accessor = (RecipeBookComponentAccessor) book;
            accessor.craftable$getSearchBox().setFocused(false);
            var page = accessor.craftable$getRecipeBookPage();
            var button = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                    && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(PICK))).findFirst().orElseThrow();
            var hover = RecipeBookPage.class.getDeclaredField("hoveredButton");
            hover.setAccessible(true); hover.set(page, button);
            review.openDetails(mc);
            next();
        }
    }

    private static void fail(Minecraft mc, Throwable error) {
        try { outputVariants.abort(); } catch (Throwable cleanupFailure) { error.addSuppressed(cleanupFailure); }
        try { settings.abort(mc); } catch (Throwable cleanupFailure) { error.addSuppressed(cleanupFailure); }
        try { DiagnosticLifecycleScenario.abort(); } catch (Throwable cleanupFailure) { error.addSuppressed(cleanupFailure); }
        ClientTestRun.fail();
        Craftable.LOGGER.error("PLANNING_SMOKE FAIL case " + failureCase(), error);
        stage = Stage.STOPPED;
        restoreOptions();
        mc.stop();
    }

    private static void restoreOptions() {
        if (!started) return;
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = oldPauseOnLostFocus;
        mc.options.guiScale().set(oldScale);
        mc.options.languageCode = oldLanguage;
        mc.getLanguageManager().setSelected(oldLanguage);
        mc.getWindow().setWindowed(oldWidth, oldHeight);
        mc.options.save();
    }
    private static void complete(Minecraft mc) {
        ClientTestRun.completedPlanning();
        stage = Stage.STOPPED; restoreOptions(); mc.stop();
    }

    private static String caseName() { return stage.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    private static String failureCase() {
        var progress = review.progress(stage);
        return progress.isEmpty() ? caseName() : caseName() + "/" + progress;
    }
    private static void next() { stage = Stage.values()[stage.ordinal() + 1]; age = 0; Craftable.LOGGER.warn("CLIENT_CASE {}", caseName()); }
}
