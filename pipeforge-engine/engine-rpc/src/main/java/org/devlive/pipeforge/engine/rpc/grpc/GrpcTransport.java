/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.rpc.grpc;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BindableService;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.devlive.pipeforge.engine.rpc.proto.Ack;
import org.jspecify.annotations.Nullable;

/**
 * Servers and channels of the control RPC: every call carries the cluster token, and with TLS the
 * connections are encrypted and the server is authenticated by the trust store of the clients.
 */
public final class GrpcTransport {

    private GrpcTransport() {}

    /**
     * TLS of the control RPC, with PKCS#12 stores.
     *
     * @param keyStore the certificate and private key of a server
     * @param trustStore the certificates a client trusts
     */
    public record Tls(Path keyStore, String keyStorePassword, Path trustStore, String trustStorePassword) {}

    /** How processes of a cluster find each other trustworthy: the token, and optionally TLS. */
    public record Security(ClusterToken token, @Nullable Tls tls) {}

    /**
     * Starts a server on {@code port} (0 picks a free one) with {@code services}.
     *
     * @throws IOException if the port cannot be bound or a store cannot be read
     */
    public static Server serve(int port, Security security, List<BindableService> services) throws IOException {
        ServerCredentials credentials = security.tls() == null
                ? InsecureServerCredentials.create()
                : TlsServerCredentials.newBuilder()
                        .keyManager(keyManagers(security.tls()).getKeyManagers())
                        .build();
        var builder = Grpc.newServerBuilderForPort(port, credentials)
                .intercept(security.token().server());
        services.forEach(builder::addService);
        return builder.build().start();
    }

    /**
     * A channel to {@code target} ({@code host:port}).
     *
     * @throws IOException if the trust store cannot be read
     */
    public static ManagedChannel channel(String target, Security security) throws IOException {
        ChannelCredentials credentials = security.tls() == null
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.newBuilder()
                        .trustManager(trustManagers(security.tls()).getTrustManagers())
                        .build();
        return Grpc.newChannelBuilder(target, credentials)
                .intercept(security.token().client())
                .build();
    }

    private static KeyManagerFactory keyManagers(Tls tls) throws IOException {
        try {
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(
                    load(tls.keyStore(), tls.keyStorePassword()),
                    tls.keyStorePassword().toCharArray());
            return factory;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot use the key store " + tls.keyStore() + ": " + e.getMessage(), e);
        }
    }

    private static TrustManagerFactory trustManagers(Tls tls) throws IOException {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(load(tls.trustStore(), tls.trustStorePassword()));
            return factory;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot use the trust store " + tls.trustStore() + ": " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path file, String password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    // ----------------------------------------------------------------------------- call helpers

    /** {@code future} as a {@link CompletableFuture}. */
    static <T> CompletableFuture<T> completable(ListenableFuture<T> future) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Futures.addCallback(
                future,
                new FutureCallback<T>() {
                    @Override
                    public void onSuccess(T value) {
                        result.complete(value);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        result.completeExceptionally(error);
                    }
                },
                MoreExecutors.directExecutor());
        return result;
    }

    /** Answers a unary call with an {@link Ack} once {@code work} is done, or with its error. */
    @SuppressWarnings("FutureReturnValueIgnored") // the callback completes another future
    static void respond(StreamObserver<Ack> observer, CompletableFuture<?> work) {
        work.whenComplete((ignored, error) -> {
            if (error != null) {
                observer.onError(RpcErrors.toStatus(error));
            } else {
                observer.onNext(Ack.getDefaultInstance());
                observer.onCompleted();
            }
        });
    }
}
