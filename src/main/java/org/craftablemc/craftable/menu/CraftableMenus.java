package org.craftablemc.craftable.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.craftablemc.craftable.Craftable;

/** 原生菜单注册入口，构造器由注册表调用。 / Native menu registration; factories are called by the registry. */
public final class CraftableMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Craftable.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<AmbientInventoryMenu>> AMBIENT_INVENTORY =
            MENUS.register("ambient_inventory", () -> new MenuType<>(AmbientInventoryMenu::new, FeatureFlags.DEFAULT_FLAGS));
    private CraftableMenus() {}
}
