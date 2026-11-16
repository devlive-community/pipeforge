/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.network.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.network.ResultPartitionId;
import org.devlive.pipeforge.engine.network.buffer.Buffer;

/** Writes and reads frames: magic, version, type, length and payload, big-endian. */
final class FrameCodec {

    private FrameCodec() {}

    static final class Encoder extends MessageToByteEncoder<Frame> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Frame frame, ByteBuf out) {
            try {
                FrameCodec.write(frame, out);
            } finally {
                if (frame instanceof Frame.BufferResponse response) {
                    response.data().release();
                }
            }
        }
    }

    static final class Decoder extends ByteToMessageDecoder {
        private final int maxFrameBytes;

        Decoder(int maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < Frame.HEADER) {
                return;
            }
            int start = in.readerIndex();
            int magic = in.getUnsignedShort(start);
            if (magic != Frame.MAGIC) {
                in.skipBytes(in.readableBytes());
                throw malformed("a frame starts with 0x" + Integer.toHexString(magic) + ", not 0x5046");
            }
            int version = in.getUnsignedByte(start + 2);
            if (version != Frame.VERSION) {
                in.skipBytes(in.readableBytes());
                throw new FrameException(PipeForgeException.of(ErrorCode.RPC_VERSION_MISMATCH)
                        .param("local", Frame.PROTOCOL_VERSION)
                        .param("remote", version + ".x")
                        .build());
            }
            int type = in.getUnsignedByte(start + 3);
            long length = in.getUnsignedInt(start + 4);
            if (length > maxFrameBytes) {
                in.skipBytes(in.readableBytes());
                throw malformed("a frame of " + length + " bytes, more than " + maxFrameBytes);
            }
            if (in.readableBytes() < Frame.HEADER + length) {
                return;
            }
            in.skipBytes(Frame.HEADER);
            ByteBuf payload = in.readSlice((int) length);
            try {
                out.add(read(type, payload));
            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                throw malformed("a malformed frame of type " + type + ": " + e.getMessage());
            }
            if (payload.isReadable() && type != Frame.BUFFER_RESPONSE) {
                throw malformed(payload.readableBytes() + " bytes after a frame of type " + type);
            }
        }
    }

    static FrameException malformed(String detail) {
        return new FrameException(PipeForgeException.of(ErrorCode.INTERNAL)
                .param("detail", detail)
                .build());
    }

    static void write(Frame frame, ByteBuf out) {
        int start = out.writerIndex();
        out.writeShort(Frame.MAGIC);
        out.writeByte(Frame.VERSION);
        out.writeByte(frame.type());
        out.writeInt(0);
        switch (frame) {
            case Frame.Hello hello -> {
                string(out, hello.protocolVersion());
                string(out, hello.workerId());
                string(out, hello.clusterToken());
            }
            case Frame.PartitionRequest request -> {
                uuid(out, request.channel());
                ResultPartitionId id = request.partition();
                string(out, id.runId());
                string(out, id.vertexId());
                varint(out, id.subtask());
                varint(out, id.attempt());
                varint(out, id.outputIndex());
                varint(out, request.subpartition());
                varint(out, request.credit());
            }
            case Frame.BufferResponse response -> {
                uuid(out, response.channel());
                varint(out, response.sequence());
                varint(out, response.backlog());
                out.writeByte(response.kind() == Buffer.Kind.DATA ? 0 : 1);
                out.writeByte(response.flags());
                out.writeBytes(
                        response.data(),
                        response.data().readerIndex(),
                        response.data().readableBytes());
            }
            case Frame.AddCredit credit -> {
                uuid(out, credit.channel());
                varint(out, credit.credit());
            }
            case Frame.CancelPartition cancel -> uuid(out, cancel.channel());
            case Frame.ErrorResponse error -> {
                UUID channel = error.channel();
                out.writeByte(channel == null ? 0 : 1);
                if (channel != null) {
                    uuid(out, channel);
                }
                string(out, error.code());
                string(out, error.message());
            }
            case Frame.CloseRequest close -> {}
            case Frame.Ping ping -> {}
            case Frame.Pong pong -> {}
        }
        out.setInt(start + 4, out.writerIndex() - start - Frame.HEADER);
    }

    static Frame read(int type, ByteBuf in) {
        return switch (type) {
            case Frame.HELLO -> new Frame.Hello(string(in), string(in), string(in));
            case Frame.PARTITION_REQUEST -> {
                UUID channel = uuid(in);
                ResultPartitionId id =
                        new ResultPartitionId(string(in), string(in), varint(in), varint(in), varint(in));
                yield new Frame.PartitionRequest(channel, id, varint(in), varint(in));
            }
            case Frame.BUFFER_RESPONSE -> {
                UUID channel = uuid(in);
                long sequence = varlong(in);
                int backlog = varint(in);
                int kind = in.readUnsignedByte();
                if (kind > 1) {
                    throw new IllegalArgumentException("buffer type " + kind);
                }
                int flags = in.readUnsignedByte();
                if (flags != 0) {
                    throw new IllegalArgumentException("buffer flags " + flags + " in version 1");
                }
                ByteBuf data = in.readRetainedSlice(in.readableBytes());
                yield new Frame.BufferResponse(
                        channel, sequence, backlog, kind == 0 ? Buffer.Kind.DATA : Buffer.Kind.EVENT, flags, data);
            }
            case Frame.ADD_CREDIT -> new Frame.AddCredit(uuid(in), varint(in));
            case Frame.CANCEL_PARTITION -> new Frame.CancelPartition(uuid(in));
            case Frame.CLOSE_REQUEST -> new Frame.CloseRequest();
            case Frame.ERROR_RESPONSE -> {
                int has = in.readUnsignedByte();
                if (has > 1) {
                    throw new IllegalArgumentException("hasChannel " + has);
                }
                UUID channel = has == 1 ? uuid(in) : null;
                yield new Frame.ErrorResponse(channel, string(in), string(in));
            }
            case Frame.PING -> new Frame.Ping();
            case Frame.PONG -> new Frame.Pong();
            default -> throw new IllegalArgumentException("unknown frame type 0x" + Integer.toHexString(type));
        };
    }

    static void varint(ByteBuf out, long value) {
        long rest = value;
        while ((rest & ~0x7FL) != 0) {
            out.writeByte((int) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        out.writeByte((int) rest);
    }

    static long varlong(ByteBuf in) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte b = in.readByte();
            value |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint longer than 10 bytes");
    }

    static int varint(ByteBuf in) {
        long value = varlong(in);
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("varint " + Long.toUnsignedString(value) + " is not an int");
        }
        return (int) value;
    }

    static void string(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(out, bytes.length);
        out.writeBytes(bytes);
    }

    static String string(ByteBuf in) {
        int length = varint(in);
        if (length > in.readableBytes()) {
            throw new IllegalArgumentException("a string of " + length + " bytes in " + in.readableBytes());
        }
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    static void uuid(ByteBuf out, UUID value) {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    static UUID uuid(ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }
}
