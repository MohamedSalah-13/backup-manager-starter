package io.github.mohamedsalah13.backupmanager.storage.s3;

import io.github.mohamedsalah13.backupmanager.core.api.ProgressCallback;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores artifacts in Amazon S3, or in any S3-compatible service — MinIO, Backblaze B2,
 * Cloudflare R2, Wasabi — by pointing {@code endpoint} at it.
 *
 * <p>Anything above {@link #multipartThreshold} is uploaded in parts. A single {@code PUT}
 * of a multi-gigabyte artifact has to restart from zero if the connection drops, which on
 * a home or office link it eventually will.
 */
public class S3StorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    /** S3 requires every part except the last to be at least 5 MB. */
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final long DEFAULT_MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final long DEFAULT_PART_SIZE = 16L * 1024 * 1024;

    private final String name;
    private final String bucket;
    private final S3Client client;
    private final boolean ownsClient;
    private final String keyPrefix;
    private final StorageClass storageClass;
    private final ServerSideEncryption serverSideEncryption;
    private final boolean createBucketIfMissing;
    private final long multipartThreshold;
    private final long partSize;

    private S3StorageProvider(Builder b) {
        this.name = b.name;
        this.bucket = b.bucket;
        this.keyPrefix = normalisePrefix(b.keyPrefix);
        this.storageClass = b.storageClass;
        this.serverSideEncryption = b.serverSideEncryption;
        this.createBucketIfMissing = b.createBucketIfMissing;
        this.multipartThreshold = b.multipartThreshold;
        this.partSize = Math.max(MIN_PART_SIZE, b.partSize);
        this.ownsClient = b.client == null;
        this.client = b.client != null ? b.client : buildClient(b);
    }

    public static Builder builder(String bucket) {
        return new Builder(bucket);
    }

    private static S3Client buildClient(Builder b) {
        var builder = S3Client.builder();
        if (b.region != null) {
            builder.region(Region.of(b.region));
        }
        if (b.accessKey != null && b.secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(b.accessKey, b.secretKey)));
        } else {
            // Environment, profile, container and instance credentials, in that order.
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (b.endpoint != null) {
            builder.endpointOverride(URI.create(b.endpoint));
            // Non-AWS services almost never support virtual-host style addressing.
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }
        return builder.build();
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String cleaned = prefix.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "" : cleaned + "/";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void init() throws IOException {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            if (!createBucketIfMissing) {
                throw new BackupException.Configuration(
                        "S3 bucket '" + bucket + "' does not exist. Create it, or enable"
                                + " createBucketIfMissing on the provider.", e);
            }
            log.info("Creating S3 bucket {}", bucket);
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            throw new BackupException.Storage(name,
                    "Could not reach the S3 bucket '" + bucket + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String store(Path localFile, String remotePath, ProgressCallback progress) throws IOException {
        String key = keyPrefix + remotePath;
        long size = Files.size(localFile);

        if (size >= multipartThreshold) {
            uploadMultipart(localFile, key, size, progress);
        } else {
            uploadSingle(localFile, key, size, progress);
        }
        log.info("Uploaded s3://{}/{} ({} bytes)", bucket, key, size);
        return key;
    }

    private void uploadSingle(Path localFile, String key, long size, ProgressCallback progress)
            throws IOException {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength(size);
        if (storageClass != null) {
            request.storageClass(storageClass);
        }
        if (serverSideEncryption != null) {
            request.serverSideEncryption(serverSideEncryption);
        }
        try {
            client.putObject(request.build(), RequestBody.fromFile(localFile));
            progress.report(name, BackupPhase.UPLOADING, "Uploaded to S3", size, size);
        } catch (S3Exception e) {
            throw new IOException("S3 upload of " + key + " failed: " + e.getMessage(), e);
        }
    }

    private void uploadMultipart(Path localFile, String key, long size, ProgressCallback progress)
            throws IOException {
        CreateMultipartUploadRequest.Builder create = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key);
        if (storageClass != null) {
            create.storageClass(storageClass);
        }
        if (serverSideEncryption != null) {
            create.serverSideEncryption(serverSideEncryption);
        }

        CreateMultipartUploadResponse initiated = client.createMultipartUpload(create.build());
        String uploadId = initiated.uploadId();
        List<CompletedPart> parts = new ArrayList<>();

        try (InputStream in = Files.newInputStream(localFile)) {
            byte[] buffer = new byte[(int) partSize];
            int partNumber = 1;
            long uploaded = 0;

            while (true) {
                int filled = in.readNBytes(buffer, 0, buffer.length);
                if (filled == 0) {
                    break;
                }
                byte[] payload = filled == buffer.length
                        ? buffer
                        : java.util.Arrays.copyOf(buffer, filled);

                var response = client.uploadPart(UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) filled)
                                .build(),
                        RequestBody.fromBytes(payload));

                parts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(response.eTag())
                        .build());

                uploaded += filled;
                progress.report(name, BackupPhase.UPLOADING,
                        "Uploading part " + partNumber + " to S3", uploaded, size);
                partNumber++;
            }

            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());

        } catch (Exception e) {
            // An abandoned multipart upload keeps billing for its parts until it is aborted.
            abortQuietly(key, uploadId);
            throw new IOException("S3 multipart upload of " + key + " failed: " + e.getMessage(), e);
        }
    }

    private void abortQuietly(String key, String uploadId) {
        try {
            client.abortMultipartUpload(software.amazon.awssdk.services.s3.model
                    .AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
            log.debug("Aborted the incomplete multipart upload {}", uploadId);
        } catch (Exception e) {
            log.warn("Could not abort the multipart upload {}; it may continue to incur"
                    + " storage charges until the bucket lifecycle rule clears it: {}",
                    uploadId, e.getMessage());
        }
    }

    @Override
    public void fetch(String remotePath, Path localTarget, ProgressCallback progress) throws IOException {
        String key = absoluteKey(remotePath);
        Path parent = localTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var response = client.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build())) {
            long total = response.response().contentLength();
            try (OutputStream out = Files.newOutputStream(localTarget)) {
                byte[] buffer = new byte[1024 * 1024];
                long read = 0;
                int n;
                while ((n = response.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    read += n;
                    progress.report(name, BackupPhase.PREPARING, "Downloading from S3", read, total);
                }
            }
        } catch (NoSuchKeyException e) {
            throw new java.io.FileNotFoundException("s3://" + bucket + "/" + key + " does not exist");
        } catch (S3Exception e) {
            throw new IOException("S3 download of " + key + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(absoluteKey(remotePath)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new IOException("Could not check whether " + remotePath + " exists: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String remotePath) throws IOException {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(absoluteKey(remotePath)).build());
        } catch (S3Exception e) {
            throw new IOException("Could not delete " + remotePath + " from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RemoteEntry> list(String prefix) throws IOException {
        String searchPrefix = keyPrefix + (prefix != null ? prefix : "");
        List<RemoteEntry> entries = new ArrayList<>();
        String continuationToken = null;
        try {
            do {
                ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(searchPrefix)
                        .continuationToken(continuationToken)
                        .build());
                response.contents().forEach(object -> entries.add(RemoteEntry.file(
                        stripPrefix(object.key()), object.size(), object.lastModified())));
                // Results arrive in pages of 1000; without this loop a long history is truncated.
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (S3Exception e) {
            throw new IOException("Could not list the S3 bucket: " + e.getMessage(), e);
        }
        return entries;
    }

    /** Accepts both a path relative to the prefix and a key already carrying it. */
    private String absoluteKey(String remotePath) {
        if (remotePath == null) {
            return keyPrefix;
        }
        return remotePath.startsWith(keyPrefix) ? remotePath : keyPrefix + remotePath;
    }

    private String stripPrefix(String key) {
        return keyPrefix.isEmpty() || !key.startsWith(keyPrefix) ? key : key.substring(keyPrefix.length());
    }

    @Override
    public boolean isHealthy() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    @Override
    public String toString() {
        return "S3StorageProvider[" + name + " -> s3://" + bucket + "/" + keyPrefix + "]";
    }

    public static final class Builder {

        private final String bucket;
        private String name = "s3";
        private String region;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String keyPrefix = "";
        private StorageClass storageClass;
        private ServerSideEncryption serverSideEncryption = ServerSideEncryption.AES256;
        private boolean createBucketIfMissing;
        private long multipartThreshold = DEFAULT_MULTIPART_THRESHOLD;
        private long partSize = DEFAULT_PART_SIZE;
        private S3Client client;

        private Builder(String bucket) {
            if (bucket == null || bucket.isBlank()) {
                throw new BackupException.Configuration("An S3 bucket name is required");
            }
            this.bucket = bucket;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /** Points at an S3-compatible service, e.g. {@code https://play.min.io}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Explicit keys. Omit to use the AWS default credential chain. */
        public Builder credentials(String accessKey, String secretKey) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            return this;
        }

        /** Folder inside the bucket that every artifact goes under. */
        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        /**
         * Storage class for uploaded objects. {@code STANDARD_IA} or {@code GLACIER_IR}
         * cut the bill substantially for backups, which are written once and rarely read.
         */
        public Builder storageClass(StorageClass storageClass) {
            this.storageClass = storageClass;
            return this;
        }

        /** Server-side encryption. Pass {@code null} to disable; defaults to AES256. */
        public Builder serverSideEncryption(ServerSideEncryption sse) {
            this.serverSideEncryption = sse;
            return this;
        }

        public Builder createBucketIfMissing(boolean create) {
            this.createBucketIfMissing = create;
            return this;
        }

        /** Size above which uploads are split into parts. */
        public Builder multipartThreshold(long bytes) {
            this.multipartThreshold = bytes;
            return this;
        }

        /** Part size for multipart uploads; clamped up to the 5 MB minimum S3 enforces. */
        public Builder partSize(long bytes) {
            this.partSize = bytes;
            return this;
        }

        /** Supplies a pre-configured client; the caller keeps responsibility for closing it. */
        public Builder client(S3Client client) {
            this.client = client;
            return this;
        }

        public S3StorageProvider build() {
            return new S3StorageProvider(this);
        }
    }
}
