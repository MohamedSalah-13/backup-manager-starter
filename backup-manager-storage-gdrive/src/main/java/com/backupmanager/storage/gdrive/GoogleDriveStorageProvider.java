package com.backupmanager.storage.gdrive;

import com.backupmanager.core.api.ProgressCallback;
import com.backupmanager.core.api.StorageProvider;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupPhase;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores artifacts in Google Drive.
 *
 * <p>Drive has no real directories — a "folder" is a file whose parent list contains it —
 * so paths like {@code sales/db-20260802.sql.gz} are mapped onto a chain of folder objects.
 * Resolved folder ids are cached, because walking the chain costs one API call per level
 * and Drive's per-minute quota is not generous.
 *
 * <p>Authenticate with a service account key. Note that a service account has its own
 * empty Drive with no storage quota of its own: share a folder from a real account with
 * the service account's address and pass that folder as {@code rootFolderId}, or the first
 * upload will fail with a quota error that reads like a billing problem.
 */
public class GoogleDriveStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveStorageProvider.class);

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String BINARY_MIME = "application/octet-stream";

    private final String name;
    private final Drive drive;
    private final String rootFolderId;
    private final Map<String, String> folderCache = new ConcurrentHashMap<>();

    private GoogleDriveStorageProvider(Builder b) {
        this.name = b.name;
        this.rootFolderId = b.rootFolderId != null ? b.rootFolderId : "root";
        this.drive = b.drive != null ? b.drive : buildDrive(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Drive buildDrive(Builder b) {
        try {
            GoogleCredentials credentials;
            if (b.credentials != null) {
                credentials = b.credentials;
            } else if (b.serviceAccountKey != null) {
                try (InputStream in = Files.newInputStream(b.serviceAccountKey)) {
                    credentials = GoogleCredentials.fromStream(in);
                }
            } else {
                // GOOGLE_APPLICATION_CREDENTIALS, gcloud config, or the metadata server.
                credentials = GoogleCredentials.getApplicationDefault();
            }
            credentials = credentials.createScoped(Collections.singleton(DriveScopes.DRIVE_FILE));

            HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);
            return new Drive.Builder(transport, GsonFactory.getDefaultInstance(), initializer)
                    .setApplicationName(b.applicationName)
                    .build();
        } catch (Exception e) {
            throw new BackupException.Configuration(
                    "Could not initialise the Google Drive client: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void init() throws IOException {
        try {
            drive.files().get(rootFolderId).setFields("id,name").execute();
        } catch (IOException e) {
            throw new BackupException.Storage(name,
                    "Could not open the Google Drive folder '" + rootFolderId + "'. Check that it"
                            + " exists and is shared with the service account.", e);
        }
    }

    @Override
    public String store(Path localFile, String remotePath, ProgressCallback progress) throws IOException {
        String[] split = splitPath(remotePath);
        String parentId = ensureFolderPath(split[0]);
        String fileName = split[1];

        long size = Files.size(localFile);
        progress.report(name, BackupPhase.UPLOADING, "Uploading to Google Drive", 0, size);

        File metadata = new File();
        metadata.setName(fileName);
        metadata.setParents(Collections.singletonList(parentId));

        FileContent content = new FileContent(BINARY_MIME, localFile.toFile());

        // Replace rather than duplicate: Drive happily keeps several files with one name
        // in the same folder, which would make a later lookup ambiguous.
        Optional<String> existing = findChildId(parentId, fileName);
        File uploaded;
        if (existing.isPresent()) {
            File update = new File();
            update.setName(fileName);
            uploaded = drive.files().update(existing.get(), update, content)
                    .setFields("id,name,size").execute();
        } else {
            uploaded = drive.files().create(metadata, content)
                    .setFields("id,name,size").execute();
        }

        progress.report(name, BackupPhase.UPLOADING, "Uploaded to Google Drive", size, size);
        log.info("Uploaded {} to Google Drive as {}", remotePath, uploaded.getId());
        // The id is returned as the canonical path: names can change, ids cannot.
        return uploaded.getId();
    }

    @Override
    public void fetch(String remotePath, Path localTarget, ProgressCallback progress) throws IOException {
        String fileId = resolveFileId(remotePath);
        Path parent = localTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(localTarget)) {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        }
        progress.report(name, BackupPhase.PREPARING, "Downloaded from Google Drive");
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        try {
            resolveFileId(remotePath);
            return true;
        } catch (java.io.FileNotFoundException e) {
            return false;
        }
    }

    @Override
    public void delete(String remotePath) throws IOException {
        try {
            drive.files().delete(resolveFileId(remotePath)).execute();
        } catch (java.io.FileNotFoundException e) {
            log.debug("Nothing to delete at {}", remotePath);
        }
    }

    @Override
    public List<RemoteEntry> list(String prefix) throws IOException {
        String folderId = (prefix == null || prefix.isBlank())
                ? rootFolderId
                : findFolderPath(prefix).orElse(null);
        if (folderId == null) {
            return List.of();
        }

        List<RemoteEntry> entries = new ArrayList<>();
        String pageToken = null;
        do {
            FileList result = drive.files().list()
                    .setQ("'" + folderId + "' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, size, modifiedTime, mimeType)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute();

            for (File file : result.getFiles()) {
                boolean isFolder = FOLDER_MIME.equals(file.getMimeType());
                entries.add(new RemoteEntry(
                        file.getId(),
                        file.getSize() != null ? file.getSize() : 0L,
                        file.getModifiedTime() != null
                                ? Instant.ofEpochMilli(file.getModifiedTime().getValue()) : null,
                        isFolder));
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);

        return entries;
    }

    /**
     * Resolves a stored path to a file id.
     *
     * <p>{@link #store} returns ids, so a catalog entry normally holds one already and this
     * is a no-op. The name-based walk is the fallback for entries written by hand or by an
     * older version.
     */
    private String resolveFileId(String remotePath) throws IOException {
        if (remotePath == null || remotePath.isBlank()) {
            throw new java.io.FileNotFoundException("No Google Drive path given");
        }
        if (!remotePath.contains("/")) {
            // Looks like a bare id; confirm before trusting it.
            try {
                drive.files().get(remotePath).setFields("id").execute();
                return remotePath;
            } catch (IOException ignored) {
                // Fall through to a name lookup.
            }
        }
        String[] split = splitPath(remotePath);
        String parentId = split[0].isEmpty()
                ? rootFolderId
                : findFolderPath(split[0]).orElseThrow(() -> new java.io.FileNotFoundException(
                        "Google Drive folder not found: " + split[0]));

        return findChildId(parentId, split[1]).orElseThrow(() ->
                new java.io.FileNotFoundException("Google Drive file not found: " + remotePath));
    }

    /** Creates each missing folder along the path and returns the deepest id. */
    private String ensureFolderPath(String folderPath) throws IOException {
        if (folderPath.isEmpty()) {
            return rootFolderId;
        }
        String cached = folderCache.get(folderPath);
        if (cached != null) {
            return cached;
        }

        String parentId = rootFolderId;
        StringBuilder walked = new StringBuilder();
        for (String segment : folderPath.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            walked.append(walked.length() > 0 ? "/" : "").append(segment);
            String key = walked.toString();

            String known = folderCache.get(key);
            if (known != null) {
                parentId = known;
                continue;
            }
            Optional<String> found = findChildFolderId(parentId, segment);
            if (found.isPresent()) {
                parentId = found.get();
            } else {
                File metadata = new File();
                metadata.setName(segment);
                metadata.setMimeType(FOLDER_MIME);
                metadata.setParents(Collections.singletonList(parentId));
                parentId = drive.files().create(metadata).setFields("id").execute().getId();
                log.debug("Created the Google Drive folder {}", key);
            }
            folderCache.put(key, parentId);
        }
        return parentId;
    }

    private Optional<String> findFolderPath(String folderPath) throws IOException {
        String cached = folderCache.get(folderPath);
        if (cached != null) {
            return Optional.of(cached);
        }
        String parentId = rootFolderId;
        for (String segment : folderPath.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            Optional<String> found = findChildFolderId(parentId, segment);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            parentId = found.get();
        }
        folderCache.put(folderPath, parentId);
        return Optional.of(parentId);
    }

    private Optional<String> findChildFolderId(String parentId, String name) throws IOException {
        FileList result = drive.files().list()
                .setQ("'" + parentId + "' in parents and name = '" + escape(name)
                        + "' and mimeType = '" + FOLDER_MIME + "' and trashed = false")
                .setFields("files(id)")
                .setPageSize(1)
                .execute();
        return result.getFiles().isEmpty()
                ? Optional.empty()
                : Optional.of(result.getFiles().get(0).getId());
    }

    private Optional<String> findChildId(String parentId, String name) throws IOException {
        FileList result = drive.files().list()
                .setQ("'" + parentId + "' in parents and name = '" + escape(name) + "' and trashed = false")
                .setFields("files(id)")
                .setPageSize(1)
                .execute();
        return result.getFiles().isEmpty()
                ? Optional.empty()
                : Optional.of(result.getFiles().get(0).getId());
    }

    /** Drive query strings are single-quoted, so a name containing one must be escaped. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Splits into {@code [folderPath, fileName]}. */
    private static String[] splitPath(String remotePath) {
        String cleaned = remotePath.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        return slash < 0
                ? new String[]{"", cleaned}
                : new String[]{cleaned.substring(0, slash), cleaned.substring(slash + 1)};
    }

    @Override
    public long availableSpace() {
        try {
            var about = drive.about().get().setFields("storageQuota").execute();
            var quota = about.getStorageQuota();
            if (quota == null || quota.getLimit() == null) {
                return -1L;
            }
            long used = quota.getUsage() != null ? quota.getUsage() : 0L;
            return quota.getLimit() - used;
        } catch (IOException e) {
            log.debug("Could not read the Google Drive quota: {}", e.getMessage());
            return -1L;
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            drive.files().get(rootFolderId).setFields("id").execute();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "GoogleDriveStorageProvider[" + name + " -> folder " + rootFolderId + "]";
    }

    public static final class Builder {

        private String name = "gdrive";
        private String applicationName = "BackupManager";
        private Path serviceAccountKey;
        private GoogleCredentials credentials;
        private String rootFolderId;
        private Drive drive;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        /** Path to the service account JSON key downloaded from the Google Cloud console. */
        public Builder serviceAccountKey(Path keyFile) {
            this.serviceAccountKey = keyFile;
            return this;
        }

        public Builder credentials(GoogleCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        /**
         * Folder that backups go into. Share this folder with the service account's email
         * address, otherwise uploads fail for lack of quota.
         */
        public Builder rootFolderId(String folderId) {
            this.rootFolderId = folderId;
            return this;
        }

        public Builder drive(Drive drive) {
            this.drive = drive;
            return this;
        }

        public GoogleDriveStorageProvider build() {
            return new GoogleDriveStorageProvider(this);
        }
    }
}
