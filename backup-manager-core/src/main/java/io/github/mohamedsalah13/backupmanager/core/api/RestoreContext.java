package io.github.mohamedsalah13.backupmanager.core.api;

import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Options and callbacks for a restore. Restores are destructive by nature, so the flags
 * here are deliberately explicit rather than inferred.
 */
public final class RestoreContext {

    private final BackupRecord record;
    private final Path workDir;
    private final ProgressCallback progress;
    private final AtomicBoolean cancelled;

    /** Drop and recreate existing objects instead of merging into them. */
    private boolean replaceExisting;
    /** Keep going after an individual statement or entry fails. */
    private boolean continueOnError;
    /** Restore into this target instead of the one recorded in the backup. */
    private String targetOverride;
    /** Validate only: read the whole artifact and report, but change nothing. */
    private boolean dryRun;

    public RestoreContext(BackupRecord record, Path workDir,
                          ProgressCallback progress, AtomicBoolean cancelled) {
        this.record = record;
        this.workDir = workDir;
        this.progress = progress != null ? progress : ProgressCallback.NOOP;
        this.cancelled = cancelled != null ? cancelled : new AtomicBoolean(false);
    }

    public BackupRecord getRecord() {
        return record;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public ProgressCallback getProgress() {
        return progress;
    }

    public boolean isReplaceExisting() {
        return replaceExisting;
    }

    public RestoreContext setReplaceExisting(boolean replaceExisting) {
        this.replaceExisting = replaceExisting;
        return this;
    }

    public boolean isContinueOnError() {
        return continueOnError;
    }

    public RestoreContext setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
        return this;
    }

    public String getTargetOverride() {
        return targetOverride;
    }

    /** Restores elsewhere — a staging database, a scratch folder — instead of over the original. */
    public RestoreContext setTargetOverride(String targetOverride) {
        this.targetOverride = targetOverride;
        return this;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public RestoreContext setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void checkCancelled() throws InterruptedException {
        if (cancelled.get()) {
            throw new InterruptedException("Restore cancelled");
        }
    }

    public void report(BackupPhase phase, String detail) {
        progress.report(record != null ? record.getJobName() : "restore", phase, detail);
    }

    public void report(BackupPhase phase, String detail, long processed, long total) {
        progress.report(record != null ? record.getJobName() : "restore", phase, detail, processed, total);
    }
}
