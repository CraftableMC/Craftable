package org.craftablemc.craftable.client;

import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import org.craftablemc.craftable.client.mixin.OptionsListEntryAccessor;

/** 中文：在客户端线程追加半宽原版控件，只补末行空位，不替换 OptionEntry。
 * 原列表继续拥有映射、焦点、裁剪和延迟滑块修改，不能复制行后遗失这些状态。
 * English: Append a half-width native widget on the client thread, filling the last row without replacing its OptionEntry.
 * The original list retains mappings, focus, clipping and deferred slider changes, which a copied row could lose. */
public final class NativeOptionRows {
    private NativeOptionRows() {}

    public static void appendSmall(OptionsList list, AbstractWidget widget) {
        var rows = ((ContainerEventHandler) list).children();
        if (!rows.isEmpty()) {
            var last = (ContainerEventHandler) rows.getLast();
            var children = last.children();
            if (children.size() == 1 && children.getFirst() instanceof AbstractWidget left
                    && left.getWidth() <= 150 && last instanceof OptionsListEntryAccessor accessor) {
                accessor.craftable$setWidgets(List.of(left, widget));
                return;
            }
        }
        list.addSmall(widget, null);
    }
}
