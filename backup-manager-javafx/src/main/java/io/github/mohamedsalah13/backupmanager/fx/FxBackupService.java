package io.github.mohamedsalah13.backupmanager.fx;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.api.BackupListener;
import io.github.mohamedsalah13.backupmanager.core.api.ProgressCallback;
import io.github.mohamedsalah13.backupmanager.core.engine.RestoreEngine;
import io.github.mohamedsalah13.backupmanager.core.model.BackupProgress;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupType;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.util.List;
import java.util.function.Consumer;

/**
 * Bridges {@link BackupManager} to JavaFX.
 *
 * <p>The engine reports progress from whatever thread is doing the work, and touching a
 * JavaFX property from there throws. Everything this class exposes is therefore published
 * through {@link Platform#runLater}, so a controller can bind to these properties directly
 * without thinking about threads at all.
 */
public class FxBackupService {

    private final BackupManager manager;

    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper statusMessage = new ReadOnlyStringWrapper("Idle");
    private final ReadOnlyStringWrapper currentJob = new ReadOnlyStringWrapper("");
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(0);
    private final ObservableList<BackupRecord> history = FXCollections.observableArrayList();

    public FxBackupService(BackupManager manager) {
        this.manager = manager;
        manager.addListener(new UiListener());
    }

    public BackupManager getManager() {
        return manager;
    }

    // ------------------------------------------------------------- properties

    /** True while any backup or restore started through this service is in flight. */
    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    /** Human-readable description of the current step, for a status bar. */
    public ReadOnlyStringProperty statusMessageProperty() {
        return statusMessage.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty currentJobProperty() {
        return currentJob.getReadOnlyProperty();
    }

    /**
     * Completion in {@code [0,1]}, or {@link javafx.scene.control.ProgressIndicator#INDETERMINATE_PROGRESS}
     * when the total is unknown — which it usually is for a database dump.
     */
    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    /** Live backup history, safe to bind straight to a {@code TableView}. */
    public ObservableList<BackupRecord> getHistory() {
        return history;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Runs a backup on a background thread.
     *
     * @return the task, so a caller can bind to it or cancel it; it is already started
     */
    public Task<BackupRecord> backup(String jobName, BackupType type,
                                     Consumer<BackupRecord> onSuccess,
                                     Consumer<Throwable> onFailure) {
        Task<BackupRecord> task = new Task<>() {
            @Override
            protected BackupRecord call() {
                ProgressCallback callback = p -> Platform.runLater(() -> publish(p));
                return manager.backupNow(jobName, type, callback);
            }
        };

        task.setOnSucceeded(e -> {
            finish();
            refreshHistory(jobName);
            if (onSuccess != null) {
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            finish();
            statusMessage.set("Backup failed: " + describe(task.getException()));
            if (onFailure != null) {
                onFailure.accept(task.getException());
            }
        });
        task.setOnCancelled(e -> {
            finish();
            statusMessage.set("Backup cancelled");
        });

        start(jobName, "Starting backup");
        runInBackground(task, "backup-" + jobName);
        return task;
    }

    public Task<BackupRecord> backup(String jobName) {
        return backup(jobName, null, null, null);
    }

    /**
     * Restores a backup on a background thread.
     *
     * <p>A restore overwrites live data, so a UI should confirm with the user before
     * calling this — the service deliberately does not ask on its behalf.
     */
    public Task<Void> restore(String recordId, RestoreEngine.RestoreOptions options,
                              Runnable onSuccess, Consumer<Throwable> onFailure) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                ProgressCallback callback = p -> Platform.runLater(() -> publish(p));
                manager.restore(recordId, options != null
                        ? options : new RestoreEngine.RestoreOptions(), callback);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            finish();
            statusMessage.set("Restore complete");
            if (onSuccess != null) {
                onSuccess.run();
            }
        });
        task.setOnFailed(e -> {
            finish();
            statusMessage.set("Restore failed: " + describe(task.getException()));
            if (onFailure != null) {
                onFailure.accept(task.getException());
            }
        });

        start("", "Starting restore");
        runInBackground(task, "restore-" + recordId);
        return task;
    }

    /** Reads a backup end to end without writing anything, to prove it is intact. */
    public Task<Boolean> verify(String recordId, Consumer<Boolean> onResult) {
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                ProgressCallback callback = p -> Platform.runLater(() -> publish(p));
                return manager.testRestore(recordId, callback);
            }
        };
        task.setOnSucceeded(e -> {
            finish();
            statusMessage.set(Boolean.TRUE.equals(task.getValue())
                    ? "The backup verified successfully"
                    : "Verification failed; this backup cannot be restored");
            if (onResult != null) {
                onResult.accept(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            finish();
            statusMessage.set("Verification failed: " + describe(task.getException()));
            if (onResult != null) {
                onResult.accept(false);
            }
        });

        start("", "Verifying backup");
        runInBackground(task, "verify-" + recordId);
        return task;
    }

    /** Asks a running job to stop at the next checkpoint. */
    public void cancel(String jobName) {
        manager.cancel(jobName);
        statusMessage.set("Cancelling…");
    }

    /** Reloads the history list from the catalog. Safe to call from any thread. */
    public void refreshHistory(String jobName) {
        List<BackupRecord> records = jobName == null || jobName.isBlank()
                ? manager.allHistory()
                : manager.history(jobName);
        runOnFxThread(() -> history.setAll(records));
    }

    public void refreshHistory() {
        refreshHistory(null);
    }

    // ---------------------------------------------------------------- helpers

    private void publish(BackupProgress p) {
        currentJob.set(p.jobName() != null ? p.jobName() : "");
        String detail = p.detail() != null ? p.detail() : p.phase().getDisplayName();
        statusMessage.set(p.phase().getDisplayName() + " — " + detail);
        progress.set(p.isIndeterminate()
                ? javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS
                : p.fraction());
    }

    private void start(String jobName, String message) {
        runOnFxThread(() -> {
            running.set(true);
            currentJob.set(jobName);
            statusMessage.set(message);
            progress.set(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS);
        });
    }

    private void finish() {
        runOnFxThread(() -> {
            running.set(false);
            progress.set(0);
        });
    }

    private static void runInBackground(Task<?> task, String threadName) {
        Thread thread = new Thread(task, threadName);
        // Daemon: a backup in progress must not stop the window from closing.
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs now when already on the FX thread, avoiding a pointless frame of latency. */
    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    /** Keeps the history table current when a scheduled run finishes on its own. */
    private final class UiListener implements BackupListener {

        @Override
        public void onCompleted(BackupRecord record) {
            refreshHistory(record.getJobName());
        }

        @Override
        public void onFailed(BackupRecord record, Throwable error) {
            refreshHistory(record.getJobName());
        }

        @Override
        public void onDeleted(BackupRecord record, String reason) {
            runOnFxThread(() -> history.removeIf(r -> r.getId().equals(record.getId())));
        }
    }
}
