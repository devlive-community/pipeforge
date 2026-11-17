/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.server.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;
import org.devlive.pipeforge.server.config.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The connection to the master, from {@code pipeforge.master.*}. */
@Configuration(proxyBeanMethods = false)
public class MasterConfiguration {

    @Bean(destroyMethod = "close")
    MasterClient masterClient(ServerProperties properties) {
        ServerProperties.Master settings = properties.master();
        String address = settings.address();
        if (address == null || address.isBlank()) {
            return MasterClient.none();
        }
        try {
            return new GrpcMasterClient(GrpcTransport.channel(address, security(settings)), address);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    static GrpcTransport.Security security(ServerProperties.Master settings) {
        if (settings.token().isBlank()) {
            throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                    .param("key", "pipeforge.master.token")
                    .param("source", "the server configuration")
                    .param("reason", "the cluster token (cluster.token of the cluster) is required with a master")
                    .build();
        }
        ClusterToken token = new ClusterToken(settings.token());
        ServerProperties.Tls tls = settings.tls();
        if (!tls.enabled()) {
            return new GrpcTransport.Security(token, null);
        }
        return new GrpcTransport.Security(
                token,
                new GrpcTransport.Tls(
                        required(tls.keyStore(), "pipeforge.master.tls.key-store"),
                        tls.keyStorePassword(),
                        required(tls.trustStore(), "pipeforge.master.tls.trust-store"),
                        tls.trustStorePassword()));
    }

    private static Path required(Path path, String key) {
        if (path == null) {
            throw PipeForgeException.of(ErrorCode.SETTING_INVALID)
                    .param("key", key)
                    .param("source", "the server configuration")
                    .param("reason", "it is required when pipeforge.master.tls.enabled is true")
                    .build();
        }
        return path;
    }
}
