package io.github.mohamedsalah13.backupmanager.core.engine;

import io.github.mohamedsalah13.backupmanager.core.api.BackupCatalog;
import io.github.mohamedsalah13.backupmanager.core.api.BackupContext;
import io.github.mohamedsalah13.backupmanager.core.api.BackupSource;
import io.github.mohamedsalah13.backupmanager.core.api.ProgressCallback;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupStatus;
import io.github.mohamedsalah13.backupmanager.core.model.BackupType;
import io.github.mohamedsalah13.backupmanager.core.model.StoredLocation;
import io.github.mohamedsalah13.backupmanager.core.util.Checksums;
import io.github.mohamedsalah13.backupmanager.core.util.CountingOutputStream;
import io.github.mohamedsalah13.backupmanager.core.util.FileNames;
import io.github.mohamedsalah13.backupmanager.core.util.NonClosingOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the backup pipeline: dump the source, compress, encrypt, checksum, copy to every
 * destination, record the result and apply retention.
 *
 * <p>The artifact is staged to a local temporary file before it is uploaded. Streaming
 * straight to each destination would be leaner, but staging buys three things that matter
 * more: the checksum is known before anything is published, a second destination costs
 * one more copy rather than one more dump, and a failed upload can be retried without
 * touching the source database again.
 */
public class BackupEngine {

    private static final Logger log = LoggerFactory.getLogger(BackupEngine.class);

    private final BackupCatalog catalog;
    private final ListenerDispatcher listeners;
    private final Path workRoot;
    private final Clock clock;
    private final ZoneId zone;

    public BackupEngine(BackupCatalog catalog, ListenerDispatcher listeners, Path workRoot) {
        this(catalog, listeners, workRoot, Clock.systemDefaultZone());
    }

    public BackupEngine(BackupCatalog catalog, ListenerDispatcher listeners, Path workRoot, Clock clock) {
        this.catalog = catalog;
        this.listeners = listeners != null ? listeners : new ListenerDispatcher();
        this.workRoot = workRoot;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.zone = this.clock.getZone();
    }

    /**
     * Executes a job, retrying up to {@link BackupJob#getMaxRetries()} times.
     *
     * @return the record, saved in the catalog whatever the outcome — a failed run is
     *         evidence and belongs in the history too
     */
    public BackupRecord run(BackupJob job, BackupType type,
                            ProgressCallback progress, AtomicBoolean cancelFlag) {

        BackupType effectiveType = type != null ? type : job.getDefaultType();
        AtomicBoolean cancelled = cancelFlag != null ? cancelFlag : new AtomicBoolean(false);
        ProgressCallback callback = progress != null ? progress : ProgressCallback.NOOP;
        ProgressCallback combined = callback.andThen(listeners::onProgress);

        int attempts = job.getMaxRetries() + 1;
        BackupRecord lastRecord = null;
        Throwable lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (cancelled.get()) {
                break;
            }
            if (attempt > 1) {
                log.info("Retrying job '{}' (attempt {} of {})", job.getName(), attempt, attempts);
                if (!sleepBetweenRetries(job, cancelled)) {
                    break;
                }
            }
            try {
                BackupRecord record = runOnce(job, effectiveType, combined, cancelled);
                if (record.getStatus() == BackupStatus.SUCCESS
                        || record.getStatus() == BackupStatus.CANCELLED) {
                    return record;
                }
                // PARTIAL and FAILED are worth another attempt; keep the record either way.
                lastRecord = record;
                lastError = record.getErrorMessage() != null
                        ? new BackupException(record.getErrorMessage()) : null;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Job '{}' attempt {} failed: {}", job.getName(), attempt, e.toString());
            }
        }

