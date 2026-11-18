package org.craftablemc.craftable.client;

import java.util.concurrent.atomic.AtomicLong;

/** 中文：提供客户端进程内不复用的异步请求关联号；跨断线继续递增，但不携带服务端授权。
 * 原子计数保证分配唯一，实际游戏状态仍由客户端线程拥有。
 * English: Supplies non-reused asynchronous correlation IDs for this client process, continuing across disconnects without authority.
 * Atomic allocation ensures uniqueness; actual game state remains client-thread owned. */
public final class ClientRequestSequence {
    private static final AtomicLong NEXT = new AtomicLong();

    private ClientRequestSequence() {}

    public static long next() {
        long value = NEXT.incrementAndGet();
        if (value < 0) {
            throw new IllegalStateException("Client request sequence exhausted");
        }
        return value;
    }
}
