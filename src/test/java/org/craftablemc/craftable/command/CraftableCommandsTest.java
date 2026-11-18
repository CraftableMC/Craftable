package org.craftablemc.craftable.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.craftablemc.craftable.config.EnvironmentScanSettings;
import org.craftablemc.craftable.environment.EnvironmentSnapshot;
import org.junit.jupiter.api.Test;

/**
 * 不启动世界即可捕获诊断摘要向翻译组件传入不受支持参数的回归；不证明扫描或命令权限正确。
 * Catch unsupported translation-component arguments in diagnostic summaries without booting a world; this proves neither scanning nor command permissions.
 */
class CraftableCommandsTest {
    @Test
    void environmentSummaryUsesOnlySupportedTranslationArguments() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                Level.OVERWORLD,
                BlockPos.ZERO,
                new EnvironmentScanSettings(8, 4, 5, true),
                42,
                1,
                List.of(),
                List.of());

        assertDoesNotThrow(() -> CraftableCommands.environmentSummary(snapshot));
    }
}
