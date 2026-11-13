/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of the engine protocol, {@code major.minor}. Two sides with different major versions
 * do not talk to each other; with the same major version they use the lower minor version.
 *
 * @param major changes only with incompatible changes, which need a new package ({@code
 *     pipeforge.rpc.v2})
 * @param minor grows with compatible additions: new fields, RPCs, enum values and event types
 */
public record ProtocolVersion(int major, int minor) {

    /** The version this build speaks. */
    public static final ProtocolVersion CURRENT = new ProtocolVersion(1, 0);

    private static final Pattern FORMAT = Pattern.compile("(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})");

    public ProtocolVersion {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("Protocol version must be at least 1.0, not " + major + "." + minor);
        }
    }

    /**
     * Parses {@code major.minor}, e.g. {@code 1.0}.
     *
     * @throws IllegalArgumentException if the text is not a version
     */
    public static ProtocolVersion parse(String text) {
        Matcher matcher = FORMAT.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a protocol version such as 1.0");
        }
        return new ProtocolVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /** The version both sides use, or empty when the major versions differ. */
    public Optional<ProtocolVersion> negotiate(ProtocolVersion other) {
        if (other.major != major) {
            return Optional.empty();
        }
        return Optional.of(new ProtocolVersion(major, Math.min(minor, other.minor)));
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
