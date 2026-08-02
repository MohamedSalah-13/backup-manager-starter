package com.backupmanager.db.file;

import com.backupmanager.core.api.BackupContext;
import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupPhase;
import com.backupmanager.core.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Backs up a SQLite database file.
 *
 * <p>Copying the {@code .db} file directly is the obvious approach and the wrong one: a
 * write in progress leaves the copy internally inconsistent, and with WAL journalling the
 * recent commits live in a separate {@code -wal} file that a naive copy misses entirely.
 *
 * <p>This uses SQLite's own {@code VACUUM INTO}, which writes a consistent, fully
 * checkpointed and defragmented copy while the database stays open for readers. On builds
 * too old for that (before 3.27) it falls back to the legacy {@code backup} command.
 */
public class SqliteBackupSource implements BackupSource {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupSource.class);

    private final Path databaseFile;
    private Path restoreTarget;

    public SqliteBackupSource(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    /** Restores to a different file than the one backed up. */
    public SqliteBackupSource restoreTo(Path target) {
        this.restoreTarget = target.toAbsolutePath().normalize();
        return this;
    }

    @Override
    public String type() {
        return "sqlite";
    }

    @Override
    public String describe() {
        return "SQLite " + databaseFile;
    }

    @Override
    public String defaultExtension() {
        return "db";
    }

    @Override
    public void validate() {
        if (!Files.exists(databaseFile)) {
            throw new BackupException.Configuration("SQLite database not found: " + databaseFile);
        }
        if (!Files.isReadable(databaseFile)) {
            throw new BackupException.Configuration("SQLite database is not readable: " + databaseFile);
        }
        try (Connection connection = open(databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA quick_check");
        } catch (SQLException e) {
            throw new BackupException.Configuration(
                    "Could not open the SQLite database at " + databaseFile + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long estimateSize() {
        try {
            return Files.size(databaseFile);
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    public void dump(BackupContext ctx, OutputStream out) throws Exception {
        Path snapshot = ctx.getWorkDir().resolve("sqlite-snapshot-" + System.nanoTime() + ".db");
        try {
            ctx.report(BackupPhase.DUMPING, "Creating a consistent SQLite snapshot");
            createSnapshot(snapshot);

            long size = Files.size(snapshot);
            ctx.putAttribute("sqliteBytes", String.valueOf(size));
            ctx.report(BackupPhase.DUMPING, "Snapshot of " + FileNames.humanSize(size), 0, size);

            try (InputStream in = Files.newInputStream(snapshot)) {
                in.transferTo(out);
            }
        } finally {
            FileNames.deleteQuietly(snapshot);
        }
    }

    private void createSnapshot(Path target) throws SQLException {
        try (Connection connection = open(databaseFile);
             Statement statement = connection.createStatement()) {
            String escaped = target.toString().replace("'", "''");
            try {
                statement.execute("VACUUM INTO '" + escaped + "'");
                log.debug("Snapshotted {} with VACUUM INTO", databaseFile);
                return;
            } catch (SQLException e) {
                log.debug("VACUUM INTO unavailable ({}); trying the legacy backup command",
                        e.getMessage());
            }
            // Pre-3.27 SQLite, and the JDBC driver's own backup extension.
            statement.execute("backup to '" + escaped + "'");
            log.debug("Snapshotted {} with the legacy backup command", databaseFile);
        }
    }

    @Override
    public void restore(RestoreContext ctx, InputStream in) throws Exception {
        Path target = resolveTarget(ctx);

        Path incoming = ctx.getWorkDir().resolve("sqlite-restore-" + System.nanoTime() + ".db");
        try {
            try (OutputStream out = Files.newOutputStream(incoming)) {
                in.transferTo(out);
            }

            // Prove the payload is a working database before it replaces a live one.
            try (Connection connection = open(incoming);
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA quick_check");
            } catch (SQLException e) {
                throw new BackupException.Verification(
                        "The restored SQLite file failed its integrity check, so the existing"
                                + " database was left untouched: " + e.getMessage());
            }

            if (ctx.isDryRun()) {
                log.info("Dry run: the SQLite payload is a valid database ({} bytes)",
                        Files.size(incoming));
                return;
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Keep the previous file until the swap succeeds.
            Path previous = null;
            if (Files.exists(target)) {
                previous = target.resolveSibling(target.getFileName() + ".before-restore");
                Files.move(target, previous, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
                // WAL and shared-memory files belong to the replaced database, not the new one.
                FileNames.deleteQuietly(Path.of(target + "-wal"));
                FileNames.deleteQuietly(Path.of(target + "-shm"));
                FileNames.deleteQuietly(previous);
            } catch (Exception e) {
                if (previous != null && !Files.exists(target)) {
                    Files.move(previous, target, StandardCopyOption.REPLACE_EXISTING);
                }
                throw e;
            }
            log.info("Restored the SQLite database at {}", target);
        } finally {
            FileNames.deleteQuietly(incoming);
        }
    }

    private Path resolveTarget(RestoreContext ctx) {
        if (ctx.getTargetOverride() != null && !ctx.getTargetOverride().isBlank()) {
            return Path.of(ctx.getTargetOverride()).toAbsolutePath().normalize();
        }
        return restoreTarget != null ? restoreTarget : databaseFile;
    }

    private static Connection open(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }
}
