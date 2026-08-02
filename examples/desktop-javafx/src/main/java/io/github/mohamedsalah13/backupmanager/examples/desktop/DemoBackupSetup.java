package io.github.mohamedsalah13.backupmanager.examples.desktop;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import io.github.mohamedsalah13.backupmanager.core.model.RetentionRule;
import io.github.mohamedsalah13.backupmanager.core.model.Schedule;
import io.github.mohamedsalah13.backupmanager.core.source.FileSystemBackupSource;
import io.github.mohamedsalah13.backupmanager.core.storage.LocalStorageProvider;
import io.github.mohamedsalah13.backupmanager.db.file.SqliteBackupSource;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The whole backup configuration for the desktop example, deliberately separate from the
 * JavaFX code.
 *
 * <p>Keeping it out of the {@code Application} class is worth copying: backup settings are
 * exactly the kind of thing you want to unit test, and anything living inside
 * {@code Application.init()} can only be exercised by starting a real window.
 */
public final class DemoBackupSetup {

    private DemoBackupSetup() {
    }

    /**
     * @param root      folder everything lives under; delete it to reset the example
     * @param database  the SQLite file to protect
     * @param documents the folder to protect
     * @param password  encryption passphrase, or {@code null} to store artifacts unencrypted
     */
    public static BackupManager createManager(Path root, Path database, Path documents,
                                              String password) {
        BackupJob.Builder databaseJob = BackupJob.builder("customers-db")
                .description("The application's SQLite database")
                .source(new SqliteBackupSource(database))
                .destination(new LocalStorageProvider("local", root.resolve("backups")))
                // A second copy on separate media. In a deployed application this would be
                // a UNC share or a removable drive:
                //   LocalStorageProvider.networkShare("nas", "\\\\nas\\backups")
                .destination(new LocalStorageProvider("copy", root.resolve("backups-copy")))
                .schedule(Schedule.dailyAt(2, 30))
                .retention(RetentionRule.gfs(7, 4, 6, 0).setMinimumKept(2))
                .verifyAfterStore(true);

        if (password != null && !password.isBlank()) {
            databaseJob.encryptWithPassword(password);
        }

        BackupJob documentsJob = BackupJob.builder("documents")
                .description("User documents folder")
                .source(new FileSystemBackupSource(documents)
                        .exclude("**/*.tmp")
                        // Restoring into a separate folder means a mis-click cannot
                        // destroy the live documents.
                        .restoreTo(root.resolve("restored-documents")))
                .destination(new LocalStorageProvider("local", root.resolve("backups")))
                .schedule(Schedule.every(Duration.ofHours(6)))
                .retention(RetentionRule.keepLast(10))
                .build();

        return BackupManager.builder()
                // Catalog and staging live here. The staging area needs room for the
                // largest artifact any job will produce.
                .storageRoot(root.resolve("backup-state"))
                .job(databaseJob.build())
                .job(documentsJob)
                .build();
    }
}
