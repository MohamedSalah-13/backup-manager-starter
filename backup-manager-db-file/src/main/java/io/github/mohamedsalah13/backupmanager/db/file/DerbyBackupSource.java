package io.github.mohamedsalah13.backupmanager.db.file;

import io.github.mohamedsalah13.backupmanager.core.api.BackupContext;
import io.github.mohamedsalah13.backupmanager.core.api.BackupSource;
import io.github.mohamedsalah13.backupmanager.core.api.RestoreContext;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.util.FileNames;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * Backs up an Apache Derby database using {@code SYSCS_UTIL.SYSCS_BACKUP_DATABASE}.
 *
 * <p>Derby's backup procedure writes a directory tree rather than a single file, so the
 * result is packed into a TAR stream to fit the engine's single-artifact pipeline. The
 * database stays online throughout; Derby blocks only the operations that would make the
 * copy inconsistent.
 */
public class DerbyBackupSource implements BackupSource {

    private static final Logger log = LoggerFactory.getLogger(DerbyBackupSource.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String databaseName;
    private Path restoreTarget;

    /**
     * @param jdbcUrl      for example {@code jdbc:derby:C:/data/sales}
     * @param databaseName the directory name Derby uses, needed to lay out the archive
     */
    public DerbyBackupSource(String jdbcUrl, String databaseName, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
    }

    public static DerbyBackupSource ofDirectory(Path databaseDirectory) {
        Path normalized = databaseDirectory.toAbsolutePath().normalize();
        return new DerbyBackupSource(
                "jdbc:derby:" + normalized.toString().replace('\\', '/'),
                normalized.getFileName().toString(), null, null);
    }

    /** Where a restore unpacks the database directory. */
    public DerbyBackupSource restoreTo(Path target) {
        this.restoreTarget = target.toAbsolutePath().normalize();
        return this;
    }

    @Override
    public String type() {
        return "derby";
    }

    @Override
    public String describe() {
        return "Derby " + jdbcUrl;
    }

    @Override
    public String defaultExtension() {
        return "tar";
    }

    @Override
    public void validate() {
        try (Connection connection = open()) {
            connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            throw new BackupException.Configuration(
                    "Could not connect to the Derby database at " + jdbcUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void dump(BackupContext ctx, OutputStream out) throws Exception {
        Path stagingRoot = Files.createDirectories(
                ctx.getWorkDir().resolve("derby-backup-" + System.nanoTime()));
        try {
            ctx.report(BackupPhase.DUMPING, "Running the Derby online backup");
            try (Connection connection = open();
                 CallableStatement call = connection.prepareCall(
                         "CALL SYSCS_UTIL.SYSCS_BACKUP_DATABASE(?)")) {
                call.setString(1, stagingRoot.toString());
                call.execute();
            }

            // Derby creates <stagingRoot>/<databaseName>; archive that directory.
            Path produced = stagingRoot.resolve(databaseName);
            if (!Files.isDirectory(produced)) {
                produced = firstDirectoryIn(stagingRoot);
            }
            if (produced == null) {
                throw new BackupException.Source(
                        "The Derby backup procedure produced nothing under " + stagingRoot, null);
            }

            ctx.report(BackupPhase.DUMPING, "Packing the Derby backup");
            long[] counters = archiveDirectory(produced, out);
            ctx.putAttribute("derbyFiles", String.valueOf(counters[0]));
            ctx.putAttribute("derbyBytes", String.valueOf(counters[1]));
            log.info("Packed {} Derby files ({} bytes)", counters[0], counters[1]);

        } finally {
            FileNames.deleteQuietly(stagingRoot);
        }
    }

    private long[] archiveDirectory(Path root, OutputStream out) throws Exception {
        long files = 0;
        long bytes = 0;
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(path).toString().replace('\\', '/');
                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), name);
                long size = Files.size(path);
                entry.setSize(size);
                tar.putArchiveEntry(entry);
                try (InputStream in = Files.newInputStream(path)) {
                    in.transferTo(tar);
                }
                tar.closeArchiveEntry();
                files++;
                bytes += size;
            }
        }
        tar.finish();
        tar.flush();
        return new long[]{files, bytes};
    }

    @Override
    public void restore(RestoreContext ctx, InputStream in) throws Exception {
        Path target = ctx.getTargetOverride() != null && !ctx.getTargetOverride().isBlank()
                ? Path.of(ctx.getTargetOverride()).toAbsolutePath().normalize()
                : restoreTarget;

        if (target == null) {
            throw new BackupException.Configuration(
                    "Restoring Derby needs a target directory, and the engine must be shut down"
                            + " first. Call restoreTo(...) or set a target override.");
        }

        long files = 0;
        TarArchiveInputStream tar = new TarArchiveInputStream(in);
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            ctx.checkCancelled();
            if (entry.isDirectory()) {
                continue;
            }
            if (ctx.isDryRun()) {
                tar.transferTo(OutputStream.nullOutputStream());
                files++;
                continue;
            }
            Path destination = target.resolve(entry.getName()).normalize();
            if (!destination.startsWith(target)) {
                log.warn("Skipping archive entry with an unsafe path: {}", entry.getName());
                continue;
            }
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(destination)) {
                tar.transferTo(out);
            }
            files++;
        }
        log.info("Unpacked {} Derby files into {}", files, target);
    }

    private static Path firstDirectoryIn(Path root) throws java.io.IOException {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).findFirst().orElse(null);
        }
    }

    private Connection open() throws SQLException {
        return username != null
                ? DriverManager.getConnection(jdbcUrl, username, password)
                : DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public long estimateSize() {
        return -1L;
    }
}
