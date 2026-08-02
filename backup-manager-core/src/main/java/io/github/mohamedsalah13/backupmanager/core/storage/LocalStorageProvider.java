package io.github.mohamedsalah13.backupmanager.core.storage;

import io.github.mohamedsalah13.backupmanager.core.api.ProgressCallback;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.util.CountingOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores artifacts on a filesystem path. The same class covers a local folder, a mapped
 * drive and a UNC share such as {@code \\nas\backups} — from Java's point of view they are
 * all just paths.
 *
 * <p>Uploads land on a {@code .part} file that is renamed into place only once the copy is
 * complete, so a crash or a dropped network share can never leave a truncated file that
 * looks like a valid backup.
 */
public class LocalStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageProvider.class);
    private static final String PARTIAL_SUFFIX = ".part";
    private static final int BUFFER = 1024 * 1024;

    private final String name;
    private final Path root;
    private final boolean createIfMissing;

    public LocalStorageProvider(Path root) {
        this("local", root, true);
    }

    public LocalStorageProvider(String name, Path root) {
        this(name, root, true);
    }

    public LocalStorageProvider(String name, Path root, boolean createIfMissing) {
        this.name = name;
        this.root = root.toAbsolutePath().normalize();
        this.createIfMissing = createIfMissing;
    }

    /** Convenience for a network share; identical behaviour, clearer intent at the call site. */
    public static LocalStorageProvider networkShare(String name, String uncPath) {
        return new LocalStorageProvider(name, Path.of(uncPath), true);
    }

    @Override
    public String name() {
        return name;
    }

    public Path getRoot() {
        return root;
    }

    @Override
    public void init() throws IOException {
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) {
                throw new BackupException.Configuration(
                        "Backup destination '" + name + "' exists but is not a directory: " + root);
            }
        } else if (createIfMissing) {
            Files.createDirectories(root);
            log.info("Created backup directory {}", root);
        } else {
            throw new BackupException.Configuration(
                    "Backup destination '" + name + "' does not exist: " + root);
        }
        if (!Files.isWritable(root)) {
            throw new BackupException.Configuration(
                    "Backup destination '" + name + "' is not writable: " + root);
        }
    }

    @Override
    public String store(Path localFile, String remotePath, ProgressCallback progress) throws IOException {
        Path target = resolve(remotePath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        long total = Files.size(localFile);
        Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);

        try {
            try (InputStream in = Files.newInputStream(localFile);
                 OutputStream raw = Files.newOutputStream(partial);
                 CountingOutputStream out = new CountingOutputStream(raw,
                         written -> progress.report(name, BackupPhase.UPLOADING,
                                 "Copying to " + name, written, total))) {
                in.transferTo(out);
                out.flush();
            }
            Files.move(partial, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw new IOException("Failed to store backup at " + target + ": " + e.getMessage(), e);
        } catch (UnsupportedOperationException e) {
            // Some SMB mounts reject ATOMIC_MOVE; a plain replace is still better than
            // writing directly to the final name.
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("Stored {} ({} bytes) at {}", remotePath, total, target);
        return target.toString();
    }

    @Override
    public void fetch(String remotePath, Path localTarget, ProgressCallback progress) throws IOException {
        Path source = resolve(remotePath);
        if (!Files.exists(source)) {
            throw new java.io.FileNotFoundException("Backup not found in '" + name + "': " + source);
        }
        Path parent = localTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long total = Files.size(source);
        try (InputStream in = Files.newInputStream(source);
             OutputStream raw = Files.newOutputStream(localTarget);
             CountingOutputStream out = new CountingOutputStream(raw,
                     read -> progress.report(name, BackupPhase.PREPARING,
                             "Downloading from " + name, read, total))) {
            in.transferTo(out);
        }
    }

    @Override
    public boolean exists(String remotePath) {
        return Files.exists(resolve(remotePath));
    }

    @Override
    public void delete(String remotePath) throws IOException {
        Path target = resolve(remotePath);
        Files.deleteIfExists(target);
        log.debug("Deleted {}", target);
    }

    @Override
    public List<RemoteEntry> list(String prefix) throws IOException {
        Path dir = (prefix == null || prefix.isBlank()) ? root : resolve(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<RemoteEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (child.getFileName().toString().endsWith(PARTIAL_SUFFIX)) {
                    continue;
                }
                boolean directory = Files.isDirectory(child);
                entries.add(new RemoteEntry(
                        root.relativize(child).toString().replace('\\', '/'),
                        directory ? 0L : Files.size(child),
                        Files.getLastModifiedTime(child).toInstant(),
                        directory));
            }
        }
        return entries;
    }

    @Override
    public long availableSpace() {
        try {
            FileStore store = Files.getFileStore(Files.exists(root) ? root : root.getRoot());
            return store.getUsableSpace();
        } catch (IOException e) {
            log.debug("Could not read free space for {}: {}", root, e.getMessage());
            return -1L;
        }
    }

    @Override
    public boolean isHealthy() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    /**
     * Resolves a remote path under the root, rejecting anything that would escape it.
     * Job names reach this method, and those can come from a settings screen.
     */
    private Path resolve(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return root;
        }
        Path candidate = root.resolve(remotePath.replace('\\', '/')).normalize();
        if (!candidate.startsWith(root)) {
            throw new BackupException.Configuration(
                    "Refusing path outside the backup root: " + remotePath);
        }
        return candidate;
    }

    /** True when the root sits on removable or network media that may vanish mid-run. */
    public boolean isRemovableOrRemote() {
        try {
            FileStore store = Files.getFileStore(root);
            String type = store.type().toLowerCase(java.util.Locale.ROOT);
            return type.contains("smb") || type.contains("cifs") || type.contains("nfs")
                    || type.contains("fat") || store.name().startsWith("\\\\");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "LocalStorageProvider[" + name + " -> " + root + "]";
    }

    /** Whether the path is reachable right now, without creating anything. */
    public boolean rootExists() {
        return Files.exists(root, LinkOption.NOFOLLOW_LINKS);
    }
}
