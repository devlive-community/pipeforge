/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.checkpoint.proto.CheckpointMetadata;

/**
 * The files of a checkpoint (protocol specification, section 5). Both kinds have the same
 * envelope: magic, format version, body length, body, CRC32C of the body, integers big-endian.
 *
 * <ul>
 *   <li>{@code _metadata}: magic "PFCP", the body is a {@link CheckpointMetadata}.
 *   <li>a state file: magic "PFST", the body is the checkpoint ID (8 bytes), the node ID (varint
 *       length and UTF-8) and then the plugin serializer's bytes. The ID and the node are checked
 *       when the file is read, so a file copied to the wrong place is not taken for another.
 * </ul>
 *
 * A damaged file is {@link ErrorCode#STATE_CORRUPTED}; a newer format than this engine reads is
 * {@link ErrorCode#STATE_INCOMPATIBLE}.
 */
public final class CheckpointFiles {

    /** "PFCP". */
    public static final int METADATA_MAGIC = 0x50464350;

    /** "PFST". */
    public static final int STATE_MAGIC = 0x50465354;

    /** The format this engine writes; it reads every version up to it. */
    public static final int FORMAT_VERSION = 1;

    /** The name of the metadata file in a checkpoint's directory. */
    public static final String METADATA = "_metadata";

    /** The marker written after {@code _metadata} on storage that cannot rename atomically. */
    public static final String COMPLETED = "_COMPLETED";

    /** States up to this size go into {@code _metadata} by default (checkpoint.inline-threshold). */
    public static final int DEFAULT_INLINE_THRESHOLD = 16 * 1024;

    private static final int HEADER_BYTES = 12;
    private static final int TRAILER_BYTES = 4;

    private CheckpointFiles() {}

    /** The file name of a subtask's state: {@code <nodeId>-<subtask>.state}. */
    public static String stateFile(String nodeId, int subtask) {
        return nodeId + "-" + subtask + ".state";
    }

    /** The file name of an enumerator's state: {@code enum-<nodeId>.state}. */
    public static String enumeratorFile(String nodeId) {
        return "enum-" + nodeId + ".state";
    }

    /** The file name of a sink's committable: {@code commit-<nodeId>-<subtask>.state}. */
    public static String committableFile(String nodeId, int subtask) {
        return "commit-" + nodeId + "-" + subtask + ".state";
    }

    /** Whether a state of {@code size} bytes goes into {@code _metadata}, up to {@code threshold}. */
    public static boolean inline(long size, int threshold) {
        return size <= threshold;
    }

    // ------------------------------------------------------------------------------------- metadata

