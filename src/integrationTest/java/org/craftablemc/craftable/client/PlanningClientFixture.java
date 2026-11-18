package org.craftablemc.craftable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.craftablemc.craftable.execution.CraftingService;

/** 中文：小型规划专用夹具访问；输入仍走真实嵌入层事件，不拥有场景转换、等待或产品行为。
 * English: Small, planning-only fixture access. Input still goes through real overlay
 * events; this does not own scenario transitions, waits or product behavior. */
final class PlanningClientFixture {
    static final BlockPos CHEST = new BlockPos(1, -60, 1);
    static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");
    static volatile boolean configured;

    private PlanningClientFixture() {}

    static void stock(boolean diamonds) {
        var player = serverPlayer();
        player.getInventory().clearContent();
        var chest = (ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST);
        chest.clearContent(); chest.setItem(0, new ItemStack(Items.OAK_LOG));
        if (diamonds) chest.setItem(1, new ItemStack(Items.DIAMOND, 3));
        chest.setChanged(); player.containerMenu.broadcastChanges();
    }
    static java.util.List<Button> allButtons(net.minecraft.client.gui.components.events.GuiEventListener listener) {
        var result = new java.util.ArrayList<Button>();
        if (listener instanceof Button button) result.add(button);
        if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container)
            container.children().forEach(child -> result.addAll(allButtons(child)));
        return result;
    }
    static net.minecraft.server.level.ServerPlayer serverPlayer() {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }
    static Object field(Object object, String name) throws Exception {
        var field = (object == null ? CraftingPlanOverlay.class : object.getClass()).getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    static CraftingPlanOverlay overlay() throws Exception { return (CraftingPlanOverlay) field(null, "current"); }
    static CraftingService.Draft draft() throws Exception { return (CraftingService.Draft) field(overlay(), "draft"); }
    static boolean ready() throws Exception {
        if (overlay() == null || field(overlay(), "pending") != null || (boolean) field(overlay(), "dirty") || draft() == null) return false;
        var maximum = (CraftingService.Maximum) field(overlay(), "maximum");
        return maximum != null && !maximum.pending();
    }
    static void click(double x, double y) {
        Screen parent = Minecraft.getInstance().screen;
        var press = new ScreenEvent.MouseButtonPressed.Pre(parent, x, y, 0);
        NeoForge.EVENT_BUS.post(press);
        require(press.isCanceled(), "Overlay allowed click-through");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(parent, x, y, 0));
    }
    static void key(int key, int modifiers) {
        var event = new ScreenEvent.KeyPressed.Pre(Minecraft.getInstance().screen, key, 0, modifiers);
        NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "Expected key not consumed: " + key);
    }
    static void pressButton(String suffix) throws Exception {
        var button = overlay().children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().endsWith(suffix))
                .map(c -> (Button) c).findFirst().orElseThrow();
        click(button.getX() + 5, button.getY() + 5);
    }
    static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "planning-smoke-" + name + ".png", mc.getMainRenderTarget(), ignored -> {});
    }
    static long executionCounter(String name) throws Exception {
        var field = CraftingService.class.getDeclaredField(name); field.setAccessible(true);
        return field.getLong(null);
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
