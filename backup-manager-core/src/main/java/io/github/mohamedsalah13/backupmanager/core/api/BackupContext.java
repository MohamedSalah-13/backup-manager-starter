package io.github.mohamedsalah13.backupmanager.core.api;

import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a {@link BackupSource} needs while producing a dump: which job is running,
 * whether the run is incremental, where scratch files may go, and how to report progress
 * and notice cancellation.
 */
public final class BackupContext {

    private final String jobName;
    private final BackupType type;
    private final Path workDir;
    private final ProgressCallback progress;
    private final AtomicBoolean cancelled;
    private final BackupRecord baseRecord;
    private final Instant startedAt = Instant.now();
    private final Map<String, String> producedAttributes = new LinkedHashMap<>();

    public BackupContext(String jobName, BackupType type, Path workDir,
                         ProgressCallback progress, AtomicBoolean cancelled, BackupRecord baseRecord) {
        this.jobName = jobName;
        this.type = type != null ? type : BackupType.FULL;
        this.workDir = workDir;
        this.progress = progress != null ? progress : ProgressCallback.NOOP;
        this.cancelled = cancelled != null ? cancelled : new AtomicBoolean(false);
        this.baseRecord = baseRecord;
    }

    public String getJobName() {
        return jobName;
    }

    public BackupType getType() {
        return type;
    }

    /** Scratch directory owned by this run; the engine deletes it afterwards. */
    public Path getWorkDir() {
        return workDir;
    }

    public ProgressCallback getProgress() {
        return progress;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * The backup this run builds on. Present only for incremental and differential runs;
     * a source that cannot find it should fall back to a full dump.
     */
    public Optional<BackupRecord> getBaseRecord() {
        return Optional.ofNullable(baseRecord);
    }

    /** Timestamp an incremental source should compare file modification times against. */
    public Optional<Instant> getChangesSince() {
        return getBaseRecord().map(BackupRecord::getStartedAt);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Long-running sources should call this between units of work.
     *
     * @throws InterruptedException when the run was cancelled
     */
    public void checkCancelled() throws InterruptedException {
        if (cancelled.get()) {
            throw new InterruptedException("Backup cancelled: " + jobName);
        }
    }

    public void report(BackupPhase phase, String detail) {
        progress.report(jobName, phase, detail);
    }

    public void report(BackupPhase phase, String detail, long processed, long total) {
        progress.report(jobName, phase, detail, processed, total);
    }

    /**
     * Records source-specific metadata on the resulting {@link BackupRecord} — the server
     * version, the table count, a watermark for the next incremental run.
     */
    public void putAttribute(String key, String value) {
        producedAttributes.put(key, value);
    }

    public Map<String, String> getProducedAttributes() {
        return producedAttributes;
    }
}
