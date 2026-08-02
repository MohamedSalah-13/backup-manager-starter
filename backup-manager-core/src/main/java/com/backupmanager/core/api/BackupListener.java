package com.backupmanager.core.api;

import com.backupmanager.core.model.BackupProgress;
import com.backupmanager.core.model.BackupRecord;

/**
 * Lifecycle hook for backup runs — logging, notifications, refreshing a UI table.
 *
 * <p>Every method has a no-op default, so implementations override only what they need.
 * Exceptions thrown here are logged and swallowed: a broken listener must never fail a
 * backup that otherwise succeeded.
 */
public interface BackupListener {

    default void onStarted(String jobName, BackupRecord record) {
    }

    default void onProgress(BackupProgress progress) {
    }

    /** Fired for {@code SUCCESS} and {@code PARTIAL}. Check the status for which. */
    default void onCompleted(BackupRecord record) {
    }

    default void onFailed(BackupRecord record, Throwable error) {
    }

    default void onCancelled(BackupRecord record) {
    }

    /** Fired once per record actually removed by retention. */
    default void onDeleted(BackupRecord record, String reason) {
    }

    default void onRestoreStarted(BackupRecord record) {
    }

    default void onRestoreCompleted(BackupRecord record) {
    }

    default void onRestoreFailed(BackupRecord record, Throwable error) {
    }
}
