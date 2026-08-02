package io.github.mohamedsalah13.backupmanager.core.api;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * A destination that holds backup artifacts. Local disk and UNC shares ship in the core;
 * S3, Google Drive and Dropbox live in their own modules so their SDKs stay optional.
 *
 * <p>Implementations must be safe to call from multiple threads, since a job can fan out
 * to several destinations at once.
 */
public interface StorageProvider extends AutoCloseable {

    /** Stable name recorded on every {@link io.github.mohamedsalah13.backupmanager.core.model.StoredLocation}. */
    String name();

    /** Called once before first use — create buckets, open sessions, verify credentials. */
    default void init() throws IOException {
    }

    /**
     * Uploads {@code localFile} and returns the canonical path of the stored copy, which
     * may differ from {@code remotePath} if the backend rewrites it.
     */
    String store(Path localFile, String remotePath, ProgressCallback progress) throws IOException;

    /** Downloads {@code remotePath} to {@code localTarget}, overwriting it. */
    void fetch(String remotePath, Path localTarget, ProgressCallback progress) throws IOException;

    boolean exists(String remotePath) throws IOException;

    void delete(String remotePath) throws IOException;

    /** Entries under {@code prefix}; an empty or null prefix lists the root. */
    List<RemoteEntry> list(String prefix) throws IOException;

    /** Free space in bytes, or {@code -1} when the backend does not report it. */
    default long availableSpace() {
        return -1L;
    }

    /** Whether this destination is currently reachable. Never throws. */
    default boolean isHealthy() {
        try {
            list(null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    default void close() throws IOException {
    }

    /** One stored object. */
    record RemoteEntry(String path, long size, Instant lastModified, boolean directory) {

        public static RemoteEntry file(String path, long size, Instant lastModified) {
            return new RemoteEntry(path, size, lastModified, false);
        }

        public static RemoteEntry directory(String path) {
            return new RemoteEntry(path, 0L, null, true);
        }

        public String fileName() {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }
}
