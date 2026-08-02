package com.backupmanager.core;

import com.backupmanager.core.compress.Bzip2Compressor;
import com.backupmanager.core.compress.GzipCompressor;
import com.backupmanager.core.compress.NoCompression;
import com.backupmanager.core.compress.ZipCompressor;
import com.backupmanager.core.engine.RestoreEngine;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupJob;
import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupStatus;
import com.backupmanager.core.model.BackupType;
import com.backupmanager.core.model.RetentionRule;
import com.backupmanager.core.source.FileSystemBackupSource;
import com.backupmanager.core.storage.LocalStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of the pipeline: real files in, real artifacts on disk, real restore
 * back out. These are the tests that would catch a stage wired in the wrong order.
 */
class BackupManagerIntegrationTest {

    @TempDir
    Path temp;

    private Path sourceDir;
    private Path backupDir;
    private Path managerRoot;
    private BackupManager manager;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = Files.createDirectories(temp.resolve("source"));
        backupDir = Files.createDirectories(temp.resolve("backups"));
        managerRoot = Files.createDirectories(temp.resolve("manager"));

        Files.writeString(sourceDir.resolve("invoices.txt"), "invoice data\n".repeat(500));
        Files.writeString(sourceDir.resolve("customers.txt"), "customer data\n".repeat(300));
        Files.createDirectories(sourceDir.resolve("nested"));
        Files.writeString(sourceDir.resolve("nested/notes.txt"), "nested note");
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private BackupManager managerWith(BackupJob job) {
        manager = BackupManager.builder()
                .storageRoot(managerRoot)
                .job(job)
                .build();
        return manager;
    }

    private BackupJob.Builder jobBuilder() {
        return BackupJob.builder("files")
                .source(new FileSystemBackupSource(sourceDir))
                .destination(new LocalStorageProvider("disk", backupDir));
    }

