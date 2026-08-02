package com.backupmanager.db.file;

import com.backupmanager.core.BackupManager;
import com.backupmanager.core.engine.RestoreEngine;
import com.backupmanager.core.model.BackupJob;
import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupStatus;
import com.backupmanager.core.model.RetentionRule;
import com.backupmanager.core.storage.LocalStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backs up and restores real SQLite and H2 databases holding real rows. */
class FileDatabaseBackupTest {

    @TempDir
    Path temp;

    private BackupManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    // ------------------------------------------------------------------ SQLite

    private Path createSqliteDatabase(int rows) throws SQLException {
        Path file = temp.resolve("app.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT, note BLOB)");
            for (int i = 1; i <= rows; i++) {
                statement.execute("INSERT INTO customers (id, name, note) VALUES ("
                        + i + ", 'Customer " + i + "', X'DEADBEEF')");
            }
        }
        return file;
    }

    private long countSqliteRows(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM customers")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void backsUpAndRestoresSqlite() throws Exception {
        Path database = createSqliteDatabase(250);
        Path restoreTarget = temp.resolve("restored.db");

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("mgr"))
                .job(BackupJob.builder("sqlite")
                        .source(new SqliteBackupSource(database).restoreTo(restoreTarget))
                        .destination(new LocalStorageProvider("disk", temp.resolve("backups")))
                        .build())
                .build();

        BackupRecord record = manager.backupNow("sqlite");
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);

        manager.restore(record.getId());

        assertThat(countSqliteRows(restoreTarget)).isEqualTo(250);
    }

    @Test
    void sqliteSnapshotIsConsistentWhileTheDatabaseIsOpen() throws Exception {
        Path database = createSqliteDatabase(100);

        // Hold an open connection with uncommitted work, as a running application would.
        try (Connection held = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            held.setAutoCommit(false);
            try (Statement statement = held.createStatement()) {
                statement.execute("INSERT INTO customers (id, name) VALUES (9999, 'uncommitted')");
            }

            Path restoreTarget = temp.resolve("restored-open.db");
            manager = BackupManager.builder()
                    .storageRoot(temp.resolve("mgr2"))
                    .job(BackupJob.builder("sqlite")
                            .source(new SqliteBackupSource(database).restoreTo(restoreTarget))
                            .destination(new LocalStorageProvider("disk", temp.resolve("backups2")))
                            .build())
                    .build();

            BackupRecord record = manager.backupNow("sqlite");
            assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
            manager.restore(record.getId());

            // The uncommitted row must not appear in the snapshot.
            assertThat(countSqliteRows(restoreTarget)).isEqualTo(100);
            held.rollback();
        }
    }

    @Test
    void encryptedSqliteBackupRoundTrips() throws Exception {
        Path database = createSqliteDatabase(50);
        Path restoreTarget = temp.resolve("restored-enc.db");

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("mgr3"))
                .job(BackupJob.builder("sqlite")
                        .source(new SqliteBackupSource(database).restoreTo(restoreTarget))
                        .destination(new LocalStorageProvider("disk", temp.resolve("backups3")))
                        .encryptWithPassword("db-passphrase")
                        .build())
                .build();

        BackupRecord record = manager.backupNow("sqlite");
        manager.restore(record.getId());

        assertThat(record.isEncrypted()).isTrue();
        assertThat(countSqliteRows(restoreTarget)).isEqualTo(50);
    }

    @Test
    void keepsTheOriginalWhenTheRestorePayloadIsNotADatabase() throws Exception {
        Path database = createSqliteDatabase(30);
        manager = BackupManager.builder()
                .storageRoot(temp.resolve("mgr4"))
                .job(BackupJob.builder("sqlite")
                        .source(new SqliteBackupSource(database))
                        .destination(new LocalStorageProvider("disk", temp.resolve("backups4")))
                        .verifyAfterStore(false)
                        .noCompression()
                        .build())
                .build();

        BackupRecord record = manager.backupNow("sqlite");

        // Replace the stored artifact with junk, then clear the checksum so the engine's
        // own guard does not fire first and the source's integrity check is what runs.
        Path artifact = Path.of(record.availableLocations().get(0).getPath());
        Files.writeString(artifact, "this is not a database");

        assertThatThrownBy(() -> manager.restore(record.getId(),
                new RestoreEngine.RestoreOptions().setVerifyChecksum(false), null))
                .hasMessageContaining("integrity check");

        // The live database is untouched.
        assertThat(countSqliteRows(database)).isEqualTo(30);
    }

    @Test
    void rejectsAMissingSqliteFile() {
        manager = BackupManager.builder()
                .storageRoot(temp.resolve("mgr5"))
                .job(BackupJob.builder("sqlite")
                        .source(new SqliteBackupSource(temp.resolve("nope.db")))
                        .destination(new LocalStorageProvider("disk", temp.resolve("backups5")))
                        .maxRetries(0)
                        .build())
                .build();

        BackupRecord record = manager.backupNow("sqlite");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(record.getErrorMessage()).contains("not found");
    }

    @Test
    void retentionRemovesOldSqliteBackups() throws Exception {
        Path database = createSqliteDatabase(10);
        Path backups = temp.resolve("backups6");

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("mgr6"))
                .job(BackupJob.builder("sqlite")
                        .source(new SqliteBackupSource(database))
                        .destination(new LocalStorageProvider("disk", backups))
                        .retention(RetentionRule.keepLast(2).setMinimumKept(0))
                        .build())
                .build();

        for (int i = 0; i < 3; i++) {
            manager.backupNow("sqlite");
            Thread.sleep(1100);
        }

        assertThat(manager.history("sqlite")).hasSize(2);
        try (var files = Files.list(backups.resolve("sqlite"))) {
            assertThat(files).hasSize(2);
        }
    }

    // ---------------------------------------------------------------------- H2

    private String createH2Database(int rows) throws SQLException {
        String url = "jdbc:h2:file:" + temp.resolve("h2db").toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, total DECIMAL(10,2), label VARCHAR(255))");
            for (int i = 1; i <= rows; i++) {
                statement.execute("INSERT INTO orders VALUES (" + i + ", " + (i * 1.5)
                        + ", 'Order ''" + i + "'' with a quote')");
            }
        }
        return url;
    }

    private long countH2Rows(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void backsUpAndRestoresH2() throws Exception {
        String url = createH2Database(120);

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("h2mgr"))
                .job(BackupJob.builder("h2")
                        .source(new H2BackupSource(url, "sa", ""))
                        .destination(new LocalStorageProvider("disk", temp.resolve("h2backups")))
                        .build())
                .build();

        BackupRecord record = manager.backupNow("h2");
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);

        // Drop everything, then restore over the empty database.
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }

        manager.restore(record.getId());

        assertThat(countH2Rows(url)).isEqualTo(120);
    }

    @Test
    void h2BackupPreservesQuotedStrings() throws Exception {
        String url = createH2Database(5);

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("h2mgr2"))
                .job(BackupJob.builder("h2")
                        .source(new H2BackupSource(url, "sa", ""))
                        .destination(new LocalStorageProvider("disk", temp.resolve("h2backups2")))
                        .build())
                .build();

        BackupRecord record = manager.backupNow("h2");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        manager.restore(record.getId());

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT label FROM orders WHERE id = 3")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Order '3' with a quote");
        }
    }

    @Test
    void h2NativeZipRestoreExplainsWhyItCannotRunOnline() throws Exception {
        String url = createH2Database(10);

        manager = BackupManager.builder()
                .storageRoot(temp.resolve("h2mgr3"))
                .job(BackupJob.builder("h2")
                        .source(new H2BackupSource(url, "sa", "", H2BackupSource.Format.NATIVE_ZIP))
                        .destination(new LocalStorageProvider("disk", temp.resolve("h2backups3")))
                        .build())
                .build();

        BackupRecord record = manager.backupNow("h2");
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);

        assertThatThrownBy(() -> manager.restore(record.getId()))
                .hasMessageContaining("SQL_SCRIPT");
    }
}
