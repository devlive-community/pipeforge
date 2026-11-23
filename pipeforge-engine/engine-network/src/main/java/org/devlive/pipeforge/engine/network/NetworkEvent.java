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
 * between the rows. Events need no credit, but never overtake the buffers before them. Codes 0x11
 * to 0x1F are kept for watermarks and stream status.
 */
public sealed interface NetworkEvent {

    int END_OF_PARTITION = 0x01;
    int END_OF_INPUT = 0x02;
    int SCHEMA_CHANGE = 0x03;
    int CHECKPOINT_BARRIER = 0x10;

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

    /**
     * Marks where checkpoint {@code checkpointId} cuts the stream: the rows before it are in the
     * checkpoint, the rows after it are not. Layout (protocol specification, appendix B): {@code u64
     * checkpointId}, {@code u64 timestampMs}, {@code u8 type}, {@code u8 flags} with bit 0 for {@code
     * stop}.
     *
     * @param stop for stop-with-savepoint: the sources read nothing after it
     */
    record CheckpointBarrier(long checkpointId, long timestampMs, Type type, boolean stop) implements NetworkEvent {

        /** The kind of checkpoint, with its code in the frame. */
        public enum Type {
            CHECKPOINT(1),
            SAVEPOINT(2);

            private final int code;

            Type(int code) {
                this.code = code;
            }

            public int code() {
                return code;
            }

            static Type of(int code) {
                for (Type type : values()) {
                    if (type.code == code) {
                        return type;
                    }
                }
                throw new IllegalArgumentException("a checkpoint barrier of unknown type " + code);
            }
        }

        /** The content after the event type code. */
        static final int BYTES = 18;

        static final int STOP = 0x01;

        public CheckpointBarrier {
            if (checkpointId <= 0) {
                throw new IllegalArgumentException("a checkpoint barrier for checkpoint " + checkpointId);
            }
        }

        @Override
        public int code() {
            return CHECKPOINT_BARRIER;
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
        } else if (event instanceof CheckpointBarrier barrier) {
            ByteBuffer content = ByteBuffer.allocate(CheckpointBarrier.BYTES)
                    .putLong(barrier.checkpointId())
                    .putLong(barrier.timestampMs())
                    .put((byte) barrier.type().code())
                    .put((byte) (barrier.stop() ? CheckpointBarrier.STOP : 0));
            out.writeBytes(content.array());
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
                    case CHECKPOINT_BARRIER -> {
                        if (in.remaining() != CheckpointBarrier.BYTES) {
                            throw new IllegalArgumentException("a checkpoint barrier of " + in.remaining()
                                    + " bytes, not " + CheckpointBarrier.BYTES);
                        }
                        long checkpointId = in.getLong();
                        long timestampMs = in.getLong();
                        CheckpointBarrier.Type type = CheckpointBarrier.Type.of(in.get() & 0xFF);
                        int flags = in.get() & 0xFF;
                        if ((flags & ~CheckpointBarrier.STOP) != 0) {
                            throw new IllegalArgumentException(
                                    "a checkpoint barrier with unknown flags 0x" + Integer.toHexString(flags));
                        }
                        yield new CheckpointBarrier(
                                checkpointId, timestampMs, type, (flags & CheckpointBarrier.STOP) != 0);
                    }
                    default -> throw new IllegalArgumentException("unknown event type 0x" + Integer.toHexString(code));
                };
        if (in.hasRemaining() && !(event instanceof SchemaChange)) {
            throw new IllegalArgumentException("an event of type " + code + " with " + in.remaining() + " extra bytes");
        }
        return event;
    }
}
