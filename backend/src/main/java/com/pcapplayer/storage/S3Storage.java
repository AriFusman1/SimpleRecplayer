package com.pcapplayer.storage;

import com.pcapplayer.Config;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Thin wrapper around the AWS S3 client: put / get / list / delete objects in one bucket.
 * Works with AWS S3 and MinIO. Every method BLOCKS, so callers must run it in executeBlocking.
 */
public final class S3Storage {

    private final S3Client s3;
    private final String bucket;
    private final String region;

    public S3Storage(Config config) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create()) // small JDK-based HTTP client, no extra libraries
                .region(Region.of(config.s3Region()))
                .forcePathStyle(config.s3PathStyle());        // MinIO needs path-style: http://host/bucket/key
        if (!config.s3Endpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.s3Endpoint())); // empty = real AWS
        }
        if (!config.s3AccessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.s3AccessKey(), config.s3SecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create()); // env vars, IAM role, ...
        }
        this.s3 = builder.build();
        this.bucket = config.s3Bucket();
        this.region = config.s3Region();
    }

    /** Creates the bucket if it does not exist yet. */
    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            if (region.equals("us-east-1")) {
                s3.createBucket(b -> b.bucket(bucket)); // us-east-1 must not send a location
            } else {
                s3.createBucket(b -> b.bucket(bucket).createBucketConfiguration(
                        CreateBucketConfiguration.builder().locationConstraint(region).build()));
            }
        }
    }

    public void putBytes(String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }

    public void putFile(String key, Path file) {
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromFile(file));
    }

    /** Returns the object's bytes, or null if it does not exist. */
    public byte[] getBytes(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /** Streams the object into a local file (big pcap files never sit fully in memory here). */
    public void downloadToFile(String key, Path target) throws java.io.IOException {
        try (var in = s3.getObject(b -> b.bucket(bucket).key(key))) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** All keys that start with the prefix, e.g. "records/". */
    public List<String> listKeys(String prefix) {
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))
                .contents().stream()
                .map(S3Object::key)
                .toList();
    }

    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
