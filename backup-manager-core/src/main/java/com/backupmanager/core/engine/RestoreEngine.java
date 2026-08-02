package com.backupmanager.core.engine;

import com.backupmanager.core.api.BackupCatalog;
import com.backupmanager.core.api.Compressor;
import com.backupmanager.core.api.Encryptor;
import com.backupmanager.core.api.ProgressCallback;
import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.StorageProvider;
import com.backupmanager.core.compress.Compressors;
import com.backupmanager.core.crypto.NoEncryption;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupJob;
import com.backupmanager.core.model.BackupPhase;
import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.StoredLocation;
import com.backupmanager.core.util.Checksums;
import com.backupmanager.core.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reverses the backup pipeline: download, verify, decrypt, decompress, hand the payload
 * back to the source.
 *
 * <p>Restoring an incremental backup replays the whole chain — the anchoring full backup
 * first, then each increment in order — because an increment alone contains only what
 * changed.
 */
public class RestoreEngine {

    private static final Logger log = LoggerFactory.getLogger(RestoreEngine.class);

    private final BackupCatalog catalog;
    private final ListenerDispatcher listeners;
    private final Path workRoot;

    public RestoreEngine(BackupCatalog catalog, ListenerDispatcher listeners, Path workRoot) {
        this.catalog = catalog;
        this.listeners = listeners != null ? listeners : new ListenerDispatcher();
        this.workRoot = workRoot;
    }

    /**
     * Restores {@code record} using the job's source, codecs and destinations.
     *
     * <p>The job supplies the decryption key and the destination to download from; the
     * record supplies which codec was used, so an artifact still restores after the job's
     * compression setting has been changed.
     */
    public void restore(BackupJob job, BackupRecord record, RestoreOptions options,
                        ProgressCallback progress, AtomicBoolean cancelFlag) {

        ProgressCallback callback = progress != null ? progress : ProgressCallback.NOOP;
        ProgressCallback combined = callback.andThen(listeners::onProgress);
        AtomicBoolean cancelled = cancelFlag != null ? cancelFlag : new AtomicBoolean(false);
        RestoreOptions opts = options != null ? options : new RestoreOptions();

        listeners.onRestoreStarted(record);
        Path workDir = null;
        try {
            if (!record.getStatus().isRestorable()) {
                throw new BackupException(
                        "Backup " + record.getFileName() + " is not restorable (status "
                                + record.getStatus() + ")");
            }
            workDir = Files.createDirectories(workRoot.resolve("restore-" + record.getId()));

            List<BackupRecord> chain = resolveChain(record);
            if (chain.size() > 1) {
                log.info("Restoring a chain of {} backups, oldest first", chain.size());
            }

            for (int i = 0; i < chain.size(); i++) {
                BackupRecord link = chain.get(i);
                if (cancelled.get()) {
                    throw new InterruptedException("Restore cancelled");
                }
                combined.report(job.getName(), BackupPhase.PREPARING,
                        "Restoring " + (i + 1) + " of " + chain.size() + ": " + link.getFileName());
                restoreSingle(job, link, opts, workDir, combined, cancelled,
                        // Only the first link may replace existing data; later links merge
                        // into what the earlier ones just wrote.
                        i == 0 && opts.isReplaceExisting());
            }

            combined.report(job.getName(), BackupPhase.DONE, "Restore complete");
            listeners.onRestoreCompleted(record);
            log.info("Restored {} for job '{}'", record.getFileName(), job.getName());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listeners.onRestoreFailed(record, e);
            throw new BackupException("Restore cancelled", e);
        } catch (Exception e) {
            log.error("Restore of {} failed: {}", record.getFileName(), e.toString(), e);
            combined.report(job.getName(), BackupPhase.FAILED, BackupEngine.rootMessage(e));
            listeners.onRestoreFailed(record, e);
            throw e instanceof BackupException be
                    ? be
                    : new BackupException("Restore failed: " + BackupEngine.rootMessage(e), e);
        } finally {
            FileNames.deleteQuietly(workDir);
        }
    }

