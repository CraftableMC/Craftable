package org.craftablemc.craftable.client;

import java.util.OptionalLong;

/** 中文：仅维护客户端按键边沿和同菜单/目标配对；服务端结果决定是否允许部分准备。
 * 不持有执行授权，按住键产生的系统重复事件不能组成第二次按键。
 * English: Tracks only client key edges and same-menu/target pairing; server outcomes decide preparation eligibility.
 * It holds no execution authority, and OS repeats from a held key cannot form a second press. */
public final class DoublePressGesture {
    private boolean down;
    private Object menu;
    private Object target;
    private long previous = -1;
    private long pressedAt;

    /** 中文：返回前次请求序号，或用 -1 表示未配对；空结果表示本次不是新的按键边沿。
     * English: Return the previous request sequence, or -1 for an unpaired press; empty means no new key edge. */
    public OptionalLong press(Object menu, Object target, long sequence, long now, long windowNanos) {
        if (down) return OptionalLong.empty(); // 中文：系统重复不算第二次按键。 English: OS repeat is not a second press.
        down = true;
        boolean paired = this.menu == menu && java.util.Objects.equals(this.target, target)
                && previous >= 0 && now >= pressedAt && now - pressedAt <= windowNanos;
        long linked = paired ? previous : -1;
        this.menu = menu;
        this.target = target;
        this.previous = paired ? -1 : sequence;
        this.pressedAt = now;
        return OptionalLong.of(linked);
    }

    public void release() { down = false; }
    public void clear() { down = false; menu = null; target = null; previous = -1; }
}
