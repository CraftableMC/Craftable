package org.craftablemc.craftable.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.api.CraftingResultCode;
import org.craftablemc.craftable.client.menu.AmbientInventoryScreen;
import org.craftablemc.craftable.client.mixin.RecipeBookComponentAccessor;
import org.craftablemc.craftable.client.mixin.RecipeBookPageAccessor;
import org.craftablemc.craftable.client.recipebook.RecipeBookInputHandler;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.network.CreateRecipeResultPayload;

/** 中文：延迟真实服务端需确认包并更换实际菜单，区分界面干扰与执行；同页正例防止用删除自动详情掩盖问题。
 * English: Delay a real server confirmation reply across an actual menu change, separating UI interference from execution;
 * the same-page positive case prevents removing automatic details to satisfy the negative check. */
final class LateConfirmationScenario {
    private enum Phase { PREPARE, OPEN, SEARCH, SEND, SECOND_PRESS, HELD, REOPEN, RELEASE, VERIFY, DONE }
    private Phase phase = Phase.PREPARE;
    private int age;
    private boolean samePage;
    private Channel channel;
    private Gate gate;
    private ModConfigSpec.ConfigValue<?> worldMode, personalMode;
    private Object oldWorld, oldPersonal;
    private static final ResourceLocation PICK = ResourceLocation.withDefaultNamespace("diamond_pickaxe");

