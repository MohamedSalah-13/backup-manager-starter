package com.backupmanager.core.model;

/** Scope of the data captured by a backup run. */
public enum BackupType {
    /** Everything the source exposes. Always self-contained. */
    FULL,
    /** Only what changed since the previous backup of any type. */
    INCREMENTAL,
    /** Everything that changed since the last {@link #FULL} backup. */
    DIFFERENTIAL;

    /** Whether restoring this backup requires earlier artifacts in the same chain. */
    public boolean requiresChain() {
        return this != FULL;
    }
}
