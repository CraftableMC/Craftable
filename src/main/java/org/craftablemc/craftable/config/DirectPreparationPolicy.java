package org.craftablemc.craftable.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.planner.CraftRequest;

/**
 * 服务端拥有的不可变部分合成策略，与静态配方知识及可执行性分离；仅决定禁止或额外确认。
 * Immutable server-owned partial-crafting policy, separate from recipe knowledge/execution support; it only decides prohibition or extra confirmation.
 * 它不能授权配方或材料，也不绕过世界权限、材料新鲜度、容量或预算复验。
 * It never authorizes recipes/inputs or bypasses world permissions, input freshness, capacity or budget validation.
 */
public final class DirectPreparationPolicy {
    public static final int MAX_RECIPES = 128;
    public static final int MAX_RECIPE_ID_LENGTH = 128;
    // 界面模式显式映射到请求策略；严格度由策略规则决定，不能由枚举声明顺序推导。
    // UI modes map explicitly to request policies; policy rules define strictness, never enum declaration order.
    public enum Mode {
        CUSTOM, ALL_SUPPORTED, REQUIRE_CONFIRMATION, DISABLED;

        /** 共用UI模式映射为有界请求偏好，不引入个人名单。 / Map shared UI modes to bounded request preferences without a personal list. */
        public CraftRequest.PartialPolicy requestPolicy() {
            return switch (this) {
                case DISABLED -> CraftRequest.PartialPolicy.NEVER;
                case REQUIRE_CONFIRMATION -> CraftRequest.PartialPolicy.CONFIRM;
                case CUSTOM -> CraftRequest.PartialPolicy.WHITELIST;
                case ALL_SUPPORTED -> CraftRequest.PartialPolicy.EXPLICIT_SAFE;
            };
        }

        public static Mode fromRequestPolicy(CraftRequest.PartialPolicy policy) {
            return switch (Objects.requireNonNull(policy, "policy")) {
                case NEVER -> DISABLED;
                case CONFIRM -> REQUIRE_CONFIRMATION;
                case WHITELIST -> CUSTOM;
                case EXPLICIT_SAFE -> ALL_SUPPORTED;
            };
        }
    }
    /**
     * 普通1.21.1转换的显式可编辑默认名单；CUSTOM唯一按名单判定免确认，不存在隐藏配方形状回退。
     * Explicit editable defaults for ordinary 1.21.1 conversions; CUSTOM consent depends only on membership, with no hidden recipe-shape fallback.
     */
    public static final DirectPreparationPolicy DEFAULT = of(Mode.CUSTOM, List.of(
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:crimson_planks",
            "minecraft:warped_planks", "minecraft:stick", "minecraft:stick_from_bamboo_item",
            "minecraft:iron_nugget", "minecraft:iron_ingot_from_nuggets",
            "minecraft:gold_nugget", "minecraft:gold_ingot_from_nuggets",
            "minecraft:iron_block", "minecraft:iron_ingot_from_iron_block",
            "minecraft:gold_block", "minecraft:gold_ingot_from_gold_block",
            "minecraft:diamond_block", "minecraft:diamond", "minecraft:emerald_block", "minecraft:emerald",
            "minecraft:redstone_block", "minecraft:redstone", "minecraft:lapis_block", "minecraft:lapis_lazuli",
            "minecraft:coal_block", "minecraft:coal", "minecraft:copper_block", "minecraft:copper_ingot"));

    private final Mode mode;
    private final List<String> allowedRecipes;
    private final Set<ResourceLocation> allowed;

    private DirectPreparationPolicy(Mode mode, List<String> allowedRecipes) {
        this.mode = mode;
        this.allowedRecipes = allowedRecipes;
        this.allowed = locations(allowedRecipes);
    }

    /** 校验并规范排序/去重，快照不再持有调用者的可变列表。 / Validate, sort and deduplicate without retaining the caller's mutable list. */
    public static DirectPreparationPolicy of(Mode mode, List<String> allowedRecipes) {
        return new DirectPreparationPolicy(Objects.requireNonNull(mode, "mode"),
                normalize(allowedRecipes));
    }

    public Mode mode() { return mode; }
    public List<String> allowedRecipes() { return allowedRecipes; }

    /**
     * 仅投影是否允许提出部分方案；确认要求仍由同一冻结策略逐步骤判断，不另存世界开关。
     * Project only whether partial plans may be proposed; per-step consent still uses this frozen policy, without another world toggle.
     */
    public CraftRequest.PartialPolicy executionPolicy() {
        return mode == Mode.DISABLED ? CraftRequest.PartialPolicy.NEVER : CraftRequest.PartialPolicy.EXPLICIT_SAFE;
    }

    /**
     * 个人偏好只能收紧部分合成/确认门；始终使用此不可变世界名单，不创建个人名单。
     * Personal preference can only tighten the partial/confirmation gate; membership always uses this immutable world whitelist, never a personal list.
     */
    public DirectPreparationPolicy restrict(CraftRequest.PartialPolicy preference) {
        var restricted = Mode.fromRequestPolicy(mode.requestPolicy().restrict(preference));
        return restricted == mode ? this : new DirectPreparationPolicy(restricted, allowedRecipes);
    }

    /**
     * 只能在可执行性与权威校验成功后调用；合法但未知的ID只保留配置，不产生新配方支持。
     * Call only after support and authoritative validation succeed; well-formed unknown IDs remain dormant configuration, not new recipe support.
     */
    public boolean allowsWithoutConfirmation(ResourceLocation recipe) {
        Objects.requireNonNull(recipe, "recipe");
        return switch (mode) {
            case ALL_SUPPORTED -> true;
            case REQUIRE_CONFIRMATION, DISABLED -> false;
            case CUSTOM -> allowed.contains(recipe);
        };
    }

    /** 注册元数据与两套界面共用有界名单校验。 / Registered metadata and both presentations share bounded list validation. */
    public static boolean validRecipeList(Object value) {
        if (!(value instanceof List<?> recipes) || recipes.size() > MAX_RECIPES) return false;
        for (var recipe : recipes) if (!validRecipeId(recipe)) return false;
        return true;
    }

    public static boolean validRecipeId(Object value) {
        if (!(value instanceof String text) || text.length() > MAX_RECIPE_ID_LENGTH) return false;
        String id = text.trim();
        int separator = id.indexOf(':');
        // 缺失命名空间不能静默解释为minecraft。
        // Never silently interpret an omitted namespace as minecraft.
        return separator > 0 && separator < id.length() - 1 && ResourceLocation.tryParse(id) != null;
    }

    private static List<String> normalize(List<String> recipes) {
        if (!validRecipeList(recipes)) throw new IllegalArgumentException("Expected at most 128 full recipe IDs of at most 128 characters");
        var sorted = new TreeSet<String>();
        for (String recipe : recipes) sorted.add(ResourceLocation.parse(recipe.trim()).toString());
        return List.copyOf(sorted);
    }

    private static Set<ResourceLocation> locations(List<String> recipes) {
        var locations = new java.util.HashSet<ResourceLocation>();
        for (String recipe : recipes) locations.add(ResourceLocation.parse(recipe));
        return Set.copyOf(locations);
    }

    @Override public boolean equals(Object other) {
        return other instanceof DirectPreparationPolicy policy && mode == policy.mode
                && allowedRecipes.equals(policy.allowedRecipes);
    }

    @Override public int hashCode() { return Objects.hash(mode, allowedRecipes); }
    @Override public String toString() { return "DirectPreparationPolicy[" + mode + ", allowed=" + allowedRecipes + "]"; }
}
