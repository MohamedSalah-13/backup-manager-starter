package io.github.mohamedsalah13.backupmanager.core.api;

import io.github.mohamedsalah13.backupmanager.core.model.BackupPhase;
import io.github.mohamedsalah13.backupmanager.core.model.BackupProgress;

/**
 * Sink for progress updates during a long operation. Implementations must be cheap and
 * must not block: the engine calls this from the worker thread, so a slow callback slows
 * the backup itself. UI adapters should hand off to their own event queue.
 */
@FunctionalInterface
public interface ProgressCallback {

    ProgressCallback NOOP = progress -> {
    };

    void onProgress(BackupProgress progress);

    default void report(String jobName, BackupPhase phase, String detail) {
        onProgress(BackupProgress.of(jobName, phase, detail));
    }

    default void report(String jobName, BackupPhase phase, String detail, long processed, long total) {
        onProgress(BackupProgress.of(jobName, phase, detail, processed, total));
    }

    /** Runs both callbacks, so an internal listener and a caller-supplied one can coexist. */
    default ProgressCallback andThen(ProgressCallback other) {
        if (other == null || other == NOOP) {
            return this;
        }
        return progress -> {
            onProgress(progress);
            other.onProgress(progress);
        };
    }
}
