/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.devlive.pipeforge.api.annotation.Stable;

/** The version of this connector API; plugins declare the major version they were built for. */
@Stable
public final class ApiVersion {

    private static final String VERSION = load();

    private ApiVersion() {}

    private static String load() {
        Properties properties = new Properties();
        try (InputStream in = ApiVersion.class.getResourceAsStream("/META-INF/pipeforge/api.properties")) {
            if (in == null) {
                throw new IllegalStateException("META-INF/pipeforge/api.properties is missing from pipeforge-api");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }

    /** The full version, e.g. {@code 0.1.0}. */
    public static String current() {
        return VERSION;
    }

    /** The major version, e.g. 0 for 0.1.0. */
    public static int major() {
        return Integer.parseInt(VERSION.split("\\.", 2)[0]);
    }

    /** The form used in plugin.yaml, e.g. {@code 0.x}. */
    public static String descriptorForm() {
        return major() + ".x";
    }
}
