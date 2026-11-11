/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.serialization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.devlive.pipeforge.api.annotation.Stable;

/** UTF-8 serializer for string values, e.g. splits identified by a name. Version 1. */
@Stable
public final class StringSerializer implements VersionedSerializer<String> {

    public static final StringSerializer INSTANCE = new StringSerializer();

    private StringSerializer() {}

    @Override
    public int version() {
        return 1;
    }

    @Override
    public byte[] serialize(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String deserialize(int version, byte[] bytes) throws IOException {
        if (version != 1) {
            throw new IOException("Unknown version " + version);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