    /** {@code _metadata}; the same metadata gives the same bytes. */
    public static byte[] writeMetadata(CheckpointMetadata metadata) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(metadata.getSerializedSize());
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.useDeterministicSerialization();
            metadata.writeTo(out);
            out.flush();
            return wrap(METADATA_MAGIC, bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The metadata of {@code _metadata}.
     *
     * @param checkpoint the checkpoint, for the error message
     */
    public static CheckpointMetadata readMetadata(byte[] file, String checkpoint) {
        ByteBuffer body = unwrap(file, METADATA_MAGIC, METADATA, checkpoint);
        try {
            return CheckpointMetadata.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw corrupted(METADATA, checkpoint, "the body is not checkpoint metadata: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------- state

    /** A state file: the checkpoint and node it belongs to, then the serializer's bytes. */
    public static byte[] writeState(long checkpointId, String nodeId, byte[] state) {
        byte[] node = nodeId.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + node.length + state.length);
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeFixed64NoTag(Long.reverseBytes(checkpointId));
            out.writeUInt32NoTag(node.length);
            out.writeRawBytes(node);
            out.writeRawBytes(state);
            out.flush();
            return wrap(STATE_MAGIC, bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The serializer's bytes of a state file, checked to belong to {@code checkpointId} and
     * {@code nodeId}.
     *
     * @param name the file name, for the error message
     */
    public static byte[] readState(byte[] file, long checkpointId, String nodeId, String name) {
        String checkpoint = String.valueOf(checkpointId);
        ByteBuffer body = unwrap(file, STATE_MAGIC, name, checkpoint);
        if (body.remaining() < 9) {
            throw corrupted(name, checkpoint, "it is too short for its checkpoint and node");
        }
        long written = body.getLong();
        int length = varint(body, name, checkpoint);
        if (length < 0 || length > body.remaining()) {
            throw corrupted(name, checkpoint, "the node ID runs past the end of the file");
        }
        byte[] node = new byte[length];
        body.get(node);
        String writtenNode = new String(node, StandardCharsets.UTF_8);
        if (written != checkpointId || !writtenNode.equals(nodeId)) {
            throw corrupted(
                    name,
                    checkpoint,
                    "it belongs to node '" + writtenNode + "' of checkpoint " + written + ", not to node '" + nodeId
                            + "' of checkpoint " + checkpointId);
        }
        byte[] state = new byte[body.remaining()];
        body.get(state);
        return state;
    }

    // ------------------------------------------------------------------------------------ lists

    /**
     * The body of a list state, such as a reader's splits (section 5.2.1): the number of elements,
     * then each as its length and the element serializer's bytes, lengths as varints.
     */
    public static byte[] writeList(List<byte[]> elements) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            out.writeUInt32NoTag(elements.size());
            for (byte[] element : elements) {
                out.writeUInt32NoTag(element.length);
                out.writeRawBytes(element);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The elements of a list state.
     *
     * @param name the file or node, for the error message
     */
    public static List<byte[]> readList(byte[] body, String name, String checkpoint) {
        ByteBuffer in = ByteBuffer.wrap(body);
        int count = varint(in, name, checkpoint);
        if (count < 0 || count > body.length) {
            throw corrupted(name, checkpoint, "it says it holds " + count + " elements in " + body.length + " bytes");
        }
        List<byte[]> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = varint(in, name, checkpoint);
            if (length < 0 || length > in.remaining()) {
                throw corrupted(name, checkpoint, "element " + i + " runs past the end of the state");
            }
            byte[] element = new byte[length];
            in.get(element);
            elements.add(element);
        }
        if (in.hasRemaining()) {
            throw corrupted(name, checkpoint, in.remaining() + " bytes follow the last element");
        }
        return elements;
    }

    // ------------------------------------------------------------------------------------- envelope

    static byte[] wrap(int magic, byte[] body) {
        CRC32C crc = new CRC32C();
        crc.update(body);
        return ByteBuffer.allocate(HEADER_BYTES + body.length + TRAILER_BYTES)
                .putInt(magic)
                .putInt(FORMAT_VERSION)
                .putInt(body.length)
                .put(body)
                .putInt((int) crc.getValue())
                .array();
    }

    static ByteBuffer unwrap(byte[] bytes, int magic, String file, String checkpoint) {
        if (bytes.length < HEADER_BYTES + TRAILER_BYTES) {
            throw corrupted(file, checkpoint, "it has " + bytes.length + " bytes, fewer than the 16 of an empty file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int found = buffer.getInt();
        if (found != magic) {
            throw corrupted(
                    file,
                    checkpoint,
                    "it starts with " + String.format("0x%08x", found) + ", not " + String.format("0x%08x", magic));
        }
        int version = buffer.getInt();
        if (version < 1 || version > FORMAT_VERSION) {
            throw PipeForgeException.of(ErrorCode.STATE_INCOMPATIBLE)
                    .param("file", file)
                    .param("checkpoint", checkpoint)
                    .param("reason", "it has format version " + version + "; this engine reads 1 to " + FORMAT_VERSION)
                    .build();
        }
        int length = buffer.getInt();
        if (length < 0 || length != bytes.length - HEADER_BYTES - TRAILER_BYTES) {
            throw corrupted(
                    file,
                    checkpoint,
                    "its length says " + length + " bytes, it has " + (bytes.length - HEADER_BYTES - TRAILER_BYTES));
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, HEADER_BYTES, length);
        int expected =
                ByteBuffer.wrap(bytes, HEADER_BYTES + length, TRAILER_BYTES).getInt();
        if ((int) crc.getValue() != expected) {
            throw corrupted(file, checkpoint, "its checksum does not match");
        }
        return ByteBuffer.wrap(bytes, HEADER_BYTES, length).slice();
    }

    private static int varint(ByteBuffer buffer, String file, String checkpoint) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buffer.hasRemaining()) {
                throw corrupted(file, checkpoint, "a length runs past the end of the file");
            }
            byte b = buffer.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw corrupted(file, checkpoint, "a length is malformed");
    }

    private static PipeForgeException corrupted(String file, String checkpoint, String reason) {
        return PipeForgeException.of(ErrorCode.STATE_CORRUPTED)
                .param("file", file)
                .param("checkpoint", checkpoint)
                .param("reason", reason)
                .build();
    }
}
