package org.craftablemc.craftable.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.recipe.CraftingRecipes;
import org.craftablemc.craftable.recipe.PlanningInput;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/** 中文：客户端线程上的有界免确认配方白名单视图，局部 allowed 列表归此页拥有。
 * 完成只更新父页世界草稿，不保存配置或发规则修改；取消丢弃局部列表，移除页面释放目录。
 * English: A bounded client-thread view of the registered no-confirmation whitelist, owning its local allowed list.
 * Done updates only the parent's world draft, never saves a spec or sends a rule edit; Cancel discards the list and removal releases the catalog. */
final class DirectPreparationSettingsScreen extends OptionsSubScreen {
    private static final int MAX_DISPLAYED_RECIPES = 256;
    private final CraftableSettingsScreen parent;
    private final ConfigSettings.Draft draft;
    private final BooleanSupplier canEdit;
    private final Supplier<DirectPreparationPolicy> remotePolicy;
    private final ModConfigSpec.ConfigValue<List<String>> allowedValue;
    private final List<String> allowed = new ArrayList<>();
    private final List<Button> choices = new ArrayList<>();
    private final Map<Button, ResourceLocation> choiceIds = new LinkedHashMap<>();
    private final Map<ResourceLocation, CraftingRecipes.Entry> recipes = new LinkedHashMap<>();
    private final PlanningInput.Catalog catalog = new PlanningInput.Catalog();
    private PlanningInput.Catalog.State observedCatalogState = PlanningInput.Catalog.State.EMPTY;
    private List<CraftingRecipes.Entry> observedRecipes = List.of();
    private DirectPreparationPolicy observedPolicy;
    private DirectPreparationPolicy displayedPolicy = DirectPreparationPolicy.DEFAULT;
    private EditBox search;
    private Button done, defaults;
    private StringWidget status;
    private String searchText = "";
    private int matchingRecipes;

