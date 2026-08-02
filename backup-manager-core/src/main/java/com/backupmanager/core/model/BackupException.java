package com.backupmanager.core.model;

/** Base unchecked exception for every failure raised by the backup engine. */
public class BackupException extends RuntimeException {

    private final String jobName;

    public BackupException(String message) {
        this(message, null, null);
    }

    public BackupException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public BackupException(String message, Throwable cause, String jobName) {
        super(message, cause);
        this.jobName = jobName;
    }

    /** Name of the job that failed, or {@code null} when raised outside a job. */
    public String getJobName() {
        return jobName;
    }

    /** Configuration is invalid; retrying without changes cannot help. */
    public static class Configuration extends BackupException {
        public Configuration(String message) {
            super(message);
        }

        public Configuration(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The source could not produce a dump. */
    public static class Source extends BackupException {
        public Source(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A storage destination rejected or lost the artifact. */
    public static class Storage extends BackupException {
        private final String provider;

        public Storage(String provider, String message, Throwable cause) {
            super(message, cause);
            this.provider = provider;
        }

        public String getProvider() {
            return provider;
        }
    }

    /** Encryption or decryption failed — typically a wrong password or a corrupt artifact. */
    public static class Crypto extends BackupException {
        public Crypto(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Checksum or structural verification of a stored artifact failed. */
    public static class Verification extends BackupException {
        public Verification(String message) {
            super(message);
        }
    }
}
