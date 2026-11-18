package org.craftablemc.craftable.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 中文：只补原版行的空右槽，保留原 OptionEntry 及选项/延迟滑块映射；原版未公开槽位 setter。
 * English: Fill only the native row's unused right slot, preserving its OptionEntry and option/deferred-slider mappings; vanilla exposes no slot setter. */
@Mixin(targets = "net.minecraft.client.gui.components.OptionsList$Entry")
public interface OptionsListEntryAccessor {
    @Mutable
    @Accessor("children")
    void craftable$setWidgets(List<AbstractWidget> widgets);
}
