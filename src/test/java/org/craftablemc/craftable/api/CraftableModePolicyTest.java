package org.craftablemc.craftable.api;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定能力准入的模式边界，防止创造/旁观或未知模式意外获得合成功能；实际资源权限由服务端集成测试覆盖。
 * Pin ability admission by game mode so creative/spectator/unknown modes cannot gain crafting; server integration tests cover resource permissions.
 */
class CraftableModePolicyTest {
    @Test void onlySurvivalAndAdventureHaveCraftableAbilities() {
        assertTrue(CraftableModePolicy.allows(GameType.SURVIVAL));
        assertTrue(CraftableModePolicy.allows(GameType.ADVENTURE));
        assertFalse(CraftableModePolicy.allows(GameType.CREATIVE));
        assertFalse(CraftableModePolicy.allows(GameType.SPECTATOR));
        assertFalse(CraftableModePolicy.allows(null));
    }
}
