package org.craftablemc.craftable.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：只在客户端装载的菜单与专业设置集成入口；专用服务端不能装载本类及其 UI 依赖。
 * 原版页和 NeoForge 页共用注册定义，专业页仍由 NeoForge 负责撤销及持久化。
 * English: Client-only menu and professional-settings integration; dedicated servers must not load this class or its UI dependencies.
 * Native and NeoForge pages share registered definitions, while NeoForge retains undo and persistence ownership. */
@Mod(value = Craftable.MOD_ID, dist = Dist.CLIENT)
public final class CraftableClient {
    public CraftableClient(ModContainer container, net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) ->
                event.register(org.craftablemc.craftable.menu.CraftableMenus.AMBIENT_INVENTORY.get(),
                        org.craftablemc.craftable.client.menu.AmbientInventoryScreen::new));
        // 中文：模组页保留完整专业配置，普通设置仅换用原版控件；两者共用 spec，不另建持久化格式。
        // Mods keeps the complete NeoForge configuration UI as an advanced
        // fallback. The ordinary Options landing uses native option widgets
        // backed by these same specs, not a parallel persistence format.
        container.registerExtensionPoint(IConfigScreenFactory.class, CraftableClient::configurationScreen);
    }

    static ConfigurationScreen configurationScreen(ModContainer container, Screen parent) {
        return new ConfigurationScreen(container, parent, (screen, type, config, title) -> new Section(screen, type, config, title));
    }

    /** 中文：注册元数据驱动专业列表和枚举翻译；控件状态与回调都复查世界权限，不能仅靠变灰阻止写入。
     * NeoForge 拥有页面导航、撤销及持久化，本类只在客户端线程适配表现。
     * English: Registered metadata drives professional lists and translated enums; both widgets and callbacks recheck world permissions.
     * Disabled styling alone is not a write barrier. NeoForge owns navigation, undo and persistence; this class adapts client-thread presentation. */
    private static final class Section extends ConfigurationScreen.ConfigurationSectionScreen {
        private final Object connection, level;
        private final List<net.minecraft.client.gui.components.AbstractWidget> policyControls = new ArrayList<>();
        private final List<Element> recipeListControls = new ArrayList<>();
        Section(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
            super(parent, type, config, title);
            connection = Minecraft.getInstance().getConnection();
            level = Minecraft.getInstance().level;
        }
        Section(Context context, Component title) {
            super(context, title);
            // 中文：子页继承整棵导航树的安全身份，不能通过重新拍摄当前连接使过期父页重新获得写权限。
            // A child inherits its navigation tree's security identity; recapturing the current connection must not revive an expired parent.
            if (context.parent() instanceof Section parent) {
                connection = parent.connection;
                level = parent.level;
            } else {
                connection = Minecraft.getInstance().getConnection();
                level = Minecraft.getInstance().level;
            }
        }

        private boolean editablePolicy() { return worldEditable() && connection == minecraft.getConnection() && level == minecraft.level; }
        private boolean editableValue() { return context.modSpec() != CraftableServerConfig.SPEC || editablePolicy(); }
        private boolean editableRecipeLists() { return editablePolicy() && customPreparationMode(); }

        @Override protected Component getTooltipComponent(String key, ModConfigSpec.Range<?> range) {
            var metadata = getValueSpec(key);
            var numeric = ConfigSettings.values(context.modSpec()).stream()
                    .filter(value -> value.getSpec() == metadata && SettingTooltip.hasNumericRange(value))
                    .findFirst();
            if (numeric.isEmpty()) return super.getTooltipComponent(key, range);
            // 中文：屏蔽只作用于数值控件的 NeoForge 范围尾注，使标签和控件都复用注册的范围/默认值说明。
            // NeoForge normally adds its own Range.toString() footer for the
            // numeric widget, but not its label. Suppress that path and give
            // both surfaces the same registered range/default footer as native.
            return SettingTooltip.withNumericFooter(super.getTooltipComponent(key, null), numeric.get());
        }

        @Override protected ConfigurationScreen.ConfigurationSectionScreen rebuild() {
            policyControls.clear(); recipeListControls.clear(); return super.rebuild();
        }

        @Override public void tick() {
            for (var control : policyControls) control.active = editablePolicy();
            for (var element : recipeListControls) {
                element.widget().active = editableRecipeLists();
                element.widget().setTooltip(Tooltip.create(recipeListTooltip(element.tooltip())));
            }
            // 中文：公开局域网或断线可能发生在两帧之间；恢复可编辑时仍按真实撤销/默认状态计算，不盲目启用底栏。
            // A publication/disconnect can happen between rendered frames.
            // Recompute from the real undo/default state; never blindly
            // re-enable a footer when the current context becomes editable.
            setUndoButtonstate(undoManager.canUndo());
            setResetButtonstate(isAnyNondefault());
        }

        @Override protected void setUndoButtonstate(boolean enabled) {
            super.setUndoButtonstate(enabled && (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()));
        }

        @Override protected void setResetButtonstate(boolean enabled) {
            super.setResetButtonstate(enabled && (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()));
        }

        @Override protected void createUndoButton() {
            super.createUndoButton(); undoButton = guardedFooter(undoButton);
        }

        @Override protected void createResetButton() {
            if (context.modSpec() != CraftableServerConfig.SPEC) {
                super.createResetButton();
                return;
            }
            // 中文：Reset 的批量撤销闭包也会写注册值，必须在每次重放时检查权限；只保护外层按钮并不够。
            // Bulk Reset undo closures also write registered values: recheck each replay, not merely the originating button.
            resetButton = Button.builder(ConfigurationScreen.RESET, button -> {
                if (!editablePolicy()) return;
                var steps = new ArrayList<ConfigurationScreen.UndoManager.Step<?>>();
                for (var entry : context.entries()) {
                    if (entry.getRawValue() instanceof ModConfigSpec.ConfigValue<?> value
                            && !(getValueSpec(entry.getKey()) instanceof ModConfigSpec.ListValueSpec) && isNonDefault(value))
                        addResetStep(steps, entry.getKey(), value);
                }
                undoManager.add(steps);
                rebuild();
            }).tooltip(Tooltip.create(ConfigurationScreen.RESET_TOOLTIP)).width(Button.SMALL_WIDTH).build();
        }

        @SuppressWarnings("unchecked")
        private <T> void addResetStep(List<ConfigurationScreen.UndoManager.Step<?>> steps, String key,
                ModConfigSpec.ConfigValue<T> value) {
            Consumer<T> write = guardedTarget(candidate -> { value.set(candidate); onChanged(key); });
            steps.add(undoManager.step(write, (T) getValueSpec(key).correct(null), write, value.getRaw()));
        }

        private Button guardedFooter(Button original) {
            var guarded = Button.builder(original.getMessage(), button -> {
                if (context.modSpec() != CraftableServerConfig.SPEC || editablePolicy()) original.onPress();
            }).width(original.getWidth()).tooltip(original.getTooltip()).build();
            guarded.active = original.active;
            return guarded;
        }

        /** 中文：复用 NeoForge 原布尔/整数控件及撤销机制，但真正的注册值写点必须复查当前世界身份。
         * English: Retain NeoForge's boolean/integer widgets and undo mechanism, but recheck current world ownership at the registered-value write. */
        @Override protected Element createBooleanValue(String key, ModConfigSpec.ValueSpec metadata,
                Supplier<Boolean> source, Consumer<Boolean> target) {
            return guardControl(super.createBooleanValue(key, metadata, source, guardedTarget(target)));
        }

        @Override protected Element createIntegerValue(String key, ModConfigSpec.ValueSpec metadata,
                Supplier<Integer> source, Consumer<Integer> target) {
            return guardControl(super.createIntegerValue(key, metadata, source, guardedTarget(target)));
        }

        private <T> Consumer<T> guardedTarget(Consumer<T> target) {
            return candidate -> { if (editableValue()) target.accept(candidate); };
        }

        private Element guardControl(Element element) {
            if (element == null || context.modSpec() != CraftableServerConfig.SPEC) return element;
            var control = element.getWidget(options);
            control.active = editablePolicy();
            policyControls.add(control);
            return new Element(element.name(), element.tooltip(), control, element.undoable());
        }

        @Override protected void onChanged(String key) {
            if (editableValue()) super.onChanged(key);
        }

        @Override public void onClose() {
            // 中文：专业控件即时写注册值；这里不回滚先前合法修改，但失效页面不能继续传播脏标记或保存配置。
            // Professional controls write registered values immediately. Do not undo prior legal edits, but an expired page must not propagate dirtiness or save.
            if (!editableValue()) { minecraft.setScreen(lastScreen); return; }
            super.onClose();
        }

        @Override protected Element createSection(String key, UnmodifiableConfig metadata, UnmodifiableConfig values) {
            if (metadata.isEmpty()) return null;
            return new Element(Component.translatable("neoforge.configuration.uitext.section", getTranslationComponent(key)),
                    getTooltipComponent(key, null), Button.builder(Component.translatable(getTranslationKey(key) + ".button"), button -> {
                        if (!editableValue()) return;
                        minecraft.setScreen(sectionCache.computeIfAbsent(key, ignored -> new Section(
                                Context.section(context, this, values.entrySet(), sectionSpecs(metadata), key),
                                Component.translatable("neoforge.configuration.uitext.breadcrumb.order", title,
                                        ConfigurationScreen.CRUMB_SEPARATOR, getTranslationComponent(key)))));
                    })
                        .tooltip(Tooltip.create(getTooltipComponent(key, null))).build(), false);
        }

        /** 中文：注册元数据建成后不再变动；保留条目顺序、原始值身份及只读性，适配 NeoForge 仍要求的 Map。
         * English: Registered metadata is fixed after construction; preserve entry order, raw-value identity and read-only access for NeoForge's Map API. */
        private static Map<String, Object> sectionSpecs(UnmodifiableConfig metadata) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : metadata.entrySet()) result.put(entry.getKey(), entry.getRawValue());
            return Collections.unmodifiableMap(result);
        }

        @SuppressWarnings("unchecked")
        @Override protected Element createOtherValue(String key, ModConfigSpec.ConfigValue<?> value) {
            if (!CraftableServerConfig.directPreparationValues().contains(value)) return super.createOtherValue(key, value);
            var registered = (ModConfigSpec.ConfigValue<List<String>>) value;
            var control = Button.builder(Component.translatable("screen.craftable.settings.direct.edit_ids"), button -> {
                    if (!editableRecipeLists()) return;
                        minecraft.setScreen(new RecipeIdsScreen(this, registered, getTranslationComponent(key), proposed -> {
                            var previous = List.copyOf(registered.getRaw());
                            if (previous.equals(proposed) || !editableRecipeLists()) return;
                            undoManager.add(candidate -> {
                                if (editableRecipeLists()) { registered.set(candidate); onChanged(key); }
                            }, proposed, candidate -> {
                                if (editableRecipeLists()) { registered.set(candidate); onChanged(key); }
                            }, previous);
                            rebuild();
                        }));
                    }).tooltip(Tooltip.create(recipeListTooltip(getTooltipComponent(key, null)))).build();
            control.active = editableRecipeLists();
            var element = new Element(getTranslationComponent(key), getTooltipComponent(key, null), control);
            recipeListControls.add(element);
            return element;
        }

        private Component recipeListTooltip(Component description) {
            if (!editablePolicy()) return description.copy().append("\n").append(Component.translatable("screen.craftable.settings.world_changed"));
            if (!customPreparationMode()) return description.copy().append("\n").append(Component.translatable("screen.craftable.settings.direct.custom_only.tooltip"));
            return description;
        }

        @Override protected <T extends Enum<T>> Element createEnumValue(String key, ModConfigSpec.ValueSpec metadata,
                Supplier<T> source, Consumer<T> target) {
            var current = source.get();
            if (!(current instanceof DirectPreparationPolicy.Mode || current instanceof CraftRequest.PartialPolicy
                    || current instanceof CraftableServerConfig.SurplusDelivery)) return super.createEnumValue(key, metadata, source, target);
            var value = ConfigSettings.values(context.modSpec()).stream().filter(candidate -> candidate.getSpec() == metadata)
                    .findFirst().orElseThrow();
            var candidates = NativeConfigOption.candidates(current, metadata);
            var option = new OptionInstance<>(
                    getTranslationKey(key), getTooltip(key, null), (caption, mode) -> NativeConfigOption.display(value, mode),
                    new Custom<>(candidates), current, next -> {
                        if (!editableValue()) return;
                        undoManager.add(mode -> { if (editableValue()) { target.accept(mode); onChanged(key); } }, next,
                                mode -> { if (editableValue()) { target.accept(mode); onChanged(key); } }, source.get());
                    });
            var control = option.createButton(options); control.active = editableValue();
            if (context.modSpec() == CraftableServerConfig.SPEC) policyControls.add(control);
            return new Element(getTranslationComponent(key), getTooltipComponent(key, null), control);
        }
    }

    private static boolean customPreparationMode() {
        return CraftableServerConfig.directPreparationValues().getFirst().getRaw() == DirectPreparationPolicy.Mode.CUSTOM;
    }

    private static boolean worldEditable() {
        // 中文：不是“拥有OP”即允许本地写SERVER配置；必须是已加载规则的未公开当前单人世界。
        // English: OP status does not permit local SERVER-config edits; this must be the current unpublished singleplayer world with loaded rules.
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.getConnection() != null && mc.hasSingleplayerServer()
                && !mc.getSingleplayerServer().isPublished() && CraftableServerConfig.SPEC.isLoaded();
    }

    /** 中文：专业配方 ID 列表维护局部 proposed 草稿；整表通过注册校验后才进入父页撤销缓冲。
     * 取消不提交，连接/世界/模式变化立即使编辑与回调失效。
     * English: The professional recipe-ID list owns a local proposed draft; registered whole-list validation precedes the parent's undo buffer.
     * Cancel does not commit, and changed connection/world/mode immediately disables editing and callbacks. */
    private static final class RecipeIdsScreen extends OptionsSubScreen {
        private final ModConfigSpec.ConfigValue<List<String>> value;
        private final Consumer<List<String>> commit;
        private final List<String> proposed;
        private final Object connection = Minecraft.getInstance().getConnection(), level = Minecraft.getInstance().level;
        private final List<EditBox> boxes = new ArrayList<>();
        private final List<Button> removes = new ArrayList<>();
        private Button add, defaults, done;
        RecipeIdsScreen(Screen parent, ModConfigSpec.ConfigValue<List<String>> value, Component title, Consumer<List<String>> commit) {
            super(parent, Minecraft.getInstance().options, title);
            this.value = value; this.commit = commit; proposed = new ArrayList<>(value.getRaw());
        }
        private boolean editable() { return worldEditable() && connection == minecraft.getConnection() && level == minecraft.level && customPreparationMode(); }
        @Override protected void addOptions() {
            list.setFocused(null); list.setSelected(null); list.children().clear(); boxes.clear(); removes.clear();
            for (int i = 0; i < proposed.size(); i++) {
                final int index = i;
                var box = new EditBox(font, 150, 20, Component.literal("namespace:recipe"));
                box.setMaxLength(128); box.setValue(proposed.get(i));
                box.setTooltip(Tooltip.create(Component.translatable("screen.craftable.settings.direct.recipe_id.tooltip")));
                box.setResponder(candidate -> { if (editable()) { proposed.set(index, candidate); updatePermissions(); } });
                var remove = Button.builder(Component.translatable("screen.craftable.settings.direct.remove"), button -> {
                    if (!editable()) return; proposed.remove(index); addOptions();
                }).width(150).build();
                boxes.add(box); removes.add(remove); list.addSmall(box, remove);
            }
            add = Button.builder(Component.translatable("screen.craftable.settings.direct.add"), button -> {
                if (!editable() || proposed.size() >= DirectPreparationPolicy.MAX_RECIPES) return;
                proposed.add("minecraft:"); addOptions();
            }).width(150).build();
            list.addSmall(add, null); updatePermissions();
        }
        @Override protected void addFooter() {
            var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(100).build());
            defaults = footer.addChild(Button.builder(Component.translatable("screen.craftable.settings.defaults"), button -> {
                if (!editable()) return; proposed.clear(); proposed.addAll(value.getDefault()); addOptions();
            }).width(100).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> {
                if (!editable() || !value.getSpec().test(proposed)) return;
                commit.accept(List.copyOf(proposed)); minecraft.setScreen(lastScreen);
            }).width(100).build());
            updatePermissions();
        }
        @Override public void tick() { updatePermissions(); }
        private void updatePermissions() {
            boolean permitted = minecraft != null && editable();
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setEditable(permitted);
                boxes.get(i).setTextColor(DirectPreparationPolicy.validRecipeId(proposed.get(i)) ? EditBox.DEFAULT_TEXT_COLOR : 0xFFFF0000);
            }
            for (var remove : removes) remove.active = permitted;
            if (add != null) add.active = permitted && proposed.size() < DirectPreparationPolicy.MAX_RECIPES;
            if (defaults != null) defaults.active = permitted;
            if (done != null) done.active = permitted && value.getSpec().test(proposed);
        }
        @Override public void onClose() { minecraft.setScreen(lastScreen); }
    }
}
