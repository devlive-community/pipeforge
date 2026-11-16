/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** A growable byte array that rows are encoded into; multi-byte integers are big-endian. */
public final class RowBuffer {

    private byte[] data;
    private int size;

    public RowBuffer(int capacity) {
        this.data = new byte[Math.max(16, capacity)];
    }

    /** Number of bytes written. */
    public int size() {
        return size;
    }

    /** Forgets the bytes written, keeping the memory. */
    public void clear() {
        size = 0;
    }

    /** The bytes written, without copying; valid until the next write. */
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(data, 0, size).slice();
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    public void writeByte(int value) {
        ensure(1);
        data[size++] = (byte) value;
    }

    public void writeShort(int value) {
        ensure(2);
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) value;
    }

    public void writeLong(long value) {
        ensure(8);
        setLong(size, value);
        size += 8;
    }

    public void writeBytes(byte[] bytes) {
        writeBytes(bytes, 0, bytes.length);
    }

    public void writeBytes(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, data, size, length);
        size += length;
    }

    /** Appends the bytes of another buffer. */
    public void writeBuffer(RowBuffer other) {
        writeBytes(other.data, 0, other.size);
    }

    /** An unsigned varint, as in Protobuf. */
    public void writeVarint(long value) {
        long rest = value;
        while ((rest & ~0x7FL) != 0) {
            writeByte((int) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        writeByte((int) rest);
    }

    /** Appends {@code count} zero bytes, to be filled in later. */
    public void skip(int count) {
        ensure(count);
        Arrays.fill(data, size, size + count, (byte) 0);
        size += count;
    }

    void setLong(int at, long value) {
        for (int i = 7; i >= 0; i--) {
            data[at + 7 - i] = (byte) (value >>> (8 * i));
        }
    }

    void orByte(int at, int bits) {
        data[at] |= (byte) bits;
    }

    private void ensure(int more) {
        int needed = size + more;
        if (needed < 0) {
            throw new IllegalStateException("A row buffer cannot hold more than 2 GiB");
        }
        if (needed > data.length) {
            data = Arrays.copyOf(data, Math.max(needed, (int) Math.min(Integer.MAX_VALUE - 8, data.length * 2L)));
        }
    }
}
