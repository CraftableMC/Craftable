package org.craftablemc.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import java.util.List;

/**
 * 服务端拥有的世界规则：资源扫描、工作站语义与部分合成边界；两套界面共用此注册定义。
 * Server-owned world rules for resource scans, workstations and partial-crafting boundaries; both UI presentations share these definitions.
 * 执行入口必须重新读取规则并收紧个人意图，已展示的配置/浏览快照不授予后续提交权限。
 * Execution must reread rules and restrict personal intent; previously displayed config/browsing snapshots cannot authorize a later commit.
 */
public final class CraftableServerConfig {
    public static final int DEFAULT_MAX_PLAN_STEPS = org.craftablemc.craftable.planner.SearchBudget.DEFAULT_MAX_STEPS;
    public static final int MIN_MAX_PLAN_STEPS = 1;
    public static final int MAX_MAX_PLAN_STEPS = org.craftablemc.craftable.planner.SearchBudget.HARD_MAX_STEPS;
    public static final int DEFAULT_MAX_FURNACE_BATCHES = 64;
    public static final int MIN_MAX_FURNACE_BATCHES = 1;
    public static final int MAX_MAX_FURNACE_BATCHES = 64;
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;
    // 仅缓存不可变规范值，不持有世界、玩家、菜单或配置文件句柄。
    // Cache only immutable normalized values, never worlds, players, menus or config-file handles.
    private static volatile PolicySnapshot policySnapshot;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CraftableServerConfig() {}

    /** 复制当前规则供一次操作使用；禁用部分合成必须同步收紧执行策略。 / Copy current rules for an operation; disabled partial crafting must also restrict the execution policy. */
    public static CraftingRules craftingRules() {
        var preparation = directPreparationPolicy();
        return new CraftingRules(VALUES.surplusDelivery.get(),
                VALUES.maxCraftBatch.get(), VALUES.maxPlanSteps.get(), preparation,
                VALUES.timeCompression.get(), VALUES.maxFurnaceBatches.get());
    }

    /** 原版页与专业页编辑同一注册值，不另设默认值、校验器或存储。 / Native/professional editors share registered values, defaults, validators and storage. */
    public static List<ModConfigSpec.ConfigValue<?>> directPreparationValues() {
        return List.of(VALUES.directPreparationMode, VALUES.directPreparationAllowedRecipes);
    }

    /**
     * 只有原始模式和名单都相同时才复用不可变规则；防御性复制原始名单，连原地列表编辑也会使其失效。
     * Reuse the immutable policy only while raw mode/list match; defensively snapshot the raw list so even in-place edits invalidate it.
     * 此缓存不拥有执行授权；重载后消费者仍按规则值/身份独立判断过期状态。
     * This cache owns no execution authority; after reload consumers independently invalidate by rule values/identity.
     */
    public static DirectPreparationPolicy directPreparationPolicy() {
        var mode = VALUES.directPreparationMode.get();
        var allowed = VALUES.directPreparationAllowedRecipes.get();
        var cached = policySnapshot;
        if (cached != null && cached.matches(mode, allowed)) return cached.policy();
        var policy = DirectPreparationPolicy.of(mode, allowed);
        // 连同原始输入一起发布同一不可变条目，避免缓存命中判定与实际规则来自两个不同编辑版本。
        // Publish raw inputs and policy as one immutable entry so cache matching cannot pair one edit's inputs with another edit's policy.
        policySnapshot = new PolicySnapshot(mode, List.copyOf(allowed), policy);
        return policy;
    }

    private record PolicySnapshot(DirectPreparationPolicy.Mode mode, List<String> allowed,
            DirectPreparationPolicy policy) {
        boolean matches(DirectPreparationPolicy.Mode currentMode, List<String> currentAllowed) {
            return mode == currentMode && allowed.equals(currentAllowed);
        }
    }

    /** 主产物始终必须装下；丢弃策略只适用于余料。 / Primary output must always fit; overflow dropping applies only to surplus. */
    public enum SurplusDelivery { REQUIRE_SPACE, DROP_OVERFLOW }

