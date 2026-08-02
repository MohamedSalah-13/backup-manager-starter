package com.backupmanager.core.engine;

import com.backupmanager.core.api.BackupListener;
import com.backupmanager.core.model.BackupProgress;
import com.backupmanager.core.model.BackupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fans lifecycle events out to registered listeners.
 *
 * <p>Every dispatch is guarded: a listener that throws — a UI that was disposed, a
 * notifier whose SMTP server is down — gets its exception logged and nothing more. A
 * backup that reached storage successfully must not be reported as failed because
 * something downstream of it broke.
 */
public final class ListenerDispatcher implements BackupListener {

    private static final Logger log = LoggerFactory.getLogger(ListenerDispatcher.class);

    private final List<BackupListener> listeners = new CopyOnWriteArrayList<>();

    public void add(BackupListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void remove(BackupListener listener) {
        listeners.remove(listener);
    }

    public void clear() {
        listeners.clear();
    }

    public int size() {
        return listeners.size();
    }

    private void dispatch(String event, Consumer<BackupListener> action) {
        for (BackupListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Backup listener {} threw on {}: {}",
                        listener.getClass().getSimpleName(), event, e.toString(), e);
            }
        }
    }

    @Override
    public void onStarted(String jobName, BackupRecord record) {
        dispatch("onStarted", l -> l.onStarted(jobName, record));
    }

    @Override
    public void onProgress(BackupProgress progress) {
        dispatch("onProgress", l -> l.onProgress(progress));
    }

    @Override
    public void onCompleted(BackupRecord record) {
        dispatch("onCompleted", l -> l.onCompleted(record));
    }

    @Override
    public void onFailed(BackupRecord record, Throwable error) {
        dispatch("onFailed", l -> l.onFailed(record, error));
    }

    @Override
    public void onCancelled(BackupRecord record) {
        dispatch("onCancelled", l -> l.onCancelled(record));
    }

    @Override
    public void onDeleted(BackupRecord record, String reason) {
        dispatch("onDeleted", l -> l.onDeleted(record, reason));
    }

    @Override
    public void onRestoreStarted(BackupRecord record) {
        dispatch("onRestoreStarted", l -> l.onRestoreStarted(record));
    }

    @Override
    public void onRestoreCompleted(BackupRecord record) {
        dispatch("onRestoreCompleted", l -> l.onRestoreCompleted(record));
    }

    @Override
    public void onRestoreFailed(BackupRecord record, Throwable error) {
        dispatch("onRestoreFailed", l -> l.onRestoreFailed(record, error));
    }
}
