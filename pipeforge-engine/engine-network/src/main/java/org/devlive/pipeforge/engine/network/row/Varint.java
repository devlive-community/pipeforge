/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.row;

import java.nio.ByteBuffer;

/** Unsigned varints as in Protobuf: 7 bits per byte, low bits first, the high bit marks more bytes. */
final class Varint {

    private Varint() {}

    /** Reads a varint at the position of {@code in} and advances it. */
    static long read(ByteBuffer in) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte b = in.get();
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint longer than 10 bytes");
    }

    /** Reads a varint that must be a non-negative int. */
    static int readInt(ByteBuffer in) {
        long value = read(in);
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("varint " + Long.toUnsignedString(value) + " is not an int");
        }
        return (int) value;
    }
}
