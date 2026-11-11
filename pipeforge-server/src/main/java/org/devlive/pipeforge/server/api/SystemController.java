/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.devlive.pipeforge.api.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Version information. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "system", description = "Server information")
public class SystemController {

    private final VersionInfo version;

    public SystemController() {
        Properties properties = new Properties();
        try (InputStream in = SystemController.class.getResourceAsStream("/pipeforge-server.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.version = new VersionInfo(
                properties.getProperty("version", "unknown"),
                ApiVersion.current(),
                Runtime.version().toString());
    }

    /**
     * The versions a client checks before it talks to the server.
     *
     * @param version the PipeForge version
     * @param connectorApi the connector API version plugins are built against
     * @param java the Java runtime of the server
     */
    public record VersionInfo(String version, String connectorApi, String java) {}

    @GetMapping("/version")
    @Operation(
            operationId = "getVersion",
            summary = "Get the server version",
            description = "The PipeForge version, the connector API version and the Java runtime.",
            extensions =
                    @Extension(
                            name = Docs.ZH,
                            properties = {
                                @ExtensionProperty(name = "summary", value = "获取服务版本"),
                                @ExtensionProperty(name = "description", value = "PipeForge 版本、连接器 API 版本和 Java 运行时。")
                            }))
    public VersionInfo version() {
        return version;
    }
}
