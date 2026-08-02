package io.github.mohamedsalah13.backupmanager.storage.dropbox;

import io.github.mohamedsalah13.backupmanager.core.api.ProgressCallback;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.UploadSessionCursor;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.users.SpaceUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores artifacts in Dropbox.
 *
 * <p>Dropbox caps a simple upload at 150 MB, so anything larger goes through an upload
 * session that appends fixed-size chunks. Backups routinely cross that line, which makes
 * the session path the normal case rather than an exotic one.
 *
 * <p>Prefer a refresh token over a raw access token: short-lived access tokens expire after
 * four hours, which is exactly long enough to work in testing and fail overnight.
 */
public class DropboxStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(DropboxStorageProvider.class);

    /** Dropbox rejects a single upload above 150 MB. */
    private static final long SIMPLE_UPLOAD_LIMIT = 140L * 1024 * 1024;
    /** Must be a multiple of 4 MB per the API contract. */
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;

    private final String name;
    private final DbxClientV2 client;
    private final String rootPath;

    private DropboxStorageProvider(Builder b) {
        this.name = b.name;
        this.rootPath = normaliseFolder(b.rootPath);
        this.client = b.client != null ? b.client : buildClient(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static DbxClientV2 buildClient(Builder b) {
        DbxRequestConfig config = DbxRequestConfig.newBuilder(b.applicationName).build();
        if (b.refreshToken != null && b.appKey != null && b.appSecret != null) {
            var credential = new com.dropbox.core.oauth.DbxCredential(
                    b.accessToken != null ? b.accessToken : "",
                    // Force a refresh on first use rather than trusting a stale token.
                    -1L, b.refreshToken, b.appKey, b.appSecret);
            return new DbxClientV2(config, credential);
        }
        if (b.accessToken == null) {
            throw new BackupException.Configuration(
                    "Dropbox needs either an access token or a refresh token with the app key and secret");
        }
        return new DbxClientV2(config, b.accessToken);
    }

    /** Dropbox paths start with a slash and never end with one; the root is the empty string. */
    private static String normaliseFolder(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String cleaned = path.replace('\\', '/');
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void init() throws IOException {
        try {
            client.users().getCurrentAccount();
        } catch (DbxException e) {
            throw new BackupException.Storage(name,
                    "Could not authenticate with Dropbox: " + e.getMessage(), e);
        }
    }

    @Override
    public String store(Path localFile, String remotePath, ProgressCallback progress) throws IOException {
        String target = absolutePath(remotePath);
        long size = Files.size(localFile);

        try (InputStream in = Files.newInputStream(localFile)) {
            FileMetadata metadata = size > SIMPLE_UPLOAD_LIMIT
                    ? uploadInSession(in, target, size, progress)
                    : uploadDirectly(in, target, size, progress);
            log.info("Uploaded {} to Dropbox ({} bytes)", metadata.getPathDisplay(), metadata.getSize());
            return metadata.getPathLower();
        } catch (DbxException e) {
            throw new IOException("Dropbox upload of " + target + " failed: " + e.getMessage(), e);
        }
    }

    private FileMetadata uploadDirectly(InputStream in, String target, long size,
                                        ProgressCallback progress) throws IOException, DbxException {
        FileMetadata metadata = client.files().uploadBuilder(target)
                .withMode(WriteMode.OVERWRITE)
                .withAutorename(false)
                .withMute(true)
                .uploadAndFinish(in);
        progress.report(name, BackupPhase.UPLOADING, "Uploaded to Dropbox", size, size);
        return metadata;
    }

    private FileMetadata uploadInSession(InputStream in, String target, long size,
                                         ProgressCallback progress) throws IOException, DbxException {
        byte[] buffer = new byte[CHUNK_SIZE];

        int first = in.readNBytes(buffer, 0, buffer.length);
        String sessionId = client.files().uploadSessionStart()
                .uploadAndFinish(new java.io.ByteArrayInputStream(buffer, 0, first), first)
                .getSessionId();

        long uploaded = first;
        progress.report(name, BackupPhase.UPLOADING, "Uploading to Dropbox", uploaded, size);

        while (true) {
            int read = in.readNBytes(buffer, 0, buffer.length);
            if (read == 0) {
                break;
            }
            UploadSessionCursor cursor = new UploadSessionCursor(sessionId, uploaded);
            if (read < buffer.length) {
                // Short read means the last chunk; finish the session with it.
                var commit = com.dropbox.core.v2.files.CommitInfo.newBuilder(target)
                        .withMode(WriteMode.OVERWRITE)
                        .withAutorename(false)
                        .withMute(true)
                        .build();
                FileMetadata metadata = client.files().uploadSessionFinish(cursor, commit)
                        .uploadAndFinish(new java.io.ByteArrayInputStream(buffer, 0, read), read);
                progress.report(name, BackupPhase.UPLOADING, "Uploaded to Dropbox", size, size);
                return metadata;
            }
            client.files().uploadSessionAppendV2(cursor)
                    .uploadAndFinish(new java.io.ByteArrayInputStream(buffer, 0, read), read);
            uploaded += read;
            progress.report(name, BackupPhase.UPLOADING, "Uploading to Dropbox", uploaded, size);
        }

        // The payload divided exactly into chunks, so close the session with an empty commit.
        UploadSessionCursor cursor = new UploadSessionCursor(sessionId, uploaded);
        var commit = com.dropbox.core.v2.files.CommitInfo.newBuilder(target)
                .withMode(WriteMode.OVERWRITE)
                .withAutorename(false)
                .withMute(true)
                .build();
        FileMetadata metadata = client.files().uploadSessionFinish(cursor, commit)
                .uploadAndFinish(new java.io.ByteArrayInputStream(new byte[0]), 0);
        progress.report(name, BackupPhase.UPLOADING, "Uploaded to Dropbox", size, size);
        return metadata;
    }

    @Override
    public void fetch(String remotePath, Path localTarget, ProgressCallback progress) throws IOException {
        Path parent = localTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(localTarget)) {
            client.files().download(absolutePath(remotePath)).download(out);
            progress.report(name, BackupPhase.PREPARING, "Downloaded from Dropbox");
        } catch (DbxException e) {
            throw new IOException("Dropbox download of " + remotePath + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        try {
            client.files().getMetadata(absolutePath(remotePath));
            return true;
        } catch (GetMetadataErrorException e) {
            return false;
        } catch (DbxException e) {
            throw new IOException("Could not check whether " + remotePath + " exists: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String remotePath) throws IOException {
        try {
            client.files().deleteV2(absolutePath(remotePath));
        } catch (GetMetadataErrorException e) {
            log.debug("Nothing to delete at {}", remotePath);
        } catch (DbxException e) {
            throw new IOException("Could not delete " + remotePath + " from Dropbox: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RemoteEntry> list(String prefix) throws IOException {
        String folder = prefix == null || prefix.isBlank() ? rootPath : absolutePath(prefix);
        List<RemoteEntry> entries = new ArrayList<>();
        try {
            ListFolderResult result = client.files().listFolder(folder);
            while (true) {
                for (Metadata metadata : result.getEntries()) {
                    if (metadata instanceof FileMetadata file) {
                        entries.add(RemoteEntry.file(
                                file.getPathLower(), file.getSize(),
                                file.getServerModified() != null
                                        ? file.getServerModified().toInstant() : null));
                    } else if (metadata instanceof FolderMetadata folderMetadata) {
                        entries.add(RemoteEntry.directory(folderMetadata.getPathLower()));
                    }
                }
                if (!result.getHasMore()) {
                    break;
                }
                // Long histories arrive in pages; without this the listing is silently short.
                result = client.files().listFolderContinue(result.getCursor());
            }
        } catch (DbxException e) {
            // An empty backup folder that does not exist yet is not an error.
            if (e.getMessage() != null && e.getMessage().contains("not_found")) {
                return List.of();
            }
            throw new IOException("Could not list the Dropbox folder " + folder + ": " + e.getMessage(), e);
        }
        return entries;
    }

    @Override
    public long availableSpace() {
        try {
            SpaceUsage usage = client.users().getSpaceUsage();
            if (usage.getAllocation() != null && usage.getAllocation().isIndividual()) {
                return usage.getAllocation().getIndividualValue().getAllocated() - usage.getUsed();
            }
            return -1L;
        } catch (DbxException e) {
            log.debug("Could not read the Dropbox quota: {}", e.getMessage());
            return -1L;
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            client.users().getCurrentAccount();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String absolutePath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return rootPath;
        }
        String cleaned = remotePath.replace('\\', '/');
        if (cleaned.startsWith("/")) {
            // Already absolute, as a path returned by store() would be.
            return cleaned;
        }
        return rootPath + "/" + cleaned;
    }

    @Override
    public String toString() {
        return "DropboxStorageProvider[" + name + " -> " + (rootPath.isEmpty() ? "/" : rootPath) + "]";
    }

    public static final class Builder {

        private String name = "dropbox";
        private String applicationName = "BackupManager";
        private String accessToken;
        private String refreshToken;
        private String appKey;
        private String appSecret;
        private String rootPath = "/backups";
        private DbxClientV2 client;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        /** Short-lived token. Fine for a trial; it expires after four hours. */
        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        /** Long-lived credentials — what a scheduled backup needs. */
        public Builder refreshToken(String refreshToken, String appKey, String appSecret) {
            this.refreshToken = refreshToken;
            this.appKey = appKey;
            this.appSecret = appSecret;
            return this;
        }

        /** Folder inside the app's Dropbox space; defaults to {@code /backups}. */
        public Builder rootPath(String rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        public Builder client(DbxClientV2 client) {
            this.client = client;
            return this;
        }

        public DropboxStorageProvider build() {
            return new DropboxStorageProvider(this);
        }
    }
}
