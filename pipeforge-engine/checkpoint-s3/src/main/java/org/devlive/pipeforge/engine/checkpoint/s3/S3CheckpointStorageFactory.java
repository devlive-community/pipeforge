/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.checkpoint.s3;

import java.net.URI;
import java.util.Map;
import org.devlive.pipeforge.common.error.ErrorCode;
import org.devlive.pipeforge.common.error.PipeForgeException;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorageFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Opens {@code s3://bucket/prefix}. Settings (the {@code checkpoint.s3.*} settings of the process):
 *
 * <ul>
 *   <li>{@code s3.endpoint}: for S3-compatible storage such as OSS, COS or LocalStack; AWS when empty
 *   <li>{@code s3.region}: {@code us-east-1} when empty
 *   <li>{@code s3.path-style}: {@code true} addresses the bucket in the path, as most S3-compatible
 *       storage wants
 *   <li>{@code s3.access-key}, {@code s3.secret-key}: when empty, the AWS default credential chain
 *       (environment, profile, instance role)
 * </ul>
 */
public final class S3CheckpointStorageFactory implements CheckpointStorageFactory {

    /** Removes {@code Expect: 100-continue} from requests. */
    static final class NoExpectContinue implements ExecutionInterceptor {
        @Override
        public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
            return context.httpRequest().toBuilder().removeHeader("Expect").build();
        }
    }

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public CheckpointStorage open(URI location, Map<String, String> settings) {
        String bucket = location.getHost();
        if (bucket == null || bucket.isBlank()) {
            throw PipeForgeException.of(ErrorCode.STORAGE_UNAVAILABLE)
                    .param("location", location.toString())
                    .param("reason", "an s3 location needs a bucket: s3://<bucket>/<prefix>")
                    .build();
        }
        String path = location.getPath() == null ? "" : location.getPath().replaceAll("^/+", "");
        String prefix = path.isEmpty() || path.endsWith("/") ? path : path + "/";
        String endpoint = settings.getOrDefault("s3.endpoint", "").strip();
        String region = settings.getOrDefault("s3.region", "").strip();
        String accessKey = settings.getOrDefault("s3.access-key", "").strip();
        String secretKey = settings.getOrDefault("s3.secret-key", "").strip();
        AwsCredentialsProvider credentials = accessKey.isEmpty()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var builder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder().expectContinueEnabled(false))
                // the S3 client asks for "100 Continue" before every body it puts; checkpoint files are
                // small and written whole, and some S3-compatible storage never answers, so that each
                // put waits for the client's continue timeout
                .overrideConfiguration(o -> o.addExecutionInterceptor(new NoExpectContinue()))
                .region(Region.of(region.isEmpty() ? "us-east-1" : region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(Boolean.parseBoolean(settings.getOrDefault("s3.path-style", "false")))
                        .build());
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return new S3CheckpointStorage(builder.build(), bucket, prefix);
    }
}
