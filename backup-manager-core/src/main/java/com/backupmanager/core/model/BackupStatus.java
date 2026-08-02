package com.backupmanager.core.model;

/** Lifecycle state of a single backup execution. */
public enum BackupStatus {
    /** Queued but not started yet. */
    PENDING,
    /** Source dump / upload currently in progress. */
    RUNNING,
    /** Stored successfully on every configured destination. */
    SUCCESS,
    /** Stored on at least one destination but not all of them. */
    PARTIAL,
    /** Nothing usable was produced. */
    FAILED,
    /** Aborted on request before completion. */
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }

    /** True when the artifact exists somewhere and can be restored. */
    public boolean isRestorable() {
        return this == SUCCESS || this == PARTIAL;
    }
}
