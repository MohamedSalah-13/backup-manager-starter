package io.github.mohamedsalah13.backupmanager.core.model;

/**
 * Immutable progress snapshot emitted while a run is in flight.
 *
 * <p>{@code totalBytes} is frequently unknown — a {@code mysqldump} stream has no
 * declared length — so {@link #fraction()} returns a negative value to mean
 * "indeterminate" rather than pretending to know.
 */
public record BackupProgress(
        String jobName,
        BackupPhase phase,
        String detail,
        long processedBytes,
        long totalBytes) {

    public static final double INDETERMINATE = -1d;

    public static BackupProgress of(String jobName, BackupPhase phase, String detail) {
        return new BackupProgress(jobName, phase, detail, 0L, -1L);
    }

    public static BackupProgress of(String jobName, BackupPhase phase, String detail,
                                    long processedBytes, long totalBytes) {
        return new BackupProgress(jobName, phase, detail, processedBytes, totalBytes);
    }

    /** Completion in {@code [0,1]}, or {@link #INDETERMINATE} when the total is unknown. */
    public double fraction() {
        if (totalBytes <= 0) {
            return INDETERMINATE;
        }
        return Math.min(1d, (double) processedBytes / (double) totalBytes);
    }

    public boolean isIndeterminate() {
        return totalBytes <= 0;
    }
}
