package org.craftablemc.craftable.client;

import static org.craftablemc.craftable.client.PlanningClientFixture.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.client.PlanningClientSmoke.Stage;

/** 中文：验证 GUI/语言显示、刷新延迟及原版菜单恢复；模式切换后检查网格/光标物品归还，防止显示验收遗漏状态丢失。
 * English: Checks GUI/language presentation, refresh latency and vanilla menu restoration, including grid/cursor returns after mode changes so visual acceptance cannot hide lost state. */
final class PlanningPresentationScenario {
    private long previewStart;
    private final java.util.List<Long> timings = new java.util.ArrayList<>();

    boolean tick(Stage stage, int age, Minecraft mc) throws Exception {
        if (stage == Stage.GUI_SCALE_ONE && age > 15 && ready()) {
            shot("scale-1");
            mc.getWindow().setWindowed(1280, 960);
            mc.options.guiScale().set(3); mc.resizeDisplay();
            return true;
        } else if (stage == Stage.GUI_SCALE_THREE && age > 15 && ready()) {
            require(mc.getWindow().getGuiScale() == 3, "Scale-three fixture not actually applied");
            shot("scale-3");
            mc.getLanguageManager().setSelected("en_us"); mc.options.languageCode = "en_us";
            mc.reloadResourcePacks();
            return true;
        } else if (stage == Stage.ENGLISH_PRESENTATION && age > 100 && mc.getOverlay() == null && ready()) {
            require(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.graph").getString().equals("Item chain"),
                    "English translation not loaded");
            GraphPresentationScenario.verifyPlayerWording();
            shot("english");
            return true;
        } else if (stage == Stage.DETAIL_REFRESH_LATENCY) {
            if (previewStart == 0 && ready()) {
                pressButton(net.minecraft.network.chat.Component.translatable("screen.craftable.plan.refresh").getString());
                require(!((Button) field(overlay(), "refresh")).active, "Refresh allowed duplicate clicks while pending");
                require((boolean) field(field(overlay(), "refresh"), "busy"), "Refresh has no pending visual state");
                previewStart = System.nanoTime();
            } else if (previewStart != 0 && draft() != null && !(boolean) field(overlay(), "dirty")) {
                timings.add(System.nanoTime() - previewStart); previewStart = 0;
                if (timings.size() == 40) {
                    var sorted = timings.stream().sorted().toList();
                    long p95 = sorted.get(37);
                    Craftable.LOGGER.warn("PLANNING_SMOKE detail 40 refreshes P50={}ms P95={}ms MAX={}ms",
                            sorted.get(19) / 1e6, p95 / 1e6, sorted.getLast() / 1e6);
                    require(p95 <= 500_000_000L, "Ordinary detail P95 exceeded 500 ms");
                    return true;
                }
            }
        } else if (stage == Stage.MENU_MODE_CHANGE && ready()) {
            mc.getSingleplayerServer().execute(() -> {
                var player = serverPlayer();
                player.containerMenu.getSlot(1).set(new ItemStack(Items.EMERALD, 3));
                player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 2));
                player.containerMenu.broadcastChanges();
                player.setGameMode(GameType.CREATIVE);
            });
            return true;
        } else if (stage == Stage.CREATIVE_RETURN && age > 40) {
            require(!CraftingPlanOverlay.active(), "Mode change retained detail");
            require(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen,
                    "Mode change lost vanilla creative UI");
            require(mc.player.getInventory().countItem(Items.EMERALD) == 3, "Mode change lost grid");
            require(mc.player.getInventory().countItem(Items.GOLD_INGOT) == 2, "Mode change lost cursor");
            mc.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                    net.neoforged.fml.ModList.get().getModContainerById(Craftable.MOD_ID).orElseThrow(), mc.screen));
            return true;
        } else if (stage == Stage.PROFESSIONAL_SETTINGS_INDEX && age > 20) {
            shot("config-index");
            var label = net.minecraft.network.chat.Component.translatable("craftable.configuration.section.craftable.client.toml").getString();
            var button = allButtons(mc.screen).stream().filter(b -> b.getMessage().getString().contains(label)).findFirst().orElseThrow();
            require(button.active, "Local client preferences unavailable");
            mc.screen.mouseClicked(button.getX() + 5, button.getY() + 5, 0);
            return true;
        } else if (stage == Stage.PROFESSIONAL_SETTINGS_CLIENT && age > 20) {
            require(mc.screen instanceof net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen,
                    "Native configuration section did not open");
            shot("config-client");
            return true;
        }
        return false;
    }
}