    private void restoreSingle(BackupJob job, BackupRecord record, RestoreOptions options,
                               Path workDir, ProgressCallback progress, AtomicBoolean cancelled,
                               boolean replaceExisting) throws Exception {

        Path local = downloadArtifact(job, record, workDir, progress);

        if (options.isVerifyChecksum() && record.getChecksum() != null) {
            progress.report(job.getName(), BackupPhase.VERIFYING, "Verifying " + record.getFileName());
            String actual = Checksums.ofFile(local, record.getChecksumAlgorithm());
            if (!Checksums.matches(record.getChecksum(), actual)) {
                throw new BackupException.Verification(
                        "Checksum mismatch for " + record.getFileName()
                                + ". The artifact is corrupt and restoring it would damage the target."
                                + " Expected " + record.getChecksum() + " but read " + actual + ".");
            }
        }

        Encryptor encryptor = record.isEncrypted() ? job.getEncryptor() : NoEncryption.INSTANCE;
        if (record.isEncrypted() && !encryptor.isEnabled()) {
            throw new BackupException.Crypto(
                    "Backup " + record.getFileName() + " is encrypted but the job has no encryption"
                            + " configured. Set the same password that produced it.", null);
        }
        // The codec comes from the record, not the job: a job whose compression setting
        // changed must still be able to read what it wrote last month.
        Compressor compressor = Compressors.byId(record.getCompressorId());

        RestoreContext ctx = new RestoreContext(record, workDir, progress, cancelled)
                .setReplaceExisting(replaceExisting)
                .setContinueOnError(options.isContinueOnError())
                .setTargetOverride(options.getTargetOverride())
                .setDryRun(options.isDryRun());

        BackupSource source = job.getSource();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(local), 1 << 16);
             InputStream decrypted = encryptor.decrypt(fileIn);
             InputStream decompressed = compressor.decompress(decrypted)) {
            source.restore(ctx, decompressed);
        }
    }

    private Path downloadArtifact(BackupJob job, BackupRecord record, Path workDir,
                                  ProgressCallback progress) throws Exception {
        Path local = workDir.resolve(record.getFileName());
        List<String> failures = new ArrayList<>();

        for (StoredLocation location : record.availableLocations()) {
            StorageProvider provider = job.getDestinations().stream()
                    .filter(p -> p.name().equals(location.getProviderName()))
                    .findFirst().orElse(null);
            if (provider == null) {
                failures.add(location.getProviderName() + ": destination is not configured");
                continue;
            }
            try {
                progress.report(job.getName(), BackupPhase.PREPARING,
                        "Fetching " + record.getFileName() + " from " + provider.name());
                provider.fetch(location.getPath(), local, progress);
                return local;
            } catch (Exception e) {
                // Fall through to the next copy: that is the entire point of having several.
                log.warn("Could not fetch {} from {}: {}",
                        record.getFileName(), provider.name(), e.toString());
                failures.add(provider.name() + ": " + BackupEngine.rootMessage(e));
            }
        }
        throw new BackupException("No copy of " + record.getFileName()
                + " could be retrieved. Tried: " + String.join("; ", failures));
    }

    /**
     * Walks back to the anchoring full backup and returns the chain oldest first.
     *
     * @throws BackupException when a link is missing, since a partial replay would leave
     *         the target silently inconsistent
     */
    public List<BackupRecord> resolveChain(BackupRecord record) {
        Deque<BackupRecord> chain = new ArrayDeque<>();
        BackupRecord current = record;
        int guard = 0;

        while (current != null) {
            chain.addFirst(current);
            if (!current.getType().requiresChain()) {
                return new ArrayList<>(chain);
            }
            String baseId = current.getBaseRecordId();
            if (baseId == null) {
                throw new BackupException(
                        "Backup " + current.getFileName() + " is " + current.getType()
                                + " but records no base backup; the chain is broken.");
            }
            if (catalog == null) {
                throw new BackupException("A catalog is required to restore an incremental backup");
            }
            BackupRecord base = catalog.findById(baseId).orElseThrow(() -> new BackupException(
                    "The chain for " + record.getFileName() + " is broken: base backup "
                            + baseId + " is no longer in the catalog."));
            if (++guard > 1000) {
                throw new BackupException("Backup chain for " + record.getFileName()
                        + " is longer than 1000 links or contains a cycle");
            }
            current = base;
        }
        return new ArrayList<>(chain);
    }

    /** Reads and decodes an artifact end to end without writing anything, to prove it is intact. */
    public boolean testRestore(BackupJob job, BackupRecord record, ProgressCallback progress) {
        try {
            RestoreOptions options = new RestoreOptions()
                    .setDryRun(true)
                    .setVerifyChecksum(true);
            restore(job, record, options, progress, new AtomicBoolean(false));
            return true;
        } catch (RuntimeException e) {
            log.warn("Test restore of {} failed: {}", record.getFileName(), e.toString());
            return false;
        }
    }

    /** Knobs for a restore run. */
    public static final class RestoreOptions {

        private boolean replaceExisting = true;
        private boolean continueOnError;
        private boolean verifyChecksum = true;
        private boolean dryRun;
        private String targetOverride;

        public boolean isReplaceExisting() {
            return replaceExisting;
        }

        /** False merges into existing data instead of overwriting it. */
        public RestoreOptions setReplaceExisting(boolean replaceExisting) {
            this.replaceExisting = replaceExisting;
            return this;
        }

        public boolean isContinueOnError() {
            return continueOnError;
        }

        public RestoreOptions setContinueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public boolean isVerifyChecksum() {
            return verifyChecksum;
        }

        /** Leave on unless the artifact is so large that hashing it twice is prohibitive. */
        public RestoreOptions setVerifyChecksum(boolean verifyChecksum) {
            this.verifyChecksum = verifyChecksum;
            return this;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public RestoreOptions setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public String getTargetOverride() {
            return targetOverride;
        }

        /** Restores into a different database or folder than the one backed up. */
        public RestoreOptions setTargetOverride(String targetOverride) {
            this.targetOverride = targetOverride;
            return this;
        }
    }
}