    /** 不可变规则边界也用于网络解码；构造时拒绝越界限制。 / Immutable rule boundary shared with network decoding; construction rejects out-of-range limits. */
    public record CraftingRules(SurplusDelivery surplusDelivery, int maxBatches, int maxPlanSteps,
            DirectPreparationPolicy directPreparation, boolean timeCompression, int maxFurnaceBatches) {
        public CraftingRules {
            java.util.Objects.requireNonNull(surplusDelivery, "surplusDelivery");
            java.util.Objects.requireNonNull(directPreparation, "directPreparation");
            if (maxBatches < 1 || maxBatches > 64) throw new IllegalArgumentException("maxBatches must be between 1 and 64");
            if (maxPlanSteps < MIN_MAX_PLAN_STEPS || maxPlanSteps > MAX_MAX_PLAN_STEPS)
                throw new IllegalArgumentException("maxPlanSteps must be between 1 and 256");
            if (maxFurnaceBatches < MIN_MAX_FURNACE_BATCHES || maxFurnaceBatches > MAX_MAX_FURNACE_BATCHES)
                throw new IllegalArgumentException("maxFurnaceBatches must be between 1 and 64");
        }

        /** 无炉调用仍使用同一有界默认规则，不引入另一配置来源。 / Furnace-free callers retain the same bounded defaults, not another config source. */
        public CraftingRules(SurplusDelivery surplusDelivery, int maxBatches, int maxPlanSteps,
                DirectPreparationPolicy directPreparation) {
            this(surplusDelivery, maxBatches, maxPlanSteps, directPreparation, true, DEFAULT_MAX_FURNACE_BATCHES);
        }
    }

    /**
     * 扫描前复制成一个值对象，扫描途中不再反复读取可变配置；不提供跨字段写入的事务锁。
     * Copy into one value object before scanning instead of rereading mutable config during the scan; this is not a cross-field write transaction lock.
     */
    public static EnvironmentScanSettings scanSettings() {
        return new EnvironmentScanSettings(
                VALUES.horizontalRadius.get(),
                VALUES.verticalRadius.get(),
                VALUES.diagnosticCacheTicks.get(),
                VALUES.includeEnderChest.get());
    }

