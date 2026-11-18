package org.craftablemc.craftable.client;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.CraftableServerConfig;


/** 中文：公共控件无法观察草稿/旧回调时才使用的窄私有 UI 适配，集中反射边界而不替代玩家交互或授予权限。
 * English: Narrow private-UI adaptation where public widgets cannot observe drafts/stale callbacks, confining reflection without replacing player interaction or granting authority. */
final class SettingsUiAccess {
    @SuppressWarnings("unchecked")
    static List<NativeConfigOption<?>> configOptions(Screen screen) throws Exception {
        return (List<NativeConfigOption<?>>) read(screen, UiField.CONFIG_OPTIONS);
    }

    static NativeConfigOption<?> binding(Screen screen, String pathTail) throws Exception {
        return configOptions(screen).stream().filter(binding -> binding.configValue().getPath().getLast().equals(pathTail))
                .findFirst().orElseThrow(() -> new AssertionError("Missing ordinary config control: " + pathTail));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void setOption(OptionInstance<?> option, Object candidate) { ((OptionInstance) option).set(candidate); }

    static ModConfigSpec.ConfigValue<?> directList(String tail) {
        return CraftableServerConfig.directPreparationValues().stream()
                .filter(value -> value.getPath().getLast().equals(tail)).findFirst().orElseThrow();
    }

    static void writeField(Object target, UiField name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name.key); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name.key);
    }

    static AbstractWidget nativeControl(Screen screen, String tail) throws Exception {
        return ((OptionsList) read(screen, UiField.LIST)).findOption(binding(screen, tail).option());
    }

    static AbstractWidget readOnlyNumericControl(Screen screen, String tail) throws Exception {
        var values = NativeConfigOption.definitions(CraftableServerConfig.SPEC).stream().filter(NativeConfigOption::scalar).toList();
        for (int index = 0; index < values.size(); index++)
            if (values.get(index).getPath().getLast().equals(tail)) return (AbstractWidget) ((List<?>) read(screen, UiField.RULE_ROWS)).get(index);
        throw new AssertionError("Missing read-only native numeric rule: " + tail);
    }

    static Bounds bounds(AbstractWidget widget) {
        return new Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
    }

    static ModConfig registered(ModConfig.Type type, ModConfigSpec spec) {
        return ModConfigs.getConfigSet(type).stream().filter(c -> c.getSpec() == spec).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked") static ModConfigSpec.ConfigValue<Integer> serverSteps() {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of("crafting", "maxPlanSteps"));
    }

    @SuppressWarnings("unchecked") static ModConfigSpec.ConfigValue<Integer> serverRadius() {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of("environment", "horizontalRadius"));
    }

    @SuppressWarnings("unchecked") static ModConfigSpec.ConfigValue<Integer> serverValue(String section, String name) {
        return (ModConfigSpec.ConfigValue<Integer>) CraftableServerConfig.SPEC.getValues().get(List.of(section, name));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void restoreValue(ModConfigSpec.ConfigValue<?> value, Object original) { ((ModConfigSpec.ConfigValue) value).set(original); }

    static List<AbstractWidget> widgets(GuiEventListener listener) {
        var result = new java.util.ArrayList<AbstractWidget>();
        if (listener instanceof AbstractWidget widget) result.add(widget);
        if (listener instanceof ContainerEventHandler container) container.children().forEach(child -> result.addAll(widgets(child)));
        return result;
    }

    static List<AbstractWidget> rowWidgets(OptionsList list) {
        // 中文：OptionsList 自身也是 AbstractWidget，数量检查须从真实条目开始，不能把外层列表算入内容。
        // OptionsList is itself an AbstractWidget. Content counts must start
        // at the actual native entries, not include the enclosing list.
        return ((ContainerEventHandler) list).children().stream().flatMap(row -> widgets(row).stream()).toList();
    }

    static Button button(Screen screen, String label) {
        return widgets(screen).stream().filter(widget -> widget instanceof Button && widget.getMessage().getString().equals(label))
                .map(widget -> (Button) widget).findFirst().orElseThrow(() -> new AssertionError("Missing native button: " + label
                        + "; available=" + widgets(screen).stream().filter(w -> w instanceof Button).map(w -> w.getMessage().getString()).toList()));
    }

    static void click(Screen screen, String label) {
        click(screen, button(screen, label));
    }

    static void clickSection(Screen screen, String translationKey) {
        String section = Component.translatable("neoforge.configuration.uitext.section", Component.translatable(translationKey)).getString();
        var label = widgets(screen).stream().filter(widget -> !(widget instanceof Button)
                && widget.getMessage().getString().equals(section)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native section label: " + section));
        var button = widgets(screen).stream().filter(widget -> widget instanceof Button && widget.getY() == label.getY())
                .map(widget -> (Button) widget).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native editor on section row: " + section));
        click(screen, button);
    }

    static void clickLabeledValue(Screen screen, String translationKey) {
        var control = professionalControl(screen, translationKey);
        require(control.active, "Professional control is disabled: " + translationKey);
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        require(screen.mouseClicked(control.getX() + 5, control.getY() + 5, 0),
                "Professional option ignored an actual click: " + translationKey);
        screen.mouseReleased(control.getX() + 5, control.getY() + 5, 0);
    }

    static AbstractWidget professionalControl(Screen screen, String translationKey) {
        String caption = Component.translatable(translationKey).getString();
        var label = professionalLabel(screen, translationKey);
        return widgets(screen).stream().filter(widget -> widget != label
                && widget.getY() == label.getY() && widget.getX() > label.getX()).findFirst()
                .orElseThrow(() -> new AssertionError("Missing professional control on row: " + caption));
    }

    static AbstractWidget professionalLabel(Screen screen, String translationKey) {
        String caption = Component.translatable(translationKey).getString();
        return widgets(screen).stream().filter(widget -> !(widget instanceof Button)
                && widget.getMessage().getString().equals(caption)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing professional option label: " + caption));
    }

    static void click(Screen screen, Button button) {
        String label = button.getMessage().getString();
        require(button.active, "Native settings button is disabled: " + label);
        Minecraft.getInstance().setLastInputType(InputType.MOUSE);
        require(screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0), "Native settings click was ignored: " + label);
        screen.mouseReleased(button.getX() + 5, button.getY() + 5, 0);
    }

    static String inGameName() { return Component.translatable("mod.craftable.name").getString(); }

    static String translated(String key) { return Component.translatable("screen.craftable.settings." + key).getString(); }

    static Object read(Object target, UiField name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name.key); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name.key);
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    record Bounds(int x, int y, int width, int height) {}
    /** 中文：私有检查集中于此，因为草稿、待定回复及旧回调没有公开观察 API；适配器从不授予权限。
     * English: Private inspection is confined here: drafts, pending replies and stale
     * callbacks have no public observation API. The adapter never grants authority. */
    enum UiField {
        LIST("list"),
        WORLD_ENTRY("worldEntry"),
        RULES("rules"),
        PAGE("page"),
        PENDING("pending"),
        CRAFTING("crafting"),
        SCAN("scan"),
        DRAFT("draft"),
        RULE_ROWS("ruleRows"),
        RETRY("retry"),
        HEADING("heading"),
        CATALOG("catalog"),
        RECIPES("recipes"),
        SEARCH("search"),
        CHOICES("choices"),
        ALLOWED_VALUE("allowedValue"),
        ALLOWED("allowed"),
        DEFERRED_TOOLTIP_RENDERING("deferredTooltipRendering"),
        TOOLTIP("tooltip"),
        POSITIONER("positioner"),
        CHOICE_IDS("choiceIds"),
        STATUS("status"),
        MATCHING_RECIPES("matchingRecipes"),
        MESSAGE("message"),
        DIRECT_PREPARATION_ENTRY("directPreparationEntry"),
        STATE("state"),
        OBSERVED_RECIPES("observedRecipes"),
        ADD("add"),
        BOXES("boxes"),
        PROPOSED("proposed"),
        REMOVES("removes"),
        CONNECTION("connection"),
        LAST_SCREEN("lastScreen"),
        CHANGED("changed"),
        UNDO_MANAGER("undoManager"),
        ADVANCED("advanced"),
        XPOS("xpos"),
        YPOS("ypos"),
        CONFIG_OPTIONS("configOptions"),
        REVISION("revision"),
        OBSERVED_POLICY("observedPolicy"),
        VALUE("value"),
        DEFAULTS("defaults"),
        DONE("done");
        final String key;
        UiField(String key) { this.key = key; }
    }
    enum UiMethod { REQUEST("request"), UPDATE_CATALOG_STATE("updateCatalogState"),
        CLEAR_UNAVAILABLE_RECIPES("clearUnavailableRecipes"), REBUILD_ROWS("rebuildRows"), REFRESH_RECIPES("refreshRecipes");
        final String key; UiMethod(String key) { this.key = key; }
    }
    static java.lang.reflect.Method method(Object target, UiMethod name, Class<?>... types) throws Exception {
        var method = target.getClass().getDeclaredMethod(name.key, types); method.setAccessible(true); return method;
    }
    static void invokePublic(Object target, String name) throws Exception {
        target.getClass().getMethod(name).invoke(target);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Screen readOnlyWorld(Screen parent) throws Exception {
        // 中文：构造远端客户端真实使用的只读视图；此集成夹具不声称覆盖真实 LAN 权限。
        // Construct the actual read-only presentation used by remote clients;
        // this integrated fixture does not claim real LAN permission coverage.
        var rules = read(parent, UiField.RULES);
        var pageType = read(parent, UiField.PAGE).getClass();
        var page = Enum.valueOf((Class<? extends Enum>) pageType, "WORLD");
        var constructor = java.util.Arrays.stream(CraftableSettingsScreen.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 4 && c.getParameterTypes()[1] == pageType).findFirst().orElseThrow();
        constructor.setAccessible(true);
        return (Screen) constructor.newInstance(parent, page, rules, null);
    }
    @FunctionalInterface interface CheckedAction { void run() throws Exception; }

}
