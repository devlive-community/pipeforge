/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.dist;

import java.nio.file.Path;
import org.devlive.pipeforge.dist.config.Configuration;
import org.devlive.pipeforge.dist.config.Settings;
import org.devlive.pipeforge.engine.rpc.grpc.ClusterToken;
import org.devlive.pipeforge.engine.rpc.grpc.GrpcTransport;

/** The token and the TLS of the RPC between the processes, from the settings. */
final class Security {

    private Security() {}

    static GrpcTransport.Security of(Configuration config, Path home) {
        ClusterToken token = new ClusterToken(config.string(Settings.CLUSTER_TOKEN));
        if (!config.bool(Settings.TLS_ENABLED)) {
            return new GrpcTransport.Security(token, null);
        }
        String keyStore =
                config.optional(Settings.TLS_KEY_STORE).orElseThrow(() -> missing(Settings.TLS_KEY_STORE.key()));
        String trustStore =
                config.optional(Settings.TLS_TRUST_STORE).orElseThrow(() -> missing(Settings.TLS_TRUST_STORE.key()));
        return new GrpcTransport.Security(
                token,
                new GrpcTransport.Tls(
                        home.resolve(keyStore),
                        config.string(Settings.TLS_KEY_STORE_PASSWORD),
                        home.resolve(trustStore),
                        config.string(Settings.TLS_TRUST_STORE_PASSWORD)));
    }

    private static RuntimeException missing(String key) {
        return org.devlive.pipeforge.common.error.PipeForgeException.of(
                        org.devlive.pipeforge.common.error.ErrorCode.SETTING_INVALID)
                .param("key", key)
                .param("source", "the configuration")
                .param("reason", "it is required when security.tls.enabled is true")
                .build();
    }
}