    @SuppressWarnings("unchecked")
    DirectPreparationSettingsScreen(CraftableSettingsScreen parent, ConfigSettings.Draft draft,
            BooleanSupplier canEdit, Supplier<DirectPreparationPolicy> remotePolicy) {
        super(parent, Minecraft.getInstance().options, text("title"));
        this.parent = parent; this.draft = draft; this.canEdit = canEdit; this.remotePolicy = remotePolicy;
        var values = CraftableServerConfig.directPreparationValues();
        allowedValue = (ModConfigSpec.ConfigValue<List<String>>) values.stream()
                .filter(v -> v.getPath().getLast().equals("directPreparationAllowedRecipes")).findFirst().orElseThrow();
        if (draft != null) {
            var policy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, draft.get(allowedValue));
            allowed.addAll(policy.allowedRecipes());
        }
        else readRemote();
    }

    CraftableSettingsScreen settingsParent() { return parent; }

    @Override protected void addTitle() {
        layout.setHeaderHeight(58);
        var header = layout.addToHeader(LinearLayout.vertical().spacing(5));
        header.addChild(new StringWidget(310, 9, title, font));
        search = header.addChild(new EditBox(font, 310, 20, text("search")));
        search.setMaxLength(128); search.setHint(text("search")); search.setValue(searchText);
        search.setResponder(value -> { searchText = value; rebuildRows(); });
    }

    @Override protected void addOptions() {
        status = new StringWidget(310, 20, Component.empty(), font).alignLeft();
        refreshRecipes(); rebuildRows();
    }

    @Override protected void addFooter() {
        var footer = layout.addToFooter(LinearLayout.horizontal().spacing(5));
        if (draft != null) {
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(100).build());
            defaults = footer.addChild(Button.builder(Component.translatable("screen.craftable.settings.defaults"), button -> {
                if (!editable()) return;
                allowed.clear(); allowed.addAll(allowedValue.getDefault()); rebuildRows();
            }).width(100).tooltip(Tooltip.create(text("defaults.tooltip"))).build());
            done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> accept()).width(100).build());
        } else done = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        updatePermissions();
    }

    private boolean editable() {
        return draft != null && canEdit.getAsBoolean() && parent.customDirectPreparation();
    }

    @Override public void tick() {
        parent.tick();
        boolean changed = refreshRecipes();
        if (draft == null) changed |= readRemote();
        if (changed) rebuildRows();
        updatePermissions();
    }

    /** 中文：按连接和已同步配方身份有界更新显示目录；目录未就绪不代表白名单 ID 无效或允许删除它们。
     * English: Update the display catalog within bounds for the connection/synchronized recipe identity; an unready catalog does not invalidate configured IDs. */
    private boolean refreshRecipes() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) {
            catalog.clear();
            return clearUnavailableRecipes(updateCatalogState());
        }
        var synchronizedRecipes = mc.level.getRecipeManager().getRecipes();
        catalog.observe(mc.getConnection(), synchronizedRecipes, synchronizedRecipes, mc.level.registryAccess());
        catalog.advance(1_000_000L);
        boolean changed = updateCatalogState();
        if (catalog.state() != PlanningInput.Catalog.State.READY) return clearUnavailableRecipes(changed);
        var current = catalog.bind(false, false, java.util.Set.of(), DirectPreparationPolicy.DEFAULT).entries();
        if (observedRecipes == current || observedRecipes.equals(current)) return changed;
        observedRecipes = current; recipes.clear();
        for (var entry : current) recipes.put(entry.id(), entry);
        return true;
    }

    private boolean updateCatalogState() {
        var current = catalog.state();
        boolean changed = current != observedCatalogState;
        observedCatalogState = current;
        return changed;
    }

    private boolean clearUnavailableRecipes(boolean changed) {
        changed |= !recipes.isEmpty() || !observedRecipes.isEmpty();
        recipes.clear(); observedRecipes = List.of();
        // 中文：目录未就绪不能证明配置 ID 对应的配方不存在；保留草稿，也不闪现原始的“不可用”条目。
        // Configured IDs are not recipe absence proofs. Preserve the draft,
        // but do not flash raw "unavailable" entries while the catalog loads.
        return changed;
    }

    private boolean readRemote() {
        var policy = remotePolicy.get();
        if (java.util.Objects.equals(observedPolicy, policy)) return false;
        observedPolicy = policy; allowed.clear();
        if (policy != null) allowed.addAll(policy.allowedRecipes());
        return true;
    }

    private void rebuildRows() {
        if (list == null || status == null) return;
        list.setFocused(null); list.setSelected(null); list.children().clear(); choices.clear(); choiceIds.clear();
        displayedPolicy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, allowed);
        list.addSmall(status, null);
        if (catalog.state() != PlanningInput.Catalog.State.READY) {
            matchingRecipes = 0;
            finishRows();
            return;
        }
        var ids = new java.util.LinkedHashSet<ResourceLocation>(recipes.keySet());
        for (var id : allowed) ids.add(ResourceLocation.parse(id));
        String query = searchText.toLowerCase(Locale.ROOT).strip();
        var sorted = ids.stream().filter(id -> matches(id, query))
                .sorted(Comparator.comparing(this::itemSortKey).thenComparing(ResourceLocation::toString)).toList();
        matchingRecipes = sorted.size();
        for (var id : sorted.subList(0, Math.min(MAX_DISPLAYED_RECIPES, sorted.size()))) {
            var entry = recipes.get(id);
            Component item = entry != null ? entry.output().getHoverName()
                    : text("unsupported");
            Component full = Component.empty().append(item).append("\n").append(id.toString()).append("\n")
                    .append(entry != null ? text("method", entry.gridSize(), entry.gridSize())
                            : text("unsupported.tooltip"));
            if (entry != null) full = full.copy().append("\n").append(text("result", entry.output().getCount()))
                    .append("\n").append(text("inputs", inputSummary(entry)));
            var label = new StringWidget(150, 20, clipped(item), font).alignLeft();
            label.setTooltip(Tooltip.create(full));
            var button = Button.builder(choiceText(id), control -> cycle(id, control)).width(150)
                    .tooltip(Tooltip.create(full.copy().append("\n").append(text("choices.tooltip")))).build();
            choices.add(button); choiceIds.put(button, id); list.addSmall(label, button);
        }
        finishRows();
    }

    private void finishRows() {
        updateStatus();
        status.setTooltip(Tooltip.create(text("help")));
        list.setScrollAmount(0); updatePermissions();
    }

    private Component catalogStatus() {
        return text(catalog.state() == PlanningInput.Catalog.State.BUILDING ? "catalog_loading" : "catalog_unavailable");
    }

    private void updateStatus() {
        status.setMessage(catalog.state() != PlanningInput.Catalog.State.READY ? catalogStatus()
                : matchingRecipes > MAX_DISPLAYED_RECIPES ? text("filtered_count", MAX_DISPLAYED_RECIPES, matchingRecipes)
                : text("count", matchingRecipes));
    }

    private String itemSortKey(ResourceLocation id) {
        var entry = recipes.get(id);
        return entry == null ? "~" + id : BuiltInRegistries.ITEM.getKey(entry.output().getItem()).toString();
    }

    static String inputSummary(CraftingRecipes.Entry entry) {
        var descriptions = new ArrayList<String>();
        for (var group : inputGroups(entry.requirements())) {
            var options = group.options();
            var names = new ArrayList<String>(Math.min(4, options.size()));
            for (int i = 0; i < Math.min(4, options.size()); i++) names.add(options.get(i).getHoverName().getString());
            String display = String.join(" / ", names);
            if (options.size() > 4) display += " / …";
            descriptions.add(group.count() + " × (" + display + ")");
        }
        return String.join("; ", descriptions);
    }

    /** 中文：按完整候选物品/组件集合合并说明，不按 Ingredient 对象或截断文字合并。
     * N 个相同 OR 槽仍是 N 次独立选择；此显示聚合不参与配料分配。
     * English: Group explanations by complete item/component candidate sets, not Ingredient identity or clipped labels.
     * N equal OR slots remain N independent choices; this display grouping never allocates ingredients. */
    static List<InputGroup> inputGroups(List<CraftingRecipes.Requirement> requirements) {
        var groups = new LinkedHashMap<Set<InputOption>, InputGroup>();
        for (var requirement : requirements) {
            var options = new LinkedHashMap<InputOption, ItemStack>();
            for (var option : requirement.ingredient().getItems()) {
                if (!option.isEmpty()) options.putIfAbsent(InputOption.of(option), option.copyWithCount(1));
            }
            if (options.isEmpty()) continue;
            var key = Set.copyOf(options.keySet());
            var previous = groups.get(key);
            if (previous == null) {
                var ordered = options.values().stream().sorted(Comparator.comparing(
                        stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())).toList();
                groups.put(key, new InputGroup(ordered, 1));
            } else groups.put(key, new InputGroup(previous.options(), previous.count() + 1));
        }
        return List.copyOf(groups.values());
    }

    record InputGroup(List<ItemStack> options, int count) {}

    private record InputOption(Item item, Map<DataComponentType<?>, Object> components) {
        static InputOption of(ItemStack stack) {
            var components = new java.util.HashMap<DataComponentType<?>, Object>();
            stack.getComponents().stream().forEach(component -> components.put(component.type(), component.value()));
            return new InputOption(stack.getItem(), Map.copyOf(components));
        }
    }

    @Override public void setTooltipForNextRenderPass(List<FormattedCharSequence> tooltip,
            ClientTooltipPositioner positioner, boolean override) {
        // 中文：原版可能把过高提示放到上方；只裁剪可视行并约束边界，Tooltip 保留完整旁白。
        // Vanilla menu positioning can choose "above" even when a tall tooltip
        // cannot fit there. Limit only visual lines (the full narration remains
        // on Tooltip) and keep the final box inside the current GUI viewport.
        super.setTooltipForNextRenderPass(boundedTooltipLines(tooltip, height),
                (w, h, x, y, tw, th) -> boundedTooltipPosition(
                        positioner.positionTooltip(w, h, x, y, tw, th), w, h, tw, th), override);
    }

    static int tooltipLineLimit(int screenHeight) {
        // 中文：文本提示的边界预算包含每行 10px、首行间隔及 4px 边框。
        // Text tooltips use 10 px per line, a first-line gap, and a 4 px border.
        return Math.max(1, (screenHeight - 14) / 10);
    }

    static List<FormattedCharSequence> boundedTooltipLines(List<FormattedCharSequence> lines, int screenHeight) {
        int limit = tooltipLineLimit(screenHeight);
        if (lines.size() <= limit) return lines;
        var visible = new ArrayList<FormattedCharSequence>(limit);
        // 中文：截断时保留开头身份与结尾操作提示，避免只剩材料正文。
        // Preserve the identity at the beginning and action hints at the end.
        int tail = limit >= 6 ? 3 : 0;
        visible.addAll(lines.subList(0, limit - tail - 1));
        visible.add(Component.literal("…").getVisualOrderText());
        if (tail > 0) visible.addAll(lines.subList(lines.size() - tail, lines.size()));
        return List.copyOf(visible);
    }

    static Vector2ic boundedTooltipPosition(Vector2ic desired, int width, int height, int tooltipWidth, int tooltipHeight) {
        return new Vector2i(Math.max(4, Math.min(desired.x(), width - tooltipWidth - 4)),
                Math.max(4, Math.min(desired.y(), height - tooltipHeight - 6)));
    }

    private boolean matches(ResourceLocation id, String query) {
        var entry = recipes.get(id);
        return query.isEmpty() || id.toString().contains(query) || entry != null
                && (BuiltInRegistries.ITEM.getKey(entry.output().getItem()).toString().contains(query)
                || entry.output().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query));
    }

    private Component clipped(Component value) {
        if (font.width(value) <= 146) return value;
        return Component.literal(font.plainSubstrByWidth(value.getString(), 136) + "…");
    }

    private boolean knownRecipe(ResourceLocation id) {
        return catalog.state() == PlanningInput.Catalog.State.READY && recipes.containsKey(id);
    }

    private Component choiceText(ResourceLocation id) {
        if (!knownRecipe(id)) return text("unavailable_choice");
        return text(displayedPolicy.allowsWithoutConfirmation(id) ? "allow" : "confirm");
    }

    private void cycle(ResourceLocation id, Button control) {
        if (!editable() || !knownRecipe(id)) return;
        boolean nextAllowedChoice = !displayedPolicy.allowsWithoutConfirmation(id);
        var nextAllowed = new ArrayList<>(allowed);
        nextAllowed.remove(id.toString());
        // 中文：浏览不改白名单；切换只增删当前精确配方，与专业页操作同一注册列表。
        // Browsing does not change the whitelist. A toggle adds or removes
        // this exact recipe from the same registered list as the professional editor.
        if (nextAllowedChoice) nextAllowed.add(id.toString());
        if (!validList(nextAllowed)) return;
        allowed.clear(); allowed.addAll(nextAllowed);
        displayedPolicy = DirectPreparationPolicy.of(DirectPreparationPolicy.Mode.CUSTOM, allowed);
        control.setMessage(choiceText(id));
        updateStatus();
    }

    private boolean validList(List<String> nextAllowed) {
        if (allowedValue.getSpec().test(nextAllowed)) return true;
        status.setMessage(text("limit")); return false;
    }

    private void accept() {
        // 中文：整表校验前后都检查编辑资格；父页可能在校验期间已不再属于可编辑世界或白名单模式。
        // English: Check edit eligibility before and after whole-list validation; the parent may no longer own an editable world/whitelist mode.
        if (!editable()) { updatePermissions(); return; }
        if (!validList(allowed)) return;
        // 中文：更新草稿中的注册列表身份前，先校验整张名单。
        // Validate the entire list before updating its registered identity in the draft.
        if (!editable()) { updatePermissions(); return; }
        draft.set(allowedValue, List.copyOf(allowed));
        minecraft.setScreen(parent);
    }

    private void updatePermissions() {
        boolean allowedNow = editable();
        for (var button : choices) button.active = allowedNow && knownRecipe(choiceIds.get(button));
        if (defaults != null) defaults.active = allowedNow;
        if (done != null && draft != null) {
            done.active = allowedNow;
            done.setTooltip(allowedNow ? null : Tooltip.create(Component.translatable("screen.craftable.settings."
                    + (parent.customDirectPreparation() ? "world_changed" : "direct.custom_only.tooltip"))));
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() { catalog.clear(); super.removed(); }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.craftable.settings.direct." + key, args);
    }
}
