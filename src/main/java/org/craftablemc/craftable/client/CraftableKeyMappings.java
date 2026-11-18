package org.craftablemc.craftable.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.craftablemc.craftable.Craftable;
import org.lwjgl.glfw.GLFW;

/** 中文：声明式注册 GUI 快捷键；按键只表达玩家意图，实际请求由配方书输入处理器路由。
 * English: Declaratively registers the GUI shortcut; the key expresses intent only, routed by the recipe-book input handler. */
@EventBusSubscriber(modid = Craftable.MOD_ID, value = Dist.CLIENT)
public final class CraftableKeyMappings {
    public static final String CATEGORY = "key.categories.craftable";

    public static final KeyMapping CREATE_ONE = new KeyMapping(
            "key.craftable.create_one",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY);

    private CraftableKeyMappings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CREATE_ONE);
    }
}
