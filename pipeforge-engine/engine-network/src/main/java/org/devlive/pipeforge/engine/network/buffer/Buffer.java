/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A network buffer: rows (DATA) or one event (EVENT). Its memory comes from a pool and goes back
 * there when the buffer is recycled; an oversized buffer for one large row has no pool.
 */
public final class Buffer {

    public enum Kind {
        DATA,
        EVENT
    }

    /** Takes the memory of a recycled buffer back. */
    @FunctionalInterface
    public interface Recycler {
        void recycle(byte[] memory);
    }

    private final byte[] memory;
    private final Recycler recycler;
    private final AtomicBoolean recycled = new AtomicBoolean();
    private Kind kind = Kind.DATA;
    private int size;

    public Buffer(byte[] memory, Recycler recycler) {
        this.memory = memory;
        this.recycler = recycler;
    }

    /** A buffer that holds {@code bytes} and has no pool. */
    public static Buffer unpooled(Kind kind, byte[] bytes) {
        Buffer buffer = new Buffer(bytes, m -> {});
        buffer.kind = kind;
        buffer.size = bytes.length;
        return buffer;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isEvent() {
        return kind == Kind.EVENT;
    }

    public void setKind(Kind value) {
        this.kind = value;
    }

    public int size() {
        return size;
    }

    public void setSize(int value) {
        if (value < 0 || value > memory.length) {
            throw new IllegalArgumentException("size " + value + " of a buffer of " + memory.length + " bytes");
        }
        this.size = value;
    }

    public int capacity() {
        return memory.length;
    }

    /** The memory; bytes 0 to {@link #size()} are the content. */
    public byte[] memory() {
        return memory;
    }

    /** The content, without copying. */
    public ByteBuffer content() {
        return ByteBuffer.wrap(memory, 0, size).slice();
    }

    /**
     * A buffer over the same memory whose recycling also calls {@code first}; for handing a buffer
     * to a consumer that must learn when it is done with it.
     */
    public Buffer onRecycle(Runnable first) {
        Buffer view = new Buffer(memory, m -> {
            first.run();
            recycle();
        });
        view.kind = kind;
        view.size = size;
        return view;
    }

    /** Gives the memory back; later calls do nothing. */
    public void recycle() {
        if (recycled.compareAndSet(false, true)) {
            recycler.recycle(memory);
        }
    }

    public boolean isRecycled() {
        return recycled.get();
    }
}
