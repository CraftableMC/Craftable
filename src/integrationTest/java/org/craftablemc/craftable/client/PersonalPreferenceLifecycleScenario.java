package org.craftablemc.craftable.client;

import static org.craftablemc.craftable.client.PlanningClientFixture.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.network.CraftingDetailPayloads;
import org.craftablemc.craftable.planner.CraftRequest;

/** 中文：在真实权威审阅后改变个人偏好，旧可用按钮必须失效，不能发送旧令牌确认。
 * 不伪造授权、不更改服务端库存或预算；无论失败与否都恢复原注册配置值。
 * English: Change a personal preference after real authoritative review; the previously enabled button must invalidate rather than confirm its old token.
 * No authority, server inventory or budgets are manufactured, and the registered config value is restored even on failure. */
final class PersonalPreferenceLifecycleScenario {
    private PersonalPreferenceLifecycleScenario() {}

    @SuppressWarnings("unchecked")
    static void verify() throws Exception {
        var mc = Minecraft.getInstance();
        var overlay = overlay();
        var reviewed = draft();
        var oldCreate = (Button) field(overlay, "create");
        var originalIntent = (CraftRequest) field(overlay, "intent");
        require(ready() && oldCreate.active && !reviewed.token().equals(CraftingDetailPayloads.NO_TOKEN)
                        && !(boolean) field(overlay, "localDraft"),
                "Personal preference regression needs a naturally received authoritative token");
        var value = (ModConfigSpec.ConfigValue<Boolean>) ConfigSettings.values(CraftableClientConfig.SPEC).stream()
                .filter(candidate -> candidate.getPath().getLast().equals("allowSurplusDrops"))
                .findFirst().orElseThrow();
        boolean original = value.getRaw();
        require(originalIntent.allowDrops() == original, "Review started with a stale personal drop preference");
        int generation = (int) field(overlay, "generation");
        long oldRevision = (long) field(overlay, "inFlight");
        var replay = new CraftingDetailPayloads.PreviewResponse(mc.player.containerMenu.containerId, oldRevision, reviewed);
        Throwable originalFailure = null;
        try {
            value.set(!original);
            // 中文：在 tick 尚未发现外部配置变化前，调用此前真实可用按钮的既有回调，捕获同帧旧授权漏洞。
            // Exercise the existing callback with its real previously enabled button,
            // before a tick has the chance to notice the external config change.
            oldCreate.onPress();
            require(((CraftRequest) field(overlay, "intent")).allowDrops() == !original,
                    "Changed personal drop preference left the old confirmation intent active");
            requireInvalidated(overlay, generation, "Preference change");
            CraftingPlanOverlay.receive(replay);
            require(draft() == null, "Old authoritative reply revived a retired personal-preference review");

            int changedGeneration = (int) field(overlay, "generation");
            value.set(original);
            overlay.tick();
            require(((CraftRequest) field(overlay, "intent")).allowDrops() == original,
                    "Restored personal preference did not become a new intent");
            requireInvalidated(overlay, changedGeneration, "Preference restoration");
            CraftingPlanOverlay.receive(replay);
            require(draft() == null, "Preference restoration revived the previous authoritative token");
        } catch (Exception | AssertionError failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                // 中文：不保存到磁盘；原权威审阅有意保持退役，由正常调度回合取得新的本地审阅。
                // Do not save to disk. The original authoritative review deliberately
                // stays retired; normal scheduler turns obtain a fresh local review.
                value.set(original);
                overlay.tick();
                require(CraftableClientConfig.allowSurplusDrops() == original,
                        "Personal preference fixture failed to restore the original registered value");
            } catch (Exception | AssertionError cleanupFailure) {
                if (originalFailure == null) throw cleanupFailure;
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        Craftable.LOGGER.warn("PERSONAL_PREFERENCE_LIFECYCLE PASS: changed/restored preference retires real review and old reply without confirmation");
    }

    private static void requireInvalidated(CraftingPlanOverlay overlay, int previousGeneration, String action) throws Exception {
        require((int) field(overlay, "generation") > previousGeneration, action + " did not advance review generation");
        require(field(overlay, "draft") == null && field(overlay, "maximum") == null
                        && !(boolean) field(overlay, "queuedAction") && (boolean) field(overlay, "dirty")
                        && field(overlay, "pending") == null,
                action + " retained reviewed authority/MAX/queued work or dispatched a confirmation");
    }
}
