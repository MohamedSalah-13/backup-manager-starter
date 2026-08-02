package io.github.mohamedsalah13.backupmanager.examples.desktop;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the desktop example's real configuration without starting a window.
 *
 * <p>This is the reason {@link DemoBackupSetup} is separate from the {@code Application}
 * class: the sample is documentation, and documentation that has never been run is a guess.
 */
class DemoBackupSetupTest {

    @TempDir
    Path root;

    private DemoDatabase database;
    private Path documents;
    private BackupManager manager;

    @BeforeEach
    void setUp() throws Exception {
        database = new DemoDatabase(root.resolve("app/customers.db"));
        database.createIfMissing();

        documents = Files.createDirectories(root.resolve("app/documents"));
        Files.writeString(documents.resolve("notes.txt"), "kept");
        Files.writeString(documents.resolve("scratch.tmp"), "excluded");

        manager = DemoBackupSetup.createManager(
                root, database.getFile(), documents, "test-passphrase");
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void configuresBothJobs() {
        assertThat(manager.getJobs()).hasSize(2);
        assertThat(manager.getJob("customers-db")).isPresent();
        assertThat(manager.getJob("documents")).isPresent();
    }

    @Test
    void backsUpTheDatabaseToBothDestinations() {
        BackupRecord record = manager.backupNow("customers-db");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.isEncrypted()).isTrue();
        assertThat(record.availableLocations())
                .extracting(l -> l.getProviderName())
                .containsExactlyInAnyOrder("local", "copy");
    }

    @Test
    void restoresTheDatabaseAfterDataLoss() throws Exception {
        assertThat(database.countRows()).isEqualTo(500);
        BackupRecord record = manager.backupNow("customers-db");
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);

        database.addCustomer("added after the backup");
        assertThat(database.countRows()).isEqualTo(501);

        manager.restore(record.getId());

        assertThat(database.countRows()).isEqualTo(500);
    }

    @Test
    void documentsBackupHonoursTheExclusion() {
        BackupRecord record = manager.backupNow("documents");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        // notes.txt only; the .tmp file is filtered out.
        assertThat(record.getAttributes()).containsEntry("fileCount", "1");
    }

    @Test
    void documentsRestoreGoesToASeparateFolderNotOverTheOriginal() throws IOException {
        BackupRecord record = manager.backupNow("documents");

        manager.restore(record.getId());

        assertThat(root.resolve("restored-documents/documents/notes.txt")).exists();
        // The live folder is untouched, including the file that was never backed up.
        assertThat(documents.resolve("scratch.tmp")).exists();
    }

    @Test
    void everyBackupVerifiesEndToEnd() {
        BackupRecord record = manager.backupNow("customers-db");

        assertThat(manager.testRestore(record.getId(), null)).isTrue();
    }

    @Test
    void schedulesAreAutomatic() {
        manager.start();

        assertThat(manager.nextRunOf("customers-db")).isPresent();
        assertThat(manager.nextRunOf("documents")).isPresent();
    }
}