    private static final class Values {
        private final ModConfigSpec.IntValue horizontalRadius;
        private final ModConfigSpec.IntValue verticalRadius;
        private final ModConfigSpec.IntValue diagnosticCacheTicks;
        private final ModConfigSpec.BooleanValue includeEnderChest;
        private final ModConfigSpec.EnumValue<SurplusDelivery> surplusDelivery;
        private final ModConfigSpec.IntValue maxCraftBatch;
        private final ModConfigSpec.IntValue maxPlanSteps;
        private final ModConfigSpec.BooleanValue timeCompression;
        private final ModConfigSpec.IntValue maxFurnaceBatches;
        private final ModConfigSpec.EnumValue<DirectPreparationPolicy.Mode> directPreparationMode;
        private final ModConfigSpec.ConfigValue<List<String>> directPreparationAllowedRecipes;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("environment");
            horizontalRadius = builder
                    .comment("Horizontal radius of the vanilla work-environment scan.")
                    .translation("config.craftable.server.horizontal_radius")
                    .defineInRange(
                            "horizontalRadius",
                            8,
                            EnvironmentScanSettings.MIN_HORIZONTAL_RADIUS,
                            EnvironmentScanSettings.MAX_HORIZONTAL_RADIUS);
            verticalRadius = builder
                    .comment("Vertical radius of the vanilla work-environment scan.")
                    .translation("config.craftable.server.vertical_radius")
                    .defineInRange(
                            "verticalRadius",
                            4,
                            EnvironmentScanSettings.MIN_VERTICAL_RADIUS,
                            EnvironmentScanSettings.MAX_VERTICAL_RADIUS);
            diagnosticCacheTicks = builder
                    .comment("Maximum age of the read-only environment diagnostic snapshot only.",
                            "Does not affect recipe-book/detail refresh or crafting authorization. 0 disables diagnostic caching.")
                    .translation("config.craftable.server.diagnostic_cache_ticks")
                    .defineInRange(
                            "diagnosticCacheTicks",
                            5,
                            EnvironmentScanSettings.MIN_DIAGNOSTIC_CACHE_TICKS,
                            EnvironmentScanSettings.MAX_DIAGNOSTIC_CACHE_TICKS);
            includeEnderChest = builder
                    .comment("Whether a nearby usable ender chest grants access to the player's personal ender inventory.")
                    .translation("config.craftable.server.include_ender_chest")
                    .define("includeEnderChest", true);
            builder.pop();
            builder.push("crafting");
            surplusDelivery = builder.translation("config.craftable.server.surplus_delivery")
                    .comment("Primary output must fit. DROP_OVERFLOW throws only surplus that will not fit; drops can be lost.")
                    .defineEnum("surplusDelivery", SurplusDelivery.REQUIRE_SPACE);
            maxCraftBatch = builder.translation("config.craftable.server.max_craft_batch")
                    .comment("Maximum root recipe executions per request; total intermediate steps are separately bounded.")
                    .defineInRange("maxCraftBatch", 64, 1, 64);
            maxPlanSteps = builder.translation("config.craftable.server.max_plan_steps")
                    .comment("Maximum actual recipe executions in one plan, including root and intermediate recipes.",
                            "This does not increase search time, memory or packet limits; unresolved plans remain unknown.")
                    .defineInRange("maxPlanSteps", DEFAULT_MAX_PLAN_STEPS, MIN_MAX_PLAN_STEPS, MAX_MAX_PLAN_STEPS);
            timeCompression = builder.translation("config.craftable.server.time_compression")
                    .comment("Deliver furnace and campfire processing immediately when enabled.",
                            "When disabled, submission takes the inputs up front and the workstation processes while its chunk runs.")
                    .define("timeCompression", true);
            maxFurnaceBatches = builder.translation("config.craftable.server.max_furnace_batches")
                    .comment("Maximum furnace and campfire recipe executions in one plan, including intermediate processing.",
                            "This does not enlarge the shared search, step, memory or network limits.")
                    .defineInRange("maxFurnaceBatches", DEFAULT_MAX_FURNACE_BATCHES,
                            MIN_MAX_FURNACE_BATCHES, MAX_MAX_FURNACE_BATCHES);
            directPreparationMode = builder.translation("config.craftable.server.direct_preparation_mode")
                    .comment("Partial-crafting policy for explicit partial requests only; a single C never consumes a partial plan.",
                            "CUSTOM uses only the no-confirmation recipe-ID whitelist; ALL_SUPPORTED skips additional partial confirmation; REQUIRE_CONFIRMATION always asks.",
                            "DISABLED prohibits partial crafting without affecting complete recipes. Switching mode preserves the whitelist.",
                            "Personal preferences may only tighten these rules; no mode permits unsupported recipes or bypasses validation.")
                    .defineEnum("directPreparationMode", DirectPreparationPolicy.Mode.CUSTOM);
            // NeoForge逐元素校验不能在ValueSpec.test内限制总数；整体有界谓词使文件纠正、UI与规则包保持一致。
            // NeoForge element validation alone cannot bound total size in ValueSpec.test; the whole-list predicate aligns TOML correction, UI and packets.
            directPreparationAllowedRecipes = builder.translation("config.craftable.server.direct_preparation_allowed_recipes")
                    .comment("Full recipe IDs exempt from additional partial confirmation in CUSTOM mode; every absent ID requires confirmation.",
                            "At most 128 IDs, 128 characters each. An empty list requires confirmation for every recipe.",
                            "Defaults name ordinary wood/storage conversions. Datapack replacements with the same IDs also inherit membership.",
                            "Unknown valid IDs are retained but cannot add execution support.")
                    .define(List.of("directPreparationAllowedRecipes"), () -> DirectPreparationPolicy.DEFAULT.allowedRecipes(),
                            DirectPreparationPolicy::validRecipeList, List.class);
            builder.pop();
        }
    }
}