        if (lastRecord != null) {
            return lastRecord;
        }
        BackupRecord failed = newRecord(job, effectiveType);
        failed.setStatus(BackupStatus.FAILED);
        failed.setFinishedAt(clock.instant());
        failed.setErrorMessage(lastError != null ? lastError.toString() : "Backup failed");
        saveQuietly(failed);
        listeners.onFailed(failed, lastError);
        return failed;
    }

    private boolean sleepBetweenRetries(BackupJob job, AtomicBoolean cancelled) {
        long millis = job.getRetryDelay() != null ? job.getRetryDelay().toMillis() : 0;
        long waited = 0;
        while (waited < millis) {
            if (cancelled.get()) {
                return false;
            }
            try {
                long slice = Math.min(500, millis - waited);
                Thread.sleep(slice);
                waited += slice;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private BackupRecord runOnce(BackupJob job, BackupType type,
                                 ProgressCallback progress, AtomicBoolean cancelled) {

        BackupRecord record = newRecord(job, type);
        listeners.onStarted(job.getName(), record);
        record.setStatus(BackupStatus.RUNNING);

        Path workDir = null;
        try {
            progress.report(job.getName(), BackupPhase.PREPARING, "Validating source and destinations");
            BackupSource source = job.getSource();
            source.validate();
            initDestinations(job);

            BackupRecord baseRecord = resolveBaseRecord(job, type).orElse(null);
            if (type.requiresChain() && baseRecord == null) {
                log.info("No base backup for job '{}'; promoting this run to FULL", job.getName());
                type = BackupType.FULL;
                record.setType(BackupType.FULL);
            }
            if (baseRecord != null) {
                record.setBaseRecordId(baseRecord.getId());
            }

            workDir = Files.createDirectories(
                    workRoot.resolve("run-" + record.getId()));

            String artifactName = FileNames.artifactName(
                    job.getName(), record.getStartedAt(), zone,
                    source.defaultExtension(),
                    job.getCompressor().extension(),
                    job.getEncryptor().extension());
            record.setFileName(artifactName);
            record.setCompressorId(job.getCompressor().id());
            record.setEncrypted(job.getEncryptor().isEnabled());

            Path staging = workDir.resolve(artifactName);

            // --- dump -> compress -> encrypt -> digest -> staging file ---
            progress.report(job.getName(), BackupPhase.DUMPING, "Reading " + source.describe());
            long rawBytes = writeArtifact(job, source, type, baseRecord, staging, record, progress, cancelled);

            if (cancelled.get()) {
                return finishCancelled(record);
            }

            record.setRawSize(rawBytes);
            record.setStoredSize(Files.size(staging));
            log.info("Job '{}' produced {} ({} raw, {} stored)", job.getName(), artifactName,
                    FileNames.humanSize(rawBytes), FileNames.humanSize(record.getStoredSize()));

            // --- fan out to destinations ---
            uploadToDestinations(job, record, staging, progress, cancelled);

            if (cancelled.get()) {
                return finishCancelled(record);
            }

            applyStatus(job, record);

            if (record.getStatus().isRestorable() && job.isVerifyAfterStore()) {
                progress.report(job.getName(), BackupPhase.VERIFYING, "Verifying stored copies");
                verifyStoredCopies(job, record, workDir, progress);
                // Verification may have invalidated copies; recompute the status.
                applyStatus(job, record);
            }

            record.setFinishedAt(clock.instant());
            saveQuietly(record);

            if (record.getStatus().isRestorable()) {
                progress.report(job.getName(), BackupPhase.APPLYING_RETENTION, "Applying retention policy");
                applyRetention(job);
                progress.report(job.getName(), BackupPhase.DONE, "Backup complete");
                listeners.onCompleted(record);
            } else {
                progress.report(job.getName(), BackupPhase.FAILED, "Backup failed");
                listeners.onFailed(record, new BackupException(
                        record.getErrorMessage() != null ? record.getErrorMessage() : "Backup failed"));
            }
            return record;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finishCancelled(record);
        } catch (Exception e) {
            log.error("Backup job '{}' failed: {}", job.getName(), e.toString(), e);
            record.setStatus(BackupStatus.FAILED);
            record.setFinishedAt(clock.instant());
            record.setErrorMessage(rootMessage(e));
            saveQuietly(record);
            progress.report(job.getName(), BackupPhase.FAILED, rootMessage(e));
            listeners.onFailed(record, e);
            return record;
        } finally {
            FileNames.deleteQuietly(workDir);
        }
    }

    /**
     * Builds the output chain and hands its outermost stream to the source.
     *
     * <p>Order is deliberate. Compression runs before encryption because ciphertext is
     * incompressible, and the digest sits at the very bottom so it covers exactly the
     * bytes that reach storage — which is what a later verification can re-read.
     */
    private long writeArtifact(BackupJob job, BackupSource source, BackupType type,
                               BackupRecord baseRecord, Path staging, BackupRecord record,
                               ProgressCallback progress, AtomicBoolean cancelled) throws Exception {

        BackupContext ctx = new BackupContext(
                job.getName(), type, staging.getParent(), progress, cancelled, baseRecord);

        long rawBytes;
        DigestOutputStream digest;

        try (OutputStream fileOut = Files.newOutputStream(staging)) {
            digest = Checksums.digesting(fileOut, Checksums.DEFAULT_ALGORITHM);
            OutputStream encrypted = job.getEncryptor().encrypt(digest);
            OutputStream compressed = job.getCompressor().compress(encrypted);
            CountingOutputStream counting = new CountingOutputStream(compressed,
                    written -> progress.report(job.getName(), BackupPhase.DUMPING,
                            "Read " + FileNames.humanSize(written), written, source.estimateSize()));

            try {
                // Sources are documented not to close what they are given, but a wrapper
                // costs nothing and removes the failure mode entirely.
                source.dump(ctx, new NonClosingOutputStream(counting));
            } finally {
                // Closing the outermost stream flushes each stage's trailer in order:
                // the compressor's, then the encryption terminator, then the digest.
                counting.close();
            }
            rawBytes = counting.getCount();
        }

        record.setChecksumAlgorithm(Checksums.DEFAULT_ALGORITHM);
        record.setChecksum(Checksums.hex(digest.getMessageDigest()));
        record.getAttributes().putAll(ctx.getProducedAttributes());
        return rawBytes;
    }

    private void uploadToDestinations(BackupJob job, BackupRecord record, Path staging,
                                      ProgressCallback progress, AtomicBoolean cancelled) {

        String remotePath = FileNames.remotePath(job.getPathPrefix(), record.getFileName());

        for (StorageProvider destination : job.getDestinations()) {
            if (cancelled.get()) {
                return;
            }
            progress.report(job.getName(), BackupPhase.UPLOADING, "Storing on " + destination.name());
            try {
                String stored = destination.store(staging, remotePath, progress);
                record.addLocation(StoredLocation.success(
                        destination.name(), stored, record.getStoredSize()));
                log.info("Job '{}' stored on '{}' at {}", job.getName(), destination.name(), stored);
            } catch (Exception e) {
                // One unreachable destination must not discard copies that did succeed.
                log.error("Job '{}' could not store on '{}': {}",
                        job.getName(), destination.name(), e.toString());
                record.addLocation(StoredLocation.failure(
                        destination.name(), remotePath, rootMessage(e)));
            }
        }
    }

    private void applyStatus(BackupJob job, BackupRecord record) {
        long available = record.availableLocations().size();
        int total = job.getDestinations().size();

        if (available == 0) {
            record.setStatus(BackupStatus.FAILED);
            record.setErrorMessage(firstFailureMessage(record)
                    .orElse("No destination accepted the backup"));
        } else if (available < total) {
            if (job.isRequireAllDestinations()) {
                record.setStatus(BackupStatus.FAILED);
                record.setErrorMessage(firstFailureMessage(record)
                        .orElse("Not every destination accepted the backup"));
            } else {
                record.setStatus(BackupStatus.PARTIAL);
                record.setErrorMessage(firstFailureMessage(record).orElse(null));
            }
        } else {
            record.setStatus(BackupStatus.SUCCESS);
            record.setErrorMessage(null);
        }
    }

    private Optional<String> firstFailureMessage(BackupRecord record) {
        return record.getLocations().stream()
                .filter(l -> !l.isAvailable())
                .map(l -> l.getProviderName() + ": " + l.getFailureMessage())
                .findFirst();
    }

    /**
     * Re-downloads each stored copy and compares its checksum.
     *
     * <p>This is the step that turns "the upload call returned" into "the bytes are really
     * there and really correct" — the difference that matters on the day a restore is
     * needed. It costs a full download per destination, so it is worth switching off for
     * very large artifacts on metered links.
     */
    private void verifyStoredCopies(BackupJob job, BackupRecord record, Path workDir,
                                    ProgressCallback progress) {
        for (StoredLocation location : record.getLocations()) {
            if (!location.isAvailable()) {
                continue;
            }
            StorageProvider provider = findProvider(job, location.getProviderName());
            if (provider == null) {
                continue;
            }
            Path temp = workDir.resolve("verify-" + location.getProviderName() + ".tmp");
            try {
                provider.fetch(location.getPath(), temp, progress);
                String actual = Checksums.ofFile(temp, record.getChecksumAlgorithm());
                if (!Checksums.matches(record.getChecksum(), actual)) {
                    location.markUnavailable("Checksum mismatch after upload; the stored copy is corrupt");
                    log.error("Verification failed for {} on {}: expected {} but read {}",
                            record.getFileName(), location.getProviderName(),
                            record.getChecksum(), actual);
                } else {
                    log.debug("Verified {} on {}", record.getFileName(), location.getProviderName());
                }
            } catch (Exception e) {
                location.markUnavailable("Verification failed: " + rootMessage(e));
                log.error("Could not verify {} on {}: {}",
                        record.getFileName(), location.getProviderName(), e.toString());
            } finally {
                FileNames.deleteQuietly(temp);
            }
        }
    }

    private StorageProvider findProvider(BackupJob job, String name) {
        return job.getDestinations().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void initDestinations(BackupJob job) throws IOException {
        for (StorageProvider destination : job.getDestinations()) {
            destination.init();
        }
    }

    private Optional<BackupRecord> resolveBaseRecord(BackupJob job, BackupType type) {
        if (!type.requiresChain() || catalog == null) {
            return Optional.empty();
        }
        return type == BackupType.DIFFERENTIAL
                ? catalog.findLatestFull(job.getName())
                : catalog.findLatestSuccessful(job.getName());
    }

    /** Deletes what the policy selects, on every destination, then drops the catalog entry. */
    public int applyRetention(BackupJob job) {
        if (catalog == null || job.getRetention() == null) {
            return 0;
        }
        List<BackupRecord> existing = catalog.findByJob(job.getName());
        List<BackupRecord> doomed = job.getRetention().selectForDeletion(existing, clock);
        if (doomed.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        for (BackupRecord record : doomed) {
            boolean allRemoved = true;
            for (StoredLocation location : record.getLocations()) {
                StorageProvider provider = job.getDestinations().stream()
                        .filter(p -> p.name().equals(location.getProviderName()))
                        .findFirst().orElse(null);
                if (provider == null) {
                    // The destination is no longer configured; leave its copy untouched
                    // rather than orphaning a file we cannot see.
                    log.warn("Retention: destination '{}' is not configured, leaving {} in place",
                            location.getProviderName(), location.getPath());
                    allRemoved = false;
                    continue;
                }
                try {
                    provider.delete(location.getPath());
                } catch (Exception e) {
                    log.warn("Retention: could not delete {} from {}: {}",
                            location.getPath(), location.getProviderName(), e.toString());
                    allRemoved = false;
                }
            }
            if (allRemoved) {
                catalog.delete(record.getId());
                deleted++;
                listeners.onDeleted(record, job.getRetention().describe());
            }
        }
        if (deleted > 0) {
            log.info("Retention removed {} backup(s) for job '{}' ({})",
                    deleted, job.getName(), job.getRetention().describe());
        }
        return deleted;
    }

    private BackupRecord newRecord(BackupJob job, BackupType type) {
        BackupRecord record = new BackupRecord();
        record.setJobName(job.getName());
        record.setSourceType(job.getSource().type());
        record.setType(type);
        record.setStartedAt(clock.instant());
        return record;
    }

    private BackupRecord finishCancelled(BackupRecord record) {
        record.setStatus(BackupStatus.CANCELLED);
        record.setFinishedAt(clock.instant());
        record.setErrorMessage("Cancelled by request");
        saveQuietly(record);
        listeners.onCancelled(record);
        return record;
    }

    private void saveQuietly(BackupRecord record) {
        if (catalog == null) {
            return;
        }
        try {
            catalog.save(record);
        } catch (RuntimeException e) {
            log.error("Could not write the catalog entry for {}: {}", record.getFileName(), e.toString(), e);
        }
    }

    static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null && !message.isBlank()
                ? current.getClass().getSimpleName() + ": " + message
                : current.getClass().getSimpleName();
    }

    public Instant now() {
        return clock.instant();
    }
}
