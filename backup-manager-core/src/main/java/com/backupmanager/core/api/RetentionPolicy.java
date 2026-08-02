package com.backupmanager.core.api;

import com.backupmanager.core.model.BackupRecord;

import java.time.Clock;
import java.util.List;

/**
 * Decides which old backups may be deleted.
 *
 * <p>Policies return what to <em>delete</em> rather than what to keep, and the engine
 * treats an empty result as "delete nothing", so a policy that cannot decide fails safe.
 */
@FunctionalInterface
public interface RetentionPolicy {

    /** Keeps everything. */
    RetentionPolicy KEEP_ALL = (records, clock) -> List.of();

    /**
     * @param records every record for one job, in any order; never modified by the caller
     * @return the subset that may be deleted
     */
    List<BackupRecord> selectForDeletion(List<BackupRecord> records, Clock clock);

    default String describe() {
        return getClass().getSimpleName();
    }

    /** Deletes only what both policies agree on — the conservative combination. */
    default RetentionPolicy and(RetentionPolicy other) {
        return (records, clock) -> {
            List<BackupRecord> mine = selectForDeletion(records, clock);
            List<BackupRecord> theirs = other.selectForDeletion(records, clock);
            return mine.stream().filter(theirs::contains).toList();
        };
    }
}
