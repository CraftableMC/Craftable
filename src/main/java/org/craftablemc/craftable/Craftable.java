package org.craftablemc.craftable;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.network.CraftablePayloads;
import org.slf4j.Logger;

/**
 * 模组注册根：菜单、注册配置与协议监听；不持有玩家/世界运行期状态。
 * Mod registration root for menus, registered configs and protocol listeners; owns no player/world runtime state.
 * 由NeoForge实例化，不能因没有显式构造调用而移除。
 * NeoForge instantiates this class; absent explicit constructor calls do not imply it is unused.
 */
@Mod(Craftable.MOD_ID)
public final class Craftable {
    public static final String MOD_ID = "craftable";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Craftable(IEventBus modEventBus, ModContainer modContainer) {
        org.craftablemc.craftable.menu.CraftableMenus.MENUS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, CraftableServerConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, CraftableClientConfig.SPEC);
        modEventBus.addListener(CraftablePayloads::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
