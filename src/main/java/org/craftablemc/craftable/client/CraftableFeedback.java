package org.craftablemc.craftable.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.craftablemc.craftable.api.CraftingResultCode;

/** 中文：在客户端线程把已收到的结果路由到原版提示，不改变规则、库存或请求生命周期。
 * 结果提示留在原槽位，正文过长时改用能完整显示的短句。
 * English: Routes received results to vanilla client-thread feedback without changing rules, inventory or request lifecycle.
 * Result toasts keep their slot and use a shorter message when the full text cannot fit. */
@OnlyIn(Dist.CLIENT)
public final class CraftableFeedback {
    private static final SystemToast.SystemToastId CREATE_RESULT = new SystemToast.SystemToastId();
    private static final Component FAILURE_TITLE = Component.translatable("toast.craftable.create.failed.title");
    private static final Component RESULT_TITLE = Component.translatable("toast.craftable.result.title");
    private static final Component MISSING_REASON = Component.translatable("reason.craftable.missing_ingredients");
    // 原版按文字宽度加 30 像素决定 Toast 的宽度。 / Vanilla sizes its toast to the text width plus 30 pixels.
    private static final int TOAST_PADDING = 30;

    private CraftableFeedback() {}

    public static void showCreateResult(org.craftablemc.craftable.network.CreateRecipeResultPayload payload, boolean detailed) {
        var code = payload.resultCode();
        // 待确认方案也可能带有预计掉落。只有已提交结果才能说余料已丢出。
        // A pending review can include predicted drops. Report delivery only after the craft commits.
        List<ItemStack> drops = code == CraftingResultCode.CREATED || code == CraftingResultCode.PARTIAL_CREATED
                ? payload.drops() : List.of();
        if (code == CraftingResultCode.PARTIAL_CREATED || !drops.isEmpty()
                || detailed && code == CraftingResultCode.MISSING_INGREDIENTS && !payload.missing().isEmpty()) {
            var message = Component.empty();
            if (code == CraftingResultCode.PARTIAL_CREATED) {
                message.append(Component.translatable("toast.craftable.prepared", itemList(payload.primary())));
            }
            if (!payload.missing().isEmpty()) {
                if (!message.getSiblings().isEmpty()) message.append("; ");
                var deficits = Component.empty();
                int limit = Math.min(3, payload.missing().size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) deficits.append(", ");
                    var missing = payload.missing().get(i);
                    Component item = missing.alternatives().isEmpty() ? Component.literal("?")
                            : missing.alternatives().getFirst().getHoverName();
                    deficits.append(Component.translatable("toast.craftable.item_count", missing.count(), item));
                    if (missing.alternatives().size() > 1) deficits.append(Component.translatable("toast.craftable.or_equivalent"));
                }
                if (payload.missing().size() > limit) deficits.append("…");
                message.append(Component.translatable("toast.craftable.missing", deficits));
            }
            if (!drops.isEmpty()) {
                if (!message.getSiblings().isEmpty()) message.append("; ");
                message.append(Component.translatable("toast.craftable.dropped", itemList(drops)));
            }
            if (code == CraftingResultCode.MISSING_INGREDIENTS) {
                showToast(FAILURE_TITLE, message, missingHint(), MISSING_REASON);
            } else if (code == CraftingResultCode.PARTIAL_CREATED) {
                var prepared = Component.translatable("toast.craftable.prepared_short");
                if (drops.isEmpty()) {
                    showToast(RESULT_TITLE, message, prepared);
                } else {
                    var dropped = Component.translatable("toast.craftable.dropped_short");
                    showToast(RESULT_TITLE, message, prepared.copy().append(" ").append(dropped), dropped);
                }
            } else {
                showToast(RESULT_TITLE, message, Component.translatable("toast.craftable.dropped_short"));
            }
            return;
        }
        showCreateResult(code, detailed);
    }

    private static Component itemList(List<ItemStack> stacks) {
        var text = Component.empty();
        for (int i = 0; i < Math.min(3, stacks.size()); i++) {
            if (i > 0) text.append(", ");
            var stack = stacks.get(i);
            text.append(Component.translatable("toast.craftable.item_count", stack.getCount(), stack.getHoverName()));
        }
        if (stacks.size() > 3) text.append("…");
        return text;
    }

    public static void showCreateResult(CraftingResultCode resultCode, boolean detailed) {
        // 中文：沿用原版成功反馈：物品进入背包即可，不为每次成功再发通知。
        // Ordinary vanilla crafting does not announce every successful item.
        // Seeing the output enter the inventory remains the success feedback.
        if (resultCode == CraftingResultCode.CREATED || resultCode == CraftingResultCode.FURNACE_QUEUED) {
            return;
        }

        if (!detailed) {
            showToast(FAILURE_TITLE, Component.translatable("message.craftable.create.failed"),
                    Component.translatable("toast.craftable.detailed_hint"));
            return;
        }
        String reason = resultCode.name().toLowerCase(Locale.ROOT);
        var message = Component.translatable("message.craftable.create." + reason);
        switch (resultCode) {
            case MISSING_INGREDIENTS, NO_USEFUL_PROGRESS ->
                    showToast(FAILURE_TITLE, message, missingHint(), MISSING_REASON);
            case CONFIRMATION_REQUIRED ->
                    showToast(FAILURE_TITLE, message, Component.translatable("toast.craftable.confirm_short"));
            case CONFIRMATION_EXPIRED ->
                    showToast(FAILURE_TITLE, message, Component.translatable("toast.craftable.reopen_short"));
            case PARTIAL_CREATED ->
                    showToast(RESULT_TITLE, message, Component.translatable("toast.craftable.prepared_short"));
            default -> showToast(FAILURE_TITLE, message, Component.translatable("reason.craftable." + reason));
        }
    }

    private static Component missingHint() {
        var binding = CraftableKeyMappings.CREATE_ONE;
        var key = binding.getKey();
        return binding.isUnbound() || key.getType() == InputConstants.Type.MOUSE
                ? MISSING_REASON : Component.translatable("toast.craftable.missing_hint", key.getDisplayName());
    }

    private static void showToast(Component title, Component... messages) {
        Minecraft minecraft = Minecraft.getInstance();
        var toasts = minecraft.getToasts();
        var toast = toasts.getToast(SystemToast.class, CREATE_RESULT);
        int width = toast == null ? minecraft.getWindow().getGuiScaledWidth() : toast.width();
        int textWidth = width - TOAST_PADDING;
        Component message = null;
        for (Component candidate : messages) {
            if (minecraft.font.width(candidate) <= textWidth) {
                message = candidate;
                break;
            }
        }

        if (toast != null) {
            // 原位更新文字和计时。Toast 的固定宽度决定本次可用的文案。
            // Reset the text and timer in place, using the width already reserved for this toast.
            toast.reset(title, message);
            return;
        }

        // 初次创建时为缺料引导留出宽度，随后只放入本次实际要显示的内容。
        // Size the new toast to accommodate the missing-materials hint, then set the actual message.
        var hint = missingHint();
        int hintWidth = minecraft.font.width(hint);
        if (hintWidth <= textWidth && (message == null || hintWidth > minecraft.font.width(message))) {
            toast = new SystemToast(CREATE_RESULT, title, hint);
            toast.reset(title, message);
        } else {
            toast = new SystemToast(CREATE_RESULT, title, message);
        }
        toasts.addToast(toast);
    }
}
