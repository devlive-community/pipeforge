/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.devlive.pipeforge.engine.network.buffer.Buffer;

/**
 * An event in a stream of buffers; it always has a buffer of its own, so it keeps its place
 * between the rows. Codes 0x10 to 0x1F are kept for barriers, watermarks and stream status.
 */
public sealed interface NetworkEvent {

    int END_OF_PARTITION = 0x01;
    int END_OF_INPUT = 0x02;
    int SCHEMA_CHANGE = 0x03;

    /** The event type code of the frame. */
    int code();

    /** No more data in this subpartition. */
    record EndOfPartition() implements NetworkEvent {
        @Override
        public int code() {
            return END_OF_PARTITION;
        }
    }

    /** A bounded input has ended (batch jobs). */
    record EndOfInput() implements NetworkEvent {
        @Override
        public int code() {
            return END_OF_INPUT;
        }
    }

    /**
     * A table changed or appeared: the Protobuf {@code SchemaChange} of the chain protocol; the rows
     * after it use the new columns.
     */
    // the array is copied in and out, and equals and hashCode compare its content
    @SuppressWarnings("ArrayRecordComponent")
    record SchemaChange(byte[] protobuf) implements NetworkEvent {
        public SchemaChange {
            protobuf = protobuf.clone();
        }

        @Override
        public byte[] protobuf() {
            return protobuf.clone();
        }

        @Override
        public int code() {
            return SCHEMA_CHANGE;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SchemaChange change && Arrays.equals(change.protobuf, protobuf);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(protobuf);
        }

        @Override
        public String toString() {
            return "SchemaChange[" + protobuf.length + " bytes]";
        }
    }

    NetworkEvent END_OF_PARTITION_EVENT = new EndOfPartition();
    NetworkEvent END_OF_INPUT_EVENT = new EndOfInput();

    /** The buffer of an event: its code and its content. */
    static Buffer toBuffer(NetworkEvent event) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(event.code());
        if (event instanceof SchemaChange change) {
            long length = change.protobuf.length;
            while ((length & ~0x7FL) != 0) {
                out.write((int) ((length & 0x7F) | 0x80));
                length >>>= 7;
            }
            out.write((int) length);
            out.writeBytes(change.protobuf);
        }
        return Buffer.unpooled(Buffer.Kind.EVENT, out.toByteArray());
    }

    /**
     * The event in a buffer.
     *
     * @throws IllegalArgumentException for an unknown code or a malformed event
     */
    static NetworkEvent fromBuffer(Buffer buffer) {
        ByteBuffer in = buffer.content();
        if (!in.hasRemaining()) {
            throw new IllegalArgumentException("an empty event");
        }
        int code = in.get() & 0xFF;
        NetworkEvent event =
                switch (code) {
                    case END_OF_PARTITION -> END_OF_PARTITION_EVENT;
                    case END_OF_INPUT -> END_OF_INPUT_EVENT;
                    case SCHEMA_CHANGE -> {
                        long length = 0;
                        for (int shift = 0; ; shift += 7) {
                            if (shift > 28) {
                                throw new IllegalArgumentException("a schema change longer than 2 GB");
                            }
                            byte b = in.get();
                            length |= (long) (b & 0x7F) << shift;
                            if (b >= 0) {
                                break;
                            }
                        }
                        if (length != in.remaining()) {
                            throw new IllegalArgumentException(
                                    "a schema change of " + length + " bytes in " + in.remaining());
                        }
                        byte[] bytes = new byte[(int) length];
                        in.get(bytes);
                        yield new SchemaChange(bytes);
                    }
                    default -> throw new IllegalArgumentException("unknown event type 0x" + Integer.toHexString(code));
                };
        if (in.hasRemaining() && !(event instanceof SchemaChange)) {
            throw new IllegalArgumentException("an event of type " + code + " with " + in.remaining() + " extra bytes");
        }
        return event;
    }
}
