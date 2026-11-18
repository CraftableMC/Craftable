package org.craftablemc.craftable.testsupport;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 仅为范围契约提供容量，不触发Minecraft物品初始化；读写物品直接失败，防止范围测试不知不觉依赖真实库存行为。
 * Supply only size for range contracts without Minecraft item initialization; item access fails so range tests cannot silently depend on real inventory behavior.
 */
public final class SizedContainerStub implements Container {
    private final int size;

    public SizedContainerStub(int size) {
        this.size = size;
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {}
}
