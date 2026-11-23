/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Arrays;

/**
 * Where the state of one node of a task is in a checkpoint: inside {@code _metadata} when it is
 * small, otherwise in a file of the checkpoint's directory (protocol specification, section 5).
 *
 * @param operatorKind {@code source}, {@code transform}, {@code sink} or {@code committer}
 * @param serializerVersion the version of the plugin serializer that wrote the state
 * @param size the size of the state in bytes
 * @param crc32c the CRC32C of the state's bytes, as an unsigned 32-bit value
 */
public record StateHandle(
        String nodeId, String operatorKind, int serializerVersion, Location location, long size, int crc32c) {

    /** Inside {@code _metadata}, or a file relative to the checkpoint's directory. */
    public sealed interface Location {}

    // the array is copied in and out, and equals and hashCode compare its content
    @SuppressWarnings("ArrayRecordComponent")
    public record Inline(byte[] bytes) implements Location {
        public Inline {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Inline inline && Arrays.equals(inline.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Inline[" + bytes.length + " bytes]";
        }
    }

    public record File(String path) implements Location {}
}
