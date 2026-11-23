package org.craftablemc.craftable.api;

/** 服务端执行与客户端反馈共用的稳定结果码；名称和顺序参与现有协议，不能为翻译而改名。
 * Stable result codes shared by execution and feedback; names/order belong to the current protocol, not localization. */
public enum CraftingResultCode {
    CREATED,
    RECIPE_NOT_FOUND,
    UNSUPPORTED_RECIPE,
    MISSING_WORKSTATION,
    MISSING_INGREDIENTS,
    NO_OUTPUT_SPACE,
    ENVIRONMENT_CHANGED,
    INVALID_CONTEXT,
    REQUEST_THROTTLED,
    INTERNAL_ERROR,
    RECIPE_LOCKED,
    SEARCH_BUDGET_EXCEEDED,
    PROTECTED_INGREDIENTS,
    PARTIAL_CREATED,
    NO_USEFUL_PROGRESS,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_EXPIRED,
    DROP_LIMIT_EXCEEDED,
    MISSING_FUEL,
    FURNACE_BATCH_LIMIT,
    FURNACE_BUSY,
    FURNACE_QUEUED
}
