/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.io.IOException;
import java.util.Arrays;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;

/**
 * A value written by a plugin's {@link VersionedSerializer}, as it travels between the job master
 * and the tasks: splits, committables and state. The in-process implementation passes these bytes
 * as they would go over the network, so that a broken serializer fails in local runs too.
 */
public final class SerializedValue {

    private final int version;
    private final byte[] bytes;

    public SerializedValue(int version, byte[] bytes) {
        this.version = version;
        this.bytes = bytes.clone();
    }

    public static <T> SerializedValue of(VersionedSerializer<T> serializer, T value) throws IOException {
        return new SerializedValue(serializer.version(), serializer.serialize(value));
    }

    public <T> T deserialize(VersionedSerializer<T> serializer) throws IOException {
        return serializer.deserialize(version, bytes.clone());
    }

    public int version() {
        return version;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SerializedValue value && value.version == version && Arrays.equals(value.bytes, bytes);
    }

    @Override
    public int hashCode() {
        return 31 * version + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "SerializedValue[version=" + version + ", " + bytes.length + " bytes]";
    }
}
