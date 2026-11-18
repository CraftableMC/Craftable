package org.craftablemc.craftable.menu;

import java.util.List;
import org.craftablemc.craftable.menu.mixin.SlotPositionAccessor;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.craftablemc.craftable.config.CraftableServerConfig;

/**
 * 原版 CraftingMenu 的扩展：保留原槽身份/编号和网格归还语义，只调整位置并追加装备槽。
 * 服务端以打开时世界/模式和当前工作台范围验证每个实际操作，不能依赖客户端画面或开页时权限。
 * Extend vanilla CraftingMenu while retaining slot identities/IDs and grid-return semantics; only reposition and append equipment.
 * Server operations check opening world/mode and current workstation range, not client display or opening-time permission alone.
 */
public final class AmbientInventoryMenu extends CraftingMenu {
    public static final int EQUIPMENT_START = 46;
    public static final int SLOT_COUNT = 51;
    private final List<BlockPos> tables;
    private final Level openedLevel;
    private final net.minecraft.world.level.GameType openedGameMode;

    public AmbientInventoryMenu(int id, Inventory inventory) {
        this(id, inventory, List.of());
    }

    public AmbientInventoryMenu(int id, Inventory inventory, List<BlockPos> tables) {
        super(id, inventory, inventory.player.level().isClientSide ? ContainerLevelAccess.NULL
                : ContainerLevelAccess.create(inventory.player.level(), inventory.player.blockPosition()));
        this.tables = List.copyOf(tables);
        this.openedLevel = inventory.player.level();
        this.openedGameMode = inventory.player instanceof ServerPlayer serverPlayer
                ? serverPlayer.gameMode.getGameModeForPlayer() : null;
        reposition(getSlot(0), 134, 62);
        for (int i = 0; i < 9; i++) reposition(getSlot(1 + i), 98 + i % 3 * 18, 8 + i / 3 * 18);
        for (int i = 0; i < 5; i++) {
            // 委托真实原版槽的限制/装备回调，保留绑定诅咒、组件与副手语义。
            // Delegate restrictions/equip callbacks to real vanilla slots, retaining binding curse, components and offhand semantics.
            Slot vanilla = inventory.player.inventoryMenu.getSlot(i < 4 ? 5 + i : 45);
            this.addSlot(new Slot(inventory, i < 4 ? 39 - i : 40, vanilla.x, vanilla.y) {
                @Override public boolean mayPlace(ItemStack stack) { return vanilla.mayPlace(stack); }
                @Override public boolean mayPickup(Player player) { return vanilla.mayPickup(player); }
                @Override public int getMaxStackSize() { return vanilla.getMaxStackSize(); }
                @Override public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() { return vanilla.getNoItemIcon(); }
                @Override public void setByPlayer(ItemStack stack, ItemStack old) { vanilla.setByPlayer(stack, old); }
            });
        }
    }

    private static void reposition(Slot slot, int x, int y) {
        var position = (SlotPositionAccessor) slot;
        position.craftable$setX(x);
        position.craftable$setY(y);
    }

    @Override public MenuType<?> getType() { return CraftableMenus.AMBIENT_INVENTORY.get(); }

    @Override public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true;
        if (!player.isAlive() || player.isSpectator() || player.level() != openedLevel
                || !(player instanceof ServerPlayer serverPlayer)
                || !org.craftablemc.craftable.api.CraftableModePolicy.allows(serverPlayer.gameMode.getGameModeForPlayer())
                || serverPlayer.gameMode.getGameModeForPlayer() != openedGameMode) return false;
        var settings = CraftableServerConfig.scanSettings();
        BlockPos origin = player.blockPosition();
        // 检查已知工作台避免每 tick 全扫描；工作台破坏后连一次结果槽操作也不能额外放行。
        // Check known workstations instead of scanning each tick; a broken table cannot authorize even one extra result-slot operation.
        return tables.stream().anyMatch(pos -> withinRange(origin, pos, settings.horizontalRadius(), settings.verticalRadius())
                && openedLevel.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                && openedLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE));
    }

    public static boolean withinRange(BlockPos origin, BlockPos table, int horizontal, int vertical) {
        return Math.abs((long) origin.getX() - table.getX()) <= horizontal
                && Math.abs((long) origin.getZ() - table.getZ()) <= horizontal
                && Math.abs((long) origin.getY() - table.getY()) <= vertical;
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) {
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
            return;
        }
        super.clicked(slot, button, type, player);
    }

    @Override public void handlePlacement(boolean all, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, ServerPlayer player) {
        if (!stillValid(player)) { player.closeContainer(); return; }
        super.handlePlacement(all, recipe, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        if (index >= 10 && index < 46) {
            Slot source = slots.get(index);
            for (int target = EQUIPMENT_START; target < SLOT_COUNT - 1; target++) {
                Slot equipment = slots.get(target);
                if (source.hasItem() && !equipment.hasItem() && equipment.mayPlace(source.getItem())) {
                    ItemStack original = source.getItem().copy();
                    if (moveItemStackTo(source.getItem(), target, target + 1, false)) {
                        source.setChanged();
                        source.onTake(player, source.getItem());
                        return original;
                    }
                }
            }
        }
        return super.quickMoveStack(player, index);
    }
}
