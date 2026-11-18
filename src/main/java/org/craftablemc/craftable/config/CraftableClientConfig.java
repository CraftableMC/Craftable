package org.craftablemc.craftable.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 客户端显示与请求偏好，由客户端配置生命周期拥有；只发送限制意图，不能修改或放宽世界规则。
 * Client presentation/request preferences owned by the client-config lifecycle; they send restrictive intent, never modify or relax world rules.
 * 部分合成名单仅归世界所有；个人白名单模式引用同一世界名单而不另建个人名单。
 * Partial-crafting membership belongs only to the world; personal whitelist mode uses that same list instead of a personal list.
 */
public final class CraftableClientConfig {
    public static final int MIN_DOUBLE_PRESS_MILLIS = 150;
    public static final int MAX_DOUBLE_PRESS_MILLIS = 800;
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        Pair<Values, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CraftableClientConfig() {}

    public static boolean recipeBookEnhancementsEnabled() {
        return VALUES.recipeBookEnhancements.get();
    }

    public static boolean detailedFailureFeedbackEnabled() {
        return VALUES.detailedFailureFeedback.get();
    }

    public static boolean unlockedOnly() { return VALUES.unlockedOnly.get(); }

    /** 两种界面共用四态定义，再映射为请求可收紧的策略。 / Both presentations share four modes, mapped to a restrictable request policy. */
    public static DirectPreparationPolicy.Mode partialMode() { return VALUES.partialPolicy.get(); }
    public static org.craftablemc.craftable.planner.CraftRequest.PartialPolicy partialPolicy() { return partialMode().requestPolicy(); }
    public static boolean allowSurplusDrops() { return VALUES.allowSurplusDrops.get(); }
    public static int doublePressMillis() { return VALUES.doublePressMillis.get(); }

    private static final class Values {
        private final ModConfigSpec.BooleanValue recipeBookEnhancements;
        private final ModConfigSpec.BooleanValue detailedFailureFeedback;
        private final ModConfigSpec.BooleanValue unlockedOnly;
        private final ModConfigSpec.EnumValue<DirectPreparationPolicy.Mode> partialPolicy;
        private final ModConfigSpec.BooleanValue allowSurplusDrops;
        private final ModConfigSpec.IntValue doublePressMillis;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("presentation");
            unlockedOnly = builder.translation("config.craftable.client.unlocked_only")
                    .comment("Only show recipes unlocked by the player; does not change progression.")
                    .define("unlockedOnly", false);
            recipeBookEnhancements = builder
                    .comment("Enable Craftable's current vanilla recipe-book entry points.")
                    .translation("config.craftable.client.recipe_book_enhancements")
                    .define("recipeBookEnhancements", true);
            detailedFailureFeedback = builder
                    .comment("Show the specific server result when a create request fails.")
                    .translation("config.craftable.client.detailed_failure_feedback")
                    .define("detailedFailureFeedback", true);
            builder.pop();
            builder.push("crafting");
            partialPolicy = builder.translation("config.craftable.client.partial_policy")
                    .comment("Personal partial-crafting preference; it may only tighten the world's rules.",
                            "DISABLED prohibits partial work; REQUIRE_CONFIRMATION always asks; CUSTOM uses the world's recipe whitelist; ALL_SUPPORTED adds no personal confirmation restriction.",
                            "There is no personal recipe list. The default follows the world's partial-crafting rules.")
                    .defineEnum("partialPolicy", DirectPreparationPolicy.Mode.ALL_SUPPORTED);
            allowSurplusDrops = builder.translation("config.craftable.client.allow_surplus_drops")
                    .comment("Follow world surplus rules. False additionally requires all outputs to fit.")
                    .define("allowSurplusDrops", true);
            doublePressMillis = builder.translation("config.craftable.client.double_press_millis")
                    .defineInRange("doublePressMillis", 350, MIN_DOUBLE_PRESS_MILLIS, MAX_DOUBLE_PRESS_MILLIS);
            builder.pop();
        }
    }
}