    boolean tick(Minecraft mc) throws Exception {
        if (++age > 200) throw new AssertionError("Late confirmation timeout: " + phase + ", samePage=" + samePage);
        switch (phase) {
            case PREPARE -> {
                worldMode = CraftableServerConfig.directPreparationValues().getFirst(); oldWorld = worldMode.getRaw();
                personalMode = (ModConfigSpec.ConfigValue<?>) CraftableClientConfig.SPEC.getValues().get(List.of("crafting", "partialPolicy"));
                oldPersonal = personalMode.getRaw();
                SettingsUiAccess.restoreValue(worldMode, DirectPreparationPolicy.Mode.REQUIRE_CONFIRMATION);
                SettingsUiAccess.restoreValue(personalMode, DirectPreparationPolicy.Mode.ALL_SUPPORTED);
                mc.player.closeContainer();
                var id = mc.player.getUUID();
                mc.getSingleplayerServer().submit(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
                    var level = player.serverLevel();
                    level.setBlockAndUpdate(new BlockPos(2, -60, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
                    ((ChestBlockEntity) level.getBlockEntity(new BlockPos(1, -60, 1))).clearContent();
                    player.containerMenu.broadcastChanges();
                }).join();
                channel = mc.getConnection().getConnection().channel(); gate = new Gate();
                channel.eventLoop().submit(() -> channel.pipeline().addBefore("packet_handler", "craftable_late_confirmation_test", gate)).syncUninterruptibly();
                next(Phase.OPEN);
            }
            case OPEN -> {
                if (age < 5) return false;
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
                mc.player.getRecipeBook().setFiltering(RecipeBookType.CRAFTING, false);
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket(RecipeBookType.CRAFTING, true, false));
                mc.setScreen(new InventoryScreen(mc.player)); next(Phase.SEARCH);
            }
            case SEARCH -> {
                if (!(mc.screen instanceof AmbientInventoryScreen screen)) return false;
                var book = screen.getRecipeBookComponent();
                if (!book.isVisible()) book.toggleVisibility();
                ((RecipeBookComponentAccessor) book).craftable$getSearchBox().setValue(Items.DIAMOND_PICKAXE.getDescription().getString());
                book.recipesUpdated();
                var tabsField = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("tabButtons");
                tabsField.setAccessible(true);
                var all = (net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton) ((List<?>) tabsField.get(book)).getFirst();
                book.mouseClicked(all.getX() + 5, all.getY() + 5, 0);
                next(Phase.SEND);
            }
            case SEND -> {
                if (age < 10) return false;
                gate.fullSeen = false;
                press(mc); next(Phase.SECOND_PRESS);
            }
            case SECOND_PRESS -> {
                // 中文：同 tick 连发会被真实服务端节流；先看到真实缺料结果，再在双击窗口内发送第二次按键。
                // Two same-tick requests are legitimately throttled; observe the real missing-input reply before the second press within the gesture window.
                if (age < 2 || !gate.fullSeen) return false;
                press(mc); next(Phase.HELD);
            }
            case HELD -> {
                if (gate.held == null) return false;
                require(!CraftingPlanOverlay.active(), "Held confirmation already opened details");
                requireUnspent(mc);
                if (samePage) next(Phase.RELEASE);
                else { mc.player.closeContainer(); next(Phase.REOPEN); }
            }
            case REOPEN -> {
                if (age < 5 || mc.player.containerMenu != mc.player.inventoryMenu) return false;
                mc.setScreen(new InventoryScreen(mc.player)); next(Phase.RELEASE);
            }
            case RELEASE -> {
                if (!(mc.screen instanceof AmbientInventoryScreen) || age < 5) return false;
                channel.eventLoop().submit(() -> gate.forward(channel.pipeline().context(gate))).syncUninterruptibly();
                next(Phase.VERIFY);
            }
            case VERIFY -> {
                if (age < 10) return false;
                requireUnspent(mc);
                require(CraftingPlanOverlay.active() == samePage, samePage
                        ? "Current-page confirmation no longer opens details"
                        : "Late confirmation opened old details on a new recipe-book page");
                if (!samePage) {
                    Craftable.LOGGER.warn("LATE_CONFIRMATION obsolete-page PASS realReply=true zeroConsumption=true");
                    samePage = true; next(Phase.SEARCH);
                } else {
                    Craftable.LOGGER.warn("LATE_CONFIRMATION same-page PASS automaticDetails=true zeroConsumption=true");
                    close(); phase = Phase.DONE; return true;
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private static void press(Minecraft mc) throws ReflectiveOperationException {
        var book = ((AmbientInventoryScreen) mc.screen).getRecipeBookComponent();
        var access = (RecipeBookComponentAccessor) book;
        access.craftable$getSearchBox().setFocused(false);
        var page = access.craftable$getRecipeBookPage();
        var button = ((RecipeBookPageAccessor) page).craftable$getButtons().stream().filter(b -> b.visible
                && b.getCollection().getRecipes().stream().anyMatch(r -> r.id().equals(PICK))).findFirst().orElseThrow();
        // 中文：每次按键前重设真实悬停观察点，渲染会清掉上一帧 hover；不替代输入请求或服务端规则。
        // Reset the real hover observation before each press: rendering clears the previous frame's hover. Requests/rules remain production paths.
        var hover = page.getClass().getDeclaredField("hoveredButton"); hover.setAccessible(true); hover.set(page, button);
        var event = new ScreenEvent.KeyPressed.Pre(mc.screen, org.lwjgl.glfw.GLFW.GLFW_KEY_C, 0, 0);
        RecipeBookInputHandler.onKeyPressed(event);
        require(event.isCanceled(), "Actual ordinary C input was not routed");
        RecipeBookInputHandler.onKeyReleased(new ScreenEvent.KeyReleased.Pre(mc.screen, org.lwjgl.glfw.GLFW.GLFW_KEY_C, 0, 0));
    }
    private static void requireUnspent(Minecraft mc) {
        require(unspent(mc.player.getInventory()), "Late confirmation changed client inventory");
        var id = mc.player.getUUID();
        require(mc.getSingleplayerServer().submit(() -> unspent(mc.getSingleplayerServer().getPlayerList().getPlayer(id).getInventory())).join(),
                "Late confirmation changed authoritative inventory");
    }
    private static boolean unspent(net.minecraft.world.entity.player.Inventory inventory) {
        return inventory.countItem(Items.OAK_LOG) == 1 && inventory.countItem(Items.OAK_PLANKS) == 0
                && inventory.countItem(Items.STICK) == 0 && inventory.countItem(Items.DIAMOND_PICKAXE) == 0;
    }
    private void next(Phase next) { phase = next; age = 0; }
    void close() {
        try {
            if (gate != null) channel.eventLoop().submit(() -> {
                if (channel.pipeline().context(gate) != null) channel.pipeline().remove(gate);
            }).syncUninterruptibly();
        } finally {
            if (oldWorld != null) { SettingsUiAccess.restoreValue(worldMode, oldWorld); oldWorld = null; }
            if (oldPersonal != null) { SettingsUiAccess.restoreValue(personalMode, oldPersonal); oldPersonal = null; }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Gate extends ChannelInboundHandlerAdapter {
        volatile Object held;
        volatile boolean fullSeen;
        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof CreateRecipeResultPayload result
                    && result.recipeId().equals(PICK) && result.resultCode() == CraftingResultCode.MISSING_INGREDIENTS) fullSeen = true;
            if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof CreateRecipeResultPayload result
                    && result.resultCode() == CraftingResultCode.CONFIRMATION_REQUIRED && held == null) { held = message; return; }
            context.fireChannelRead(message);
        }
        void forward(ChannelHandlerContext context) {
            Object packet = held; held = null; require(packet != null, "Missing real confirmation packet");
            context.fireChannelRead(packet); context.fireChannelReadComplete();
        }
        @Override public void handlerRemoved(ChannelHandlerContext context) {
            Object packet = held; held = null; if (packet != null) ReferenceCountUtil.release(packet);
        }
    }
}
