package org.craftablemc.craftable.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.network.SettingsPayloads;

/** 中文：客户端线程上的原版风格设置视图，复用注册配置定义；本页草稿与远端只读规则彼此独立。
 * 只有未开放局域网的当前单人世界可保存世界草稿；打开或重读设置从不获取合成授权。
 * English: Client-thread vanilla-style settings backed by registered definitions, separating this page's draft from remote read-only rules.
 * Only the current unpublished singleplayer world may save a world draft; opening or rereading settings never grants crafting authority. */
public final class CraftableSettingsScreen extends OptionsSubScreen {
    private enum Page { MAIN, WORLD }
    private final Page page;
    private final RulesState rules;
    private final ConfigSettings.Draft draft;
    private final List<NativeConfigOption<?>> configOptions = new ArrayList<>();
    private final List<Button> ruleRows = new ArrayList<>();
    private StringWidget heading;
    private Button retry, worldEntry, advanced, done, defaults, directPreparationEntry;

    public CraftableSettingsScreen(Screen parent) {
        this(parent, Page.MAIN, new RulesState(), new ConfigSettings.Draft(CraftableClientConfig.SPEC));
    }

    private CraftableSettingsScreen(Screen parent, Page page, RulesState rules, ConfigSettings.Draft draft) {
        super(parent, Minecraft.getInstance().options, text(page == Page.MAIN ? "title"
                : draft == null ? "world" : "edit_world"));
        this.page = page;
        this.rules = rules;
        this.draft = draft;
    }

    @Override protected void init() {
        configOptions.clear(); ruleRows.clear(); retry = null; worldEntry = null; directPreparationEntry = null;
        if (draft != null) draft.refreshUnchanged();
        super.init();
        rules.request(false);
        updateRules();
    }

    @Override protected void addOptions() {
        if (draft != null) {
            var spec = page == Page.MAIN ? CraftableClientConfig.SPEC : CraftableServerConfig.SPEC;
            for (var value : NativeConfigOption.definitions(spec))
                if (NativeConfigOption.scalar(value)) configOptions.add(NativeConfigOption.create(value, draft));
            list.addSmall(configOptions.stream().map(NativeConfigOption::option).toArray(net.minecraft.client.OptionInstance<?>[]::new));
        }
        if (page == Page.MAIN) {
            worldEntry = Button.builder(text("view_world"), b -> openWorld()).width(150).build();
            advanced = Button.builder(text("advanced"), b -> openAdvanced()).width(150)
                    .tooltip(Tooltip.create(text("advanced.tooltip"))).build();
            list.addSmall(worldEntry, advanced);
            var preferences = label(text("preferences_only"));
            preferences.setTooltip(Tooltip.create(text("preferences_only.tooltip")));
        } else {
            if (draft == null) {
                // 中文：文案共用 spec，但远端权威值不能由客户端本地 SERVER spec 冒充。
                // Captions share the spec, but values never come from a local
                // SERVER spec pretending to be the remote server's authority.
                for (var value : NativeConfigOption.definitions(CraftableServerConfig.SPEC)) {
                    if (!NativeConfigOption.scalar(value)) continue;
                    var row = Button.builder(text("rules_pending"), b -> {}).width(150)
                            .tooltip(Tooltip.create(SettingTooltip.description(value))).build();
                    row.active = false;
                    ruleRows.add(row);
                }
                for (int i = 0; i < ruleRows.size(); i += 2)
                    list.addSmall(ruleRows.get(i), i + 1 < ruleRows.size() ? ruleRows.get(i + 1) : null);
            }
            directPreparationEntry = Button.builder(text("direct_preparation"), b -> openDirectPreparation())
                    .width(150).tooltip(Tooltip.create(text("direct_preparation.tooltip"))).build();
            NativeOptionRows.appendSmall(list, directPreparationEntry);
        }
    }

    @Override protected void addTitle() {
        heading = layout.addToHeader(new StringWidget(Math.max(0, width - 20), 9, title, font));
        heading.setTooltip(Tooltip.create(title));
    }

    @Override protected void repositionElements() {
        if (heading != null) heading.setWidth(Math.max(0, width - 20));
        super.repositionElements();
    }

