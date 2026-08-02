package com.backupmanager.core.api;

import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persistent index of what has been backed up. The catalog is the source of truth for
 * restores and retention — without it you have files on disk with no way to know which
 * password, codec or chain they belong to.
 *
 * <p>Implementations must be thread-safe.
 */
public interface BackupCatalog extends AutoCloseable {

    /** Inserts or updates by {@link BackupRecord#getId()}. */
    void save(BackupRecord record);

    Optional<BackupRecord> findById(String id);

    /** All records for a job, newest first. */
    List<BackupRecord> findByJob(String jobName);

    List<BackupRecord> findAll();

    void delete(String id);

    /** Names of every job that has at least one record. */
    List<String> jobNames();

    /** Most recent restorable backup for a job. */
    default Optional<BackupRecord> findLatestSuccessful(String jobName) {
        return findByJob(jobName).stream()
                .filter(r -> r.getStatus().isRestorable())
                .max(Comparator.comparing(BackupRecord::getStartedAt));
    }

    /** Most recent full backup, the anchor an incremental chain restores from. */
    default Optional<BackupRecord> findLatestFull(String jobName) {
        return findByJob(jobName).stream()
                .filter(r -> r.getStatus().isRestorable())
                .filter(r -> !r.getType().requiresChain())
                .max(Comparator.comparing(BackupRecord::getStartedAt));
    }

    default List<BackupRecord> findByStatus(BackupStatus status) {
        return findAll().stream().filter(r -> r.getStatus() == status).toList();
    }

    /** Flushes pending writes. Called after every run so a crash cannot lose the index. */
    default void flush() {
    }

    @Override
    default void close() throws Exception {
        flush();
    }
}
