package org.craftablemc.craftable.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.network.CreateRecipeResultPayload;
import org.craftablemc.craftable.planner.CraftPlan;

final class FeedbackToastScenario {
    private enum Phase { QUEUE, SHOW_SHORT, SHOW_LONG, SHOW_LATEST }
    private Phase phase = Phase.QUEUE;
    private SystemToast toast;
    private int width, adds, frames;

    void added(Toast queued) {
        if (phase == Phase.QUEUE || !(queued instanceof SystemToast systemToast)) return;
        if (toast == null) toast = systemToast;
        if (queued.getToken().equals(toast.getToken())) adds++;
    }

    void rendered() { frames++; }

    boolean tick(Minecraft mc) throws Exception {
        var ingredient = new ItemStack(Items.DIAMOND);
        var shortText = Component.translatable("toast.craftable.missing",
                Component.translatable("toast.craftable.item_count", 3, ingredient.getHoverName()));
        var hint = Component.translatable("toast.craftable.missing_hint",
                CraftableKeyMappings.CREATE_ONE.getKey().getDisplayName());
        switch (phase) {
            case QUEUE -> {
                mc.getToasts().clear();
                phase = Phase.SHOW_SHORT;
                frames = 0;
                CraftableFeedback.showCreateResult(CraftingResultCode.RECIPE_NOT_FOUND, true);
                require(toast != null, "First failure did not queue a system toast");
                width = toast.width();
                CraftableFeedback.showCreateResult(missingResult(ingredient), true);
                expect(mc, shortText);
            }
            case SHOW_SHORT -> {
                // 第一帧分配原版槽位，下一帧实际绘制短提示。
                // Vanilla assigns a slot on the first frame and draws the short toast on the next.
                if (frames < 2) return false;
                expect(mc, shortText);
                String words = "Long ingredient name ";
                ingredient.set(DataComponents.CUSTOM_NAME,
                        Component.literal(words.repeat(width / mc.font.width(words) + 1)));
                CraftableFeedback.showCreateResult(missingResult(ingredient), true);
                expect(mc, hint);
                phase = Phase.SHOW_LONG;
                frames = 0;
            }
            case SHOW_LONG -> {
                if (frames == 0) return false;
                expect(mc, hint);
                CraftableFeedback.showCreateResult(missingResult(ingredient), true);
                phase = Phase.SHOW_LATEST;
                frames = 0;
            }
            case SHOW_LATEST -> {
                if (frames == 0) return false;
                expect(mc, shortText);
                // 待确认回复的预计余料还没有交付。 / Predicted leftovers in a pending review have not been delivered.
                CraftableFeedback.showCreateResult(new CreateRecipeResultPayload(
                        ResourceLocation.withDefaultNamespace("diamond_pickaxe"), 2L,
                        CraftingResultCode.CONFIRMATION_REQUIRED, List.of(), List.of(),
                        List.of(new ItemStack(Items.STICK))), true);
                var confirmation = Component.translatable("message.craftable.create.confirmation_required");
                expect(mc, mc.font.width(confirmation) <= width - 30
                        ? confirmation : Component.translatable("toast.craftable.confirm_short"));
                mc.getToasts().clear();
                return true;
            }
        }
        return false;
    }

    private void expect(Minecraft mc, Component expected) throws Exception {
        require(adds == 1 && mc.getToasts().getToast(SystemToast.class, toast.getToken()) == toast,
                "Updating a crafting failure queued a second toast or replaced the original");
        require(toast.width() == width && width <= mc.getWindow().getGuiScaledWidth() && toast.slotCount() == 1,
                "Updating a crafting failure changed its width or slot use");
        // 原版未公开绘制行。只读实际传给渲染器的文字，避免单查背景宽度漏掉溢出。
        // Vanilla does not expose its draw lines. Check the actual text so fixed background width cannot hide overflow.
        if (!(PlanningClientFixture.field(toast, "messageLines") instanceof List<?> lines) || lines.size() != 1
                || !(lines.getFirst() instanceof FormattedCharSequence line)) {
            throw new AssertionError("Expected one body line in the reused toast");
        }
        require(mc.font.width(line) <= width - 30, "Failure text overflowed its existing toast");
        require(text(line).equals(text(expected.getVisualOrderText())),
                "Unexpected failure text: " + text(line) + "; expected " + expected.getString());
    }

    private static String text(FormattedCharSequence line) {
        var text = new StringBuilder();
        line.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
        return text.toString();
    }

    private static CreateRecipeResultPayload missingResult(ItemStack ingredient) {
        return new CreateRecipeResultPayload(ResourceLocation.withDefaultNamespace("diamond_pickaxe"), 1L,
                CraftingResultCode.MISSING_INGREDIENTS, List.of(),
                List.of(new CraftPlan.Missing("root", List.of(ingredient), 3)), List.of());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
