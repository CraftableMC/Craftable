package org.craftablemc.craftable.planner;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/** 有界玩家意图；规范化后的选择不可变，绝不是客户端提供的背包操作列表。
 * Bounded player intent; normalized selections are immutable, never client-supplied inventory operations. */
public record CraftRequest(ResourceLocation recipe, int batches, boolean partial, boolean allowDrops,
        PartialPolicy policy, Map<String, ResourceLocation> selections) {
    public static final int MAX_BATCHES = 64;
    public static final int MAX_SELECTIONS = 32;

    public CraftRequest {
        Objects.requireNonNull(recipe);
        Objects.requireNonNull(policy);
        if (batches < 1 || batches > MAX_BATCHES || selections.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("Craft request exceeds limits");
        }
        for (var entry : selections.entrySet()) {
            if (!entry.getKey().matches("0(?:\\.[0-8]){1,12}")) {
                throw new IllegalArgumentException("Invalid demand path");
            }
            Objects.requireNonNull(entry.getValue());
        }
        selections = selections.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(selections));
    }

    public static CraftRequest one(ResourceLocation recipe) {
        return new CraftRequest(recipe, 1, false, false, PartialPolicy.EXPLICIT_SAFE, Map.of());
    }

    public CraftRequest withBatches(int count) {
        if (count == batches) return this;
        return new CraftRequest(recipe, count, partial, allowDrops, policy, selections);
    }

    public CraftRequest withPartial(boolean value) {
        if (value == partial) return this;
        return new CraftRequest(recipe, batches, value, allowDrops, policy, selections);
    }

    public enum PartialPolicy {
        // 收紧顺序须显式表达，不能按枚举序号推导：WHITELIST 比 EXPLICIT_SAFE 更严格。
        // Restriction order is explicit, never inferred from enum ordinals:
        // WHITELIST is stricter than EXPLICIT_SAFE.
        NEVER, CONFIRM, EXPLICIT_SAFE, WHITELIST;

        /** 求两种偏好的较严交集；任何调用方向均不能放宽世界限制。
         * Return the stricter intersection; neither call direction may relax a world restriction. */
        public PartialPolicy restrict(PartialPolicy other) {
            Objects.requireNonNull(other, "other");
            if (this == NEVER || other == NEVER) return NEVER;
            if (this == CONFIRM || other == CONFIRM) return CONFIRM;
            if (this == WHITELIST || other == WHITELIST) return WHITELIST;
            return EXPLICIT_SAFE;
        }
    }
}
