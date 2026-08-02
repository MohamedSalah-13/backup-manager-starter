package com.backupmanager.core.model;

/** Stage of the pipeline a run is currently in. Drives progress reporting in the UI. */
public enum BackupPhase {
    PREPARING("Preparing"),
    DUMPING("Reading source"),
    COMPRESSING("Compressing"),
    ENCRYPTING("Encrypting"),
    UPLOADING("Uploading"),
    VERIFYING("Verifying"),
    APPLYING_RETENTION("Applying retention"),
    DONE("Done"),
    FAILED("Failed");

    private final String displayName;

    BackupPhase(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
