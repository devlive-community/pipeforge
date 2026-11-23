/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.engine.checkpoint.s3;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorage;
import org.devlive.pipeforge.engine.api.checkpoint.CheckpointStorages;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Checkpoints in an S3 bucket under a prefix. A single put is atomic in S3, so files need no
 * renaming; a checkpoint is complete once its {@code _COMPLETED} marker exists, written after
 * {@code _metadata} (protocol specification, section 5.3). Errors of S3 become {@link IOException}s
 * with S3's message.
 */
public final class S3CheckpointStorage implements CheckpointStorage {

    /** The most keys one DeleteObjects request takes. */
    static final int DELETE_BATCH = 1000;

    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final URI location;

    /**
     * @param prefix the key prefix of the storage's root, empty or ending with {@code /}
     */
    S3CheckpointStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.location = URI.create("s3://" + bucket + "/" + prefix);
    }

    @Override
    public URI location() {
        return location;
    }

    private String key(String path) {
        if (path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("'" + path + "' is outside the checkpoint storage " + location);
        }
        return prefix + path;
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        call(() -> client.putObject(b -> b.bucket(bucket).key(key(path)), RequestBody.fromBytes(data)));
    }

    @Override
    public byte[] read(String path) throws IOException {
        try {
            return client.getObjectAsBytes(b -> b.bucket(bucket).key(key(path))).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(location + path);
        } catch (SdkException e) {
            throw failure(e);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        try {
            client.headObject(b -> b.bucket(bucket).key(key(path)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw failure(e);
        } catch (SdkException e) {
            throw failure(e);
        }
    }

    @Override
    public List<Entry> list(String directory) throws IOException {
        List<Entry> entries = new ArrayList<>();
        String start = key(directory);
        try {
            String token = null;
            do {
                ListObjectsV2Request.Builder request =
                        ListObjectsV2Request.builder().bucket(bucket).prefix(start);
                if (token != null) {
                    request.continuationToken(token);
                }
                ListObjectsV2Response page = client.listObjectsV2(request.build());
                for (S3Object object : page.contents()) {
                    entries.add(
                            new Entry(object.key().substring(prefix.length()), object.size(), object.lastModified()));
                }
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw failure(e);
        }
        return entries;
    }

    @Override
    public void delete(String path) throws IOException {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("The root of the checkpoint storage is not deleted");
        }
        List<ObjectIdentifier> keys = new ArrayList<>();
        if (exists(path)) {
            keys.add(ObjectIdentifier.builder().key(key(path)).build());
        }
        if (path.endsWith("/")) {
            for (Entry entry : list(path)) {
                keys.add(ObjectIdentifier.builder().key(key(entry.path())).build());
            }
        }
        for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
            List<ObjectIdentifier> batch = keys.subList(from, Math.min(keys.size(), from + DELETE_BATCH));
            call(() -> client.deleteObjects(b -> b.bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build())));
        }
    }

    @Override
    public void complete(String directory, byte[] metadata) throws IOException {
        write(directory + CheckpointStorages.METADATA, metadata);
        write(directory + CheckpointStorages.COMPLETED, new byte[0]);
    }

    @Override
    public boolean isComplete(String directory) throws IOException {
        return exists(directory + CheckpointStorages.COMPLETED);
    }

    @Override
    public void close() {
        client.close();
    }

    private void call(Runnable request) throws IOException {
        try {
            request.run();
        } catch (SdkException e) {
            throw failure(e);
        }
    }

    private IOException failure(SdkException e) {
        return new IOException(location + ": " + e.getMessage(), e);
    }
}
