/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The PipeForge server: the REST API under {@code /api/v1} and the Web UI. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PipeForgeServer {

    public static void main(String[] args) {
        SpringApplication.run(PipeForgeServer.class, args);
    }
}
