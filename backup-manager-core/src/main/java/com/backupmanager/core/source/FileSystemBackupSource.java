package com.backupmanager.core.source;

import com.backupmanager.core.api.BackupContext;
import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupPhase;
import com.backupmanager.core.model.BackupType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Backs up files and directories as an uncompressed TAR stream. TAR rather than ZIP
 * because the engine's compression stage runs next: {@code .tar.gz} compresses across
 * files, while a ZIP would deflate each entry separately and then be deflated again.
 *
 * <p>Incremental runs include only entries modified since the base backup. A restore
 * therefore has to replay the full backup first and then each increment in order, which
 * the engine handles.
 */
public class FileSystemBackupSource implements BackupSource {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBackupSource.class);

    private final List<Path> roots = new ArrayList<>();
    private final Set<String> excludePatterns = new LinkedHashSet<>();
    private Path restoreTarget;
    private boolean followSymlinks;
    private long maxFileSize = -1;

    public FileSystemBackupSource(Path... roots) {
        for (Path root : roots) {
            this.roots.add(root.toAbsolutePath().normalize());
        }
    }

    public FileSystemBackupSource addRoot(Path root) {
        roots.add(root.toAbsolutePath().normalize());
        return this;
    }

    /**
     * Excludes paths matching a glob, evaluated against the path relative to its root —
     * for example {@code **}{@code /*.tmp} or {@code cache/**}.
     */
    public FileSystemBackupSource exclude(String glob) {
        excludePatterns.add(glob);
        return this;
    }

    /** Where a restore writes to, when it should not go back to the original location. */
    public FileSystemBackupSource restoreTo(Path target) {
        this.restoreTarget = target.toAbsolutePath().normalize();
        return this;
    }

    public FileSystemBackupSource followSymlinks(boolean follow) {
        this.followSymlinks = follow;
        return this;
    }

    /** Skips files above this size. {@code -1} includes everything. */
    public FileSystemBackupSource maxFileSize(long bytes) {
        this.maxFileSize = bytes;
        return this;
    }

    @Override
    public String type() {
        return "files";
    }

    @Override
    public String describe() {
        return "files:" + roots;
    }

    @Override
    public String defaultExtension() {
        return "tar";
    }

    @Override
    public Set<BackupType> supportedTypes() {
        return EnumSet.of(BackupType.FULL, BackupType.INCREMENTAL, BackupType.DIFFERENTIAL);
    }

    @Override
    public void validate() {
        if (roots.isEmpty()) {
            throw new BackupException.Configuration("File backup source has no roots configured");
        }
        for (Path root : roots) {
            if (!Files.exists(root)) {
                throw new BackupException.Configuration("Backup source path does not exist: " + root);
            }
            if (!Files.isReadable(root)) {
                throw new BackupException.Configuration("Backup source path is not readable: " + root);
            }
        }
    }

    @Override
    public long estimateSize() {
        long total = 0;
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                total += walk.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            } catch (IOException e) {
                log.debug("Could not size {}: {}", root, e.getMessage());
                return -1L;
            }
        }
        return total;
    }

    @Override
    public void dump(BackupContext ctx, OutputStream out) throws Exception {
        Instant since = ctx.getType().requiresChain()
                ? ctx.getChangesSince().orElse(null)
                : null;
        if (ctx.getType().requiresChain() && since == null) {
            log.info("No base backup found for job '{}'; falling back to a full file backup",
                    ctx.getJobName());
        }

        List<PathMatcher> excludes = compileExcludes();
        long[] counters = new long[2]; // files, bytes

        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        // Names and sizes in real trees routinely exceed the 1988-era TAR limits.
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

        for (Path root : roots) {
            ctx.checkCancelled();
            archiveRoot(ctx, tar, root, since, excludes, counters);
        }

        tar.finish();
        // The engine owns the underlying stream, so the trailer is flushed but not closed.
        tar.flush();

        ctx.putAttribute("fileCount", String.valueOf(counters[0]));
        ctx.putAttribute("byteCount", String.valueOf(counters[1]));
        if (since != null) {
            ctx.putAttribute("changesSince", since.toString());
        }
        log.info("Archived {} files ({} bytes) for job '{}'", counters[0], counters[1], ctx.getJobName());
    }

    private void archiveRoot(BackupContext ctx, TarArchiveOutputStream tar, Path root,
                             Instant since, List<PathMatcher> excludes, long[] counters)
            throws IOException {

        // Entry names are prefixed with the root's folder name so several roots can be
        // archived together and separated again on restore.
        String rootLabel = root.getFileName() != null
                ? root.getFileName().toString()
                : root.toString().replaceAll("[^a-zA-Z0-9]", "_");

        Set<java.nio.file.FileVisitOption> options = followSymlinks
                ? EnumSet.of(java.nio.file.FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(java.nio.file.FileVisitOption.class);

        Files.walkFileTree(root, options, Integer.MAX_VALUE, new FileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (ctx.isCancelled()) {
                    return FileVisitResult.TERMINATE;
                }
                return isExcluded(root, dir, excludes)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (ctx.isCancelled()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || isExcluded(root, file, excludes)) {
                    return FileVisitResult.CONTINUE;
                }
                if (maxFileSize > 0 && attrs.size() > maxFileSize) {
                    log.debug("Skipping {} ({} bytes exceeds the configured limit)", file, attrs.size());
                    return FileVisitResult.CONTINUE;
                }
                if (since != null && !attrs.lastModifiedTime().toInstant().isAfter(since)) {
                    return FileVisitResult.CONTINUE;
                }

                String entryName = rootLabel + "/" + root.relativize(file).toString().replace('\\', '/');
                TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), entryName);
                entry.setSize(attrs.size());
                entry.setModTime(attrs.lastModifiedTime().toMillis());

                tar.putArchiveEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(tar);
                } catch (IOException e) {
                    // A file locked by another process should not sink the whole run.
                    log.warn("Could not read {}: {}", file, e.getMessage());
                }
                tar.closeArchiveEntry();

                counters[0]++;
                counters[1] += attrs.size();
                if (counters[0] % 200 == 0) {
                    ctx.report(BackupPhase.DUMPING,
                            "Archived " + counters[0] + " files", counters[1], -1);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Skipping unreadable path {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void restore(RestoreContext ctx, InputStream in) throws Exception {
        Path target = resolveRestoreTarget(ctx);
        if (target == null) {
            throw new BackupException.Configuration(
                    "File restore needs a target directory. Call restoreTo(...) or set a target override.");
        }
        // A dry run must leave no trace, not even an empty directory.
        if (!ctx.isDryRun()) {
            Files.createDirectories(target);
        }

        long files = 0;
        long bytes = 0;
        TarArchiveInputStream tar = new TarArchiveInputStream(in);
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            ctx.checkCancelled();
            if (entry.isDirectory()) {
                continue;
            }

            Path destination = safeResolve(target, entry.getName());
            if (destination == null) {
                log.warn("Skipping archive entry with an unsafe path: {}", entry.getName());
                continue;
            }
            if (ctx.isDryRun()) {
                tar.transferTo(OutputStream.nullOutputStream());
                files++;
                continue;
            }
            if (Files.exists(destination) && !ctx.isReplaceExisting()) {
                log.debug("Keeping existing file {}", destination);
                tar.transferTo(OutputStream.nullOutputStream());
                continue;
            }

            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(destination)) {
                bytes += tar.transferTo(out);
            }
            if (entry.getLastModifiedDate() != null) {
                Files.setLastModifiedTime(destination,
                        FileTime.fromMillis(entry.getLastModifiedDate().getTime()));
            }
            files++;
            if (files % 200 == 0) {
                ctx.report(BackupPhase.PREPARING, "Restored " + files + " files", bytes, -1);
            }
        }
        log.info("Restored {} files ({} bytes) into {}", files, bytes, target);
    }

    private Path resolveRestoreTarget(RestoreContext ctx) {
        if (ctx.getTargetOverride() != null && !ctx.getTargetOverride().isBlank()) {
            return Path.of(ctx.getTargetOverride()).toAbsolutePath().normalize();
        }
        if (restoreTarget != null) {
            return restoreTarget;
        }
        // A single root can restore in place; several would be ambiguous.
        return roots.size() == 1 ? roots.get(0) : null;
    }

    /**
     * Resolves an archive entry under {@code base}, returning {@code null} when the name
     * would escape it. Without this, a crafted archive could write anywhere the process
     * can reach — the "zip slip" problem.
     */
    private Path safeResolve(Path base, String entryName) {
        String cleaned = entryName.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..") || cleaned.matches("^[A-Za-z]:.*")) {
            // Still allow ".." that normalises harmlessly, but check the result below.
            if (cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*")) {
                return null;
            }
        }
        Path resolved = base.resolve(cleaned).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    /**
     * Compiles the exclude globs.
     *
     * <p>A literal reading of {@code **}{@code /*.log} misses {@code app.log} at the root,
     * because {@code **}{@code /} demands at least one directory. Nobody writing an exclude
     * list means that, so the leading {@code **}{@code /} also gets a depth-zero variant.
     */
    private List<PathMatcher> compileExcludes() {
        var fs = java.nio.file.FileSystems.getDefault();
        List<PathMatcher> matchers = new ArrayList<>(excludePatterns.size());
        for (String pattern : excludePatterns) {
            matchers.add(fs.getPathMatcher("glob:" + pattern));
            if (pattern.startsWith("**/") && pattern.length() > 3) {
                matchers.add(fs.getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
        return matchers;
    }

    private boolean isExcluded(Path root, Path candidate, List<PathMatcher> excludes) {
        if (excludes.isEmpty()) {
            return false;
        }
        Path relative = root.relativize(candidate);
        for (PathMatcher matcher : excludes) {
            if (matcher.matches(relative) || matcher.matches(candidate.getFileName())) {
                return true;
            }
        }
        return false;
    }

    public List<Path> getRoots() {
        return List.copyOf(roots);
    }

    /** True when every configured root is currently present. */
    public boolean rootsExist() {
        return roots.stream().allMatch(p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS));
    }
}