    @Override protected void addFooter() {
        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
        if (draft == null) {
            retry = footer.addChild(Button.builder(text("retry"), b -> { rules.request(true); updateRules(); })
                    .width(150).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());
            return;
        }
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(100).build());
        defaults = footer.addChild(Button.builder(text("defaults"), b -> {
            if (!editable()) { updateRules(); return; }
            list.applyUnsavedChanges();
            draft.resetDefaults();
            // 中文：使用新页面重建原版控件，避免保留的 OptionsSubScreen 布局被重复追加。
            // Rebuild the native controls on a fresh screen, without adding
            // duplicate rows to the retained OptionsSubScreen layout.
            minecraft.setScreen(new CraftableSettingsScreen(lastScreen, page, rules, draft));
        }).width(100).tooltip(Tooltip.create(text("defaults.tooltip"))).build());
        done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> save()).width(100).build());
    }

    private boolean editable() { return page == Page.MAIN || canEditWorld(); }

    /** 中文：先提交延迟滑块到草稿，再复查权限后保存；这两次检查守护不同副作用，不应合并。
     * English: Flush deferred sliders into the draft, then recheck permission before saving; the two checks protect different side effects. */
    private void save() {
        if (draft == null || !editable()) { updateRules(); return; }
        list.applyUnsavedChanges();
        // 中文：滑块延迟值写入后再次复查；远端及已公开局域网没有修改规则的网络路径。
        // Recheck after flushing delayed sliders. There is no network path
        // for changing rules on remote or published LAN servers.
        if (!editable()) { updateRules(); return; }
        draft.save();
        if (page == Page.WORLD) rules.invalidate();
        minecraft.setScreen(lastScreen);
    }

    @Override public void onClose() {
        // 中文：退出/取消只丢弃本页草稿，不写入 spec。
        // Escape/Cancel discards this page's draft without touching the spec.
        minecraft.setScreen(lastScreen);
    }

    private void openWorld() {
        if (!rules.connected()) { updateRules(); return; }
        list.applyUnsavedChanges();
        var worldDraft = canEditWorld() ? new ConfigSettings.Draft(CraftableServerConfig.SPEC,
                NativeConfigOption.definitions(CraftableServerConfig.SPEC)) : null;
        minecraft.setScreen(new CraftableSettingsScreen(this, Page.WORLD, rules, worldDraft));
    }

    private void openAdvanced() {
        // 中文：专业页拥有自己的持久化；普通页返回时只刷新未编辑值，普通页取消不能撤销专业页已保存值。
        // English: The professional editor owns its persistence; returning refreshes untouched values, and native Cancel cannot undo professional saves.
        list.applyUnsavedChanges();
        rules.invalidate();
        var mod = ModList.get().getModContainerById(Craftable.MOD_ID).orElseThrow();
        // 中文：专业页返回只刷新未改动值，保留本页明确编辑但尚未保存的个人偏好。
        // Refresh untouched values when returning from the professional UI,
        // while keeping explicitly edited, unsaved native preferences.
        var parent = new CraftableSettingsScreen(lastScreen, Page.MAIN, rules, draft);
        minecraft.setScreen(CraftableClient.configurationScreen(mod, parent));
    }

    private void openDirectPreparation() {
        if (!rules.connected() || draft == null && rules.crafting == null) return;
        list.applyUnsavedChanges();
        // 中文：变灰不是授权边界；迟到回调也必须检查当前模式与世界所有权。
        // A disabled entry is not an authorization boundary: stale callbacks
        // must observe the current mode and world ownership as well.
        if (!customDirectPreparation() || draft != null && !canEditWorld()) { updateRules(); return; }
        minecraft.setScreen(new DirectPreparationSettingsScreen(this, draft, this::canEditWorld,
                () -> rules.sameContext() && rules.crafting != null ? rules.crafting.directPreparation() : null));
    }

    boolean customDirectPreparation() {
        if (draft == null) return rules.sameContext() && rules.crafting != null
                && rules.crafting.directPreparation().mode() == DirectPreparationPolicy.Mode.CUSTOM;
        for (var option : configOptions)
            if (option.configValue().getDefault() instanceof DirectPreparationPolicy.Mode)
                return draft.get(option.configValue()) == DirectPreparationPolicy.Mode.CUSTOM;
        return false;
    }

    private StringWidget label(Component value) {
        var row = new StringWidget(310, 20, value, font).alignLeft();
        list.addSmall(row, null);
        return row;
    }

    @Override public void tick() {
        if (!rules.sameContext()) rules.invalidate();
        // 中文：返回时父页可能已初始化；编辑使旧读取失效后补读一次，不在有效时周期轮询。
        // Returning can reuse an initialized parent. Fetch once after an
        // editor invalidates the read, not periodically while it is valid.
        rules.request(false);
        if (rules.pending && System.nanoTime() - rules.requestedAt >= 5_000_000_000L) {
            rules.pending = false; rules.unavailable = true;
        }
        updateRules();
    }

    private void updateRules() {
        if (worldEntry != null) {
            worldEntry.active = rules.connected();
            String key = canEditWorld() ? "edit_world" : "view_world";
            worldEntry.setMessage(text(key));
            worldEntry.setTooltip(Tooltip.create(text(worldEntry.active ? key + ".tooltip" : "world_requires_level")));
        }
        var values = worldValues();
        for (int i = 0; i < ruleRows.size(); i++) ruleRows.get(i).setMessage(i < values.size() ? values.get(i) : text("rules_pending"));
        if (retry != null) {
            retry.active = rules.connected() && !rules.pending;
            // 中文：请求/错误反馈留在底栏提示与旁白中，不向选项列表追加常驻状态行。
            // Keep request/error feedback in the footer tooltip and narration,
            // rather than adding a persistent status row to the options list.
            retry.setTooltip(Tooltip.create(Component.empty().append(text("retry.tooltip"))
                    .append("\n").append(rules.status())));
        }
        if (draft != null && page == Page.WORLD) {
            boolean allowed = canEditWorld();
            for (var option : configOptions) list.findOption(option.option()).active = allowed;
            done.active = allowed; defaults.active = allowed;
            done.setTooltip(allowed ? null : Tooltip.create(text("world_changed")));
        }
        if (directPreparationEntry != null) {
            boolean custom = customDirectPreparation();
            directPreparationEntry.active = rules.connected() && custom
                    && (draft != null ? canEditWorld() : rules.crafting != null);
            directPreparationEntry.setTooltip(Tooltip.create(text(custom ? "direct_preparation.tooltip"
                    : "direct.custom_only.tooltip")));
        }
    }

    private List<Component> worldValues() {
        if (!rules.sameContext() || rules.scan == null || rules.crafting == null) return List.of();
        Map<String, Object> values = Map.of("horizontalRadius", rules.scan.horizontalRadius(),
                "verticalRadius", rules.scan.verticalRadius(), "includeEnderChest", rules.scan.includeEnderChest(),
                "surplusDelivery", rules.crafting.surplusDelivery(),
                "maxCraftBatch", rules.crafting.maxBatches(), "maxPlanSteps", rules.crafting.maxPlanSteps(),
                "directPreparationMode", rules.crafting.directPreparation().mode(),
                "timeCompression", rules.crafting.timeCompression(), "maxFurnaceBatches", rules.crafting.maxFurnaceBatches());
        return NativeConfigOption.definitions(CraftableServerConfig.SPEC).stream().filter(NativeConfigOption::scalar).<Component>map(value -> {
            Object current = values.get(value.getPath().getLast());
            return CommonComponents.optionNameValue(Component.translatable(value.getSpec().getTranslationKey()),
                    current == null ? text("rules_unavailable") : NativeConfigOption.display(value, current));
        }).toList();
    }

    private boolean canEditWorld() {
        return minecraft != null && rules.connected() && minecraft.hasSingleplayerServer()
                && !minecraft.getSingleplayerServer().isPublished() && CraftableServerConfig.SPEC.isLoaded();
    }

    @Override public Component getNarrationMessage() {
        var result = Component.empty().append(super.getNarrationMessage());
        if (page == Page.WORLD && draft == null) {
            result.append(". ").append(rules.status());
            result.append(". ").append(text("world_read_only"));
            worldValues().forEach(value -> result.append(". ").append(value));
        } else if (page == Page.WORLD && !canEditWorld()) {
            result.append(". ").append(text("world_changed"));
        }
        return result;
    }

    /** 中文：设置专用回复只更新当前请求的只读展示，不代表资源版本、浏览租约或执行保证。
     * English: A settings-only reply updates the current read-only display, never a resource version, browse lease or execution promise. */
    public static void receiveRules(long revision, EnvironmentScanSettings scan, CraftableServerConfig.CraftingRules crafting) {
        var mc = Minecraft.getInstance();
        CraftableSettingsScreen screen;
        if (mc.screen instanceof CraftableSettingsScreen settings) screen = settings;
        else if (mc.screen instanceof DirectPreparationSettingsScreen editor) screen = editor.settingsParent();
        else return;
        var state = screen.rules;
        if (!state.sameContext() || !state.connected() || !state.pending || state.revision != revision) return;
        state.scan = java.util.Objects.requireNonNull(scan);
        state.crafting = java.util.Objects.requireNonNull(crafting);
        state.pending = false; state.unavailable = false;
        screen.updateRules();
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings." + key, args);
    }

    /** 中文：同一设置导航树共用的只读请求状态，绑定连接/世界/集成服对象，拒绝跨上下文迟到回复。
     * English: Read-only request state shared by one settings navigation tree, binding connection/world/integrated-server objects against late replies. */
    private static final class RulesState {
        private final Object connection = Minecraft.getInstance().getConnection();
        private final Object level = Minecraft.getInstance().level;
        private final Object server = Minecraft.getInstance().getSingleplayerServer();
        private long revision = -1, requestedAt;
        private boolean requested, pending, unavailable;
        private EnvironmentScanSettings scan;
        private CraftableServerConfig.CraftingRules crafting;
        boolean sameContext() {
            var mc = Minecraft.getInstance();
            return connection == mc.getConnection() && level == mc.level && server == mc.getSingleplayerServer();
        }
        boolean connected() { return connection != null && level != null && sameContext(); }
        void request(boolean retry) {
            if (!connected() || pending || requested && !retry) return;
            invalidate();
            requested = true; pending = true; revision = ClientRequestSequence.next(); requestedAt = System.nanoTime();
            PacketDistributor.sendToServer(new SettingsPayloads.RulesRequest(revision));
        }
        void invalidate() {
            revision = -1; requested = false; pending = false; unavailable = false; scan = null; crafting = null;
        }
        Component status() {
            if (!connected()) return text("rules_not_connected");
            if (pending) return text("rules_pending");
            if (scan != null && crafting != null) return text(Minecraft.getInstance().hasSingleplayerServer()
                    ? "rules_from_world" : "rules_from_server");
            return text(unavailable ? "rules_unavailable" : "rules_pending");
        }
    }
}