    @Test
    void backsUpAndRestoresFiles() throws IOException {
        Path restoreTarget = temp.resolve("restored");
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(restoreTarget))
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.getChecksum()).isNotBlank();
        assertThat(record.getStoredSize()).isPositive();
        assertThat(record.getRawSize()).isGreaterThan(record.getStoredSize());
        assertThat(record.availableLocations()).hasSize(1);

        manager.restore(record.getId());

        // The archive nests entries under the root folder's name.
        Path restoredRoot = restoreTarget.resolve("source");
        assertThat(Files.readString(restoredRoot.resolve("invoices.txt")))
                .isEqualTo("invoice data\n".repeat(500));
        assertThat(Files.readString(restoredRoot.resolve("nested/notes.txt")))
                .isEqualTo("nested note");
    }

    static Stream<Object[]> codecs() {
        return Stream.of(
                new Object[]{"none", NoCompression.INSTANCE},
                new Object[]{"gzip", new GzipCompressor()},
                new Object[]{"zip", new ZipCompressor()},
                new Object[]{"bzip2", new Bzip2Compressor()});
    }

    @ParameterizedTest(name = "round-trips through {0}")
    @MethodSource("codecs")
    void roundTripsThroughEveryCodec(String name,
                                     com.backupmanager.core.api.Compressor compressor) throws IOException {
        Path restoreTarget = temp.resolve("restored-" + name);
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(restoreTarget))
                .compressor(compressor)
                .build());

        BackupRecord record = manager.backupNow("files");
        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.getCompressorId()).isEqualTo(name);

        manager.restore(record.getId());

        assertThat(Files.readString(restoreTarget.resolve("source/nested/notes.txt")))
                .isEqualTo("nested note");
    }

    @Test
    void encryptsAndRestoresWithTheSamePassword() throws IOException {
        Path restoreTarget = temp.resolve("restored-encrypted");
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(restoreTarget))
                .encryptWithPassword("s3cret-passphrase")
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.isEncrypted()).isTrue();
        assertThat(record.getFileName()).endsWith(".enc");

        // The plaintext must not be visible in the stored artifact.
        Path artifact = Path.of(record.availableLocations().get(0).getPath());
        String rawBytes = new String(Files.readAllBytes(artifact), StandardCharsets.ISO_8859_1);
        assertThat(rawBytes).doesNotContain("invoice data");

        manager.restore(record.getId());
        assertThat(Files.readString(restoreTarget.resolve("source/nested/notes.txt")))
                .isEqualTo("nested note");
    }

    @Test
    void refusesToRestoreEncryptedBackupWithTheWrongPassword() {
        managerWith(jobBuilder().encryptWithPassword("right-password").build());
        BackupRecord record = manager.backupNow("files");
        manager.close();

        // Same catalog and artifacts, different password.
        manager = BackupManager.builder()
                .storageRoot(managerRoot)
                .job(BackupJob.builder("files")
                        .source(new FileSystemBackupSource(sourceDir).restoreTo(temp.resolve("nope")))
                        .destination(new LocalStorageProvider("disk", backupDir))
                        .encryptWithPassword("wrong-password")
                        .build())
                .build();

        assertThatThrownBy(() -> manager.restore(record.getId()))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("password");
    }

    @Test
    void detectsACorruptedArtifactBeforeTouchingTheTarget() throws IOException {
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(temp.resolve("restored-corrupt")))
                .verifyAfterStore(false)
                .build());
        BackupRecord record = manager.backupNow("files");

        // Corrupt the stored copy after the fact, as bit rot or a bad disk would.
        Path artifact = Path.of(record.availableLocations().get(0).getPath());
        byte[] bytes = Files.readAllBytes(artifact);
        bytes[bytes.length / 2] ^= 0x7F;
        Files.write(artifact, bytes);

        assertThatThrownBy(() -> manager.restore(record.getId()))
                .isInstanceOf(BackupException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void writesToEveryDestination() throws IOException {
        Path secondDir = Files.createDirectories(temp.resolve("backups2"));
        managerWith(jobBuilder()
                .destination(new LocalStorageProvider("disk2", secondDir))
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.availableLocations()).hasSize(2);
        assertThat(Files.list(backupDir.resolve("files")).count()).isEqualTo(1);
        assertThat(Files.list(secondDir.resolve("files")).count()).isEqualTo(1);
    }

    @Test
    void marksTheRunPartialWhenOneDestinationFails() {
        managerWith(jobBuilder()
                .destination(new FailingStorageProvider("broken"))
                .maxRetries(0)
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.PARTIAL);
        assertThat(record.availableLocations()).hasSize(1);
        assertThat(record.getErrorMessage()).contains("broken");
    }

    @Test
    void failsTheRunWhenAllDestinationsAreRequired() {
        managerWith(jobBuilder()
                .destination(new FailingStorageProvider("broken"))
                .requireAllDestinations(true)
                .maxRetries(0)
                .build());

        assertThat(manager.backupNow("files").getStatus()).isEqualTo(BackupStatus.FAILED);
    }

    @Test
    void survivesTheLossOfEveryDestination() {
        managerWith(BackupJob.builder("files")
                .source(new FileSystemBackupSource(sourceDir))
                .destination(new FailingStorageProvider("broken"))
                .maxRetries(0)
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(manager.history("files")).hasSize(1);
    }

    @Test
    void appliesRetentionAfterEachRun() {
        managerWith(jobBuilder()
                .retention(RetentionRule.keepLast(2).setMinimumKept(0))
                .build());

        for (int i = 0; i < 4; i++) {
            manager.backupNow("files");
            // Artifact names carry a whole-second timestamp; keep them distinct.
            sleep(1100);
        }

        assertThat(manager.history("files")).hasSize(2);
        assertThat(listArtifacts()).hasSize(2);
    }

    @Test
    void backsUpOnlyChangedFilesInAnIncrementalRun() throws IOException {
        managerWith(jobBuilder().build());

        BackupRecord full = manager.backupNow("files", BackupType.FULL, null);
        assertThat(full.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        sleep(1100);

        Files.writeString(sourceDir.resolve("new-file.txt"), "added later");
        BackupRecord incremental = manager.backupNow("files", BackupType.INCREMENTAL, null);

        assertThat(incremental.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(incremental.getType()).isEqualTo(BackupType.INCREMENTAL);
        assertThat(incremental.getBaseRecordId()).isEqualTo(full.getId());
        // Only the new file was archived, so the increment is far smaller.
        assertThat(incremental.getRawSize()).isLessThan(full.getRawSize());
    }

    @Test
    void restoringAnIncrementalReplaysTheWholeChain() throws IOException {
        Path restoreTarget = temp.resolve("restored-chain");
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(restoreTarget))
                .build());

        manager.backupNow("files", BackupType.FULL, null);
        sleep(1100);
        Files.writeString(sourceDir.resolve("late-arrival.txt"), "arrived after the full backup");
        BackupRecord incremental = manager.backupNow("files", BackupType.INCREMENTAL, null);

        manager.restore(incremental.getId());

        Path restoredRoot = restoreTarget.resolve("source");
        // Both the original files and the increment's addition must be present.
        assertThat(restoredRoot.resolve("invoices.txt")).exists();
        assertThat(Files.readString(restoredRoot.resolve("late-arrival.txt")))
                .isEqualTo("arrived after the full backup");
    }

    @Test
    void promotesAnIncrementalToFullWhenNoBaseExists() {
        managerWith(jobBuilder().build());

        BackupRecord record = manager.backupNow("files", BackupType.INCREMENTAL, null);

        assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(record.getType()).isEqualTo(BackupType.FULL);
    }

    @Test
    void testRestoreValidatesWithoutWritingAnything() throws IOException {
        Path restoreTarget = temp.resolve("never-written");
        managerWith(jobBuilder()
                .source(new FileSystemBackupSource(sourceDir).restoreTo(restoreTarget))
                .encryptWithPassword("pw")
                .build());
        BackupRecord record = manager.backupNow("files");

        assertThat(manager.testRestore(record.getId(), null)).isTrue();
        assertThat(Files.exists(restoreTarget)).isFalse();
    }

    @Test
    void excludesMatchingPaths() {
        Path restoreTarget = temp.resolve("restored-filtered");
        managerWith(BackupJob.builder("files")
                .source(new FileSystemBackupSource(sourceDir)
                        .exclude("**/*.txt")
                        .restoreTo(restoreTarget))
                .destination(new LocalStorageProvider("disk", backupDir))
                .build());

        BackupRecord record = manager.backupNow("files");

        assertThat(record.getAttributes()).containsEntry("fileCount", "0");
    }

    @Test
    void keepsHistoryAcrossRestarts() {
        managerWith(jobBuilder().build());
        manager.backupNow("files");
        manager.close();

        manager = BackupManager.builder()
                .storageRoot(managerRoot)
                .job(jobBuilder().build())
                .build();

        assertThat(manager.history("files")).hasSize(1);
    }

    @Test
    void reportsUnknownJobsClearly() {
        managerWith(jobBuilder().build());

        assertThatThrownBy(() -> manager.backupNow("does-not-exist"))
                .isInstanceOf(BackupException.Configuration.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void deletesABackupFromDiskAndCatalog() {
        managerWith(jobBuilder().build());
        BackupRecord record = manager.backupNow("files");
        assertThat(listArtifacts()).hasSize(1);

        assertThat(manager.deleteBackup(record.getId())).isTrue();

        assertThat(manager.history("files")).isEmpty();
        assertThat(listArtifacts()).isEmpty();
    }

    @Test
    void statusSummarisesEachJob() {
        managerWith(jobBuilder().cron("0 0 3 * * *").build());
        manager.start();
        manager.backupNow("files");

        var status = manager.status().get("files");

        assertThat(status.enabled()).isTrue();
        assertThat(status.lastStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(status.totalBackups()).isEqualTo(1);
        assertThat(status.nextRun()).isNotNull();
    }

    @Test
    void restoreOptionsCanTargetADifferentDirectory() throws IOException {
        managerWith(jobBuilder().build());
        BackupRecord record = manager.backupNow("files");
        Path elsewhere = temp.resolve("staging-copy");

        manager.restore(record.getId(),
                new RestoreEngine.RestoreOptions().setTargetOverride(elsewhere.toString()), null);

        assertThat(elsewhere.resolve("source/invoices.txt")).exists();
        // The original is untouched.
        assertThat(sourceDir.resolve("invoices.txt")).exists();
    }

    private List<Path> listArtifacts() {
        try (var files = Files.list(backupDir.resolve("files"))) {
            return files.toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Destination that always rejects, to exercise partial and total upload failure. */
    private static final class FailingStorageProvider
            implements com.backupmanager.core.api.StorageProvider {

        private final String name;

        FailingStorageProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String store(Path localFile, String remotePath,
                            com.backupmanager.core.api.ProgressCallback progress) throws IOException {
            throw new IOException("destination is offline");
        }

        @Override
        public void fetch(String remotePath, Path localTarget,
                          com.backupmanager.core.api.ProgressCallback progress) throws IOException {
            throw new IOException("destination is offline");
        }

        @Override
        public boolean exists(String remotePath) {
            return false;
        }

        @Override
        public void delete(String remotePath) {
        }

        @Override
        public List<RemoteEntry> list(String prefix) {
            return List.of();
        }
    }
}
