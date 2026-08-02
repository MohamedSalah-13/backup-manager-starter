package com.backupmanager.core.model;

import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.Compressor;
import com.backupmanager.core.api.Encryptor;
import com.backupmanager.core.api.RetentionPolicy;
import com.backupmanager.core.api.StorageProvider;
import com.backupmanager.core.compress.GzipCompressor;
import com.backupmanager.core.compress.NoCompression;
import com.backupmanager.core.crypto.AesGcmEncryptor;
import com.backupmanager.core.crypto.NoEncryption;
import com.backupmanager.core.retention.RetentionPolicies;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One backup task: what to capture, how to package it, where to put it, how long to keep
 * it and when to run it.
 *
 * <p>Build with {@link #builder(String)}. The source and every destination are held for
 * the job's lifetime and reused across runs, so they must be safe to use repeatedly and
 * from a scheduler thread.
 */
public final class BackupJob {

    private final String name;
    private final String description;
    private final BackupSource source;
    private final List<StorageProvider> destinations;
    private final Compressor compressor;
    private final Encryptor encryptor;
    private final RetentionPolicy retention;
    private final Schedule schedule;
    private final BackupType defaultType;
    private final String pathPrefix;
    private final boolean verifyAfterStore;
    private final boolean enabled;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration retryDelay;
    private final boolean requireAllDestinations;

    private BackupJob(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.source = b.source;
        this.destinations = List.copyOf(b.destinations);
        this.compressor = b.compressor;
        this.encryptor = b.encryptor;
        this.retention = b.retention;
        this.schedule = b.schedule;
        this.defaultType = b.defaultType;
        this.pathPrefix = b.pathPrefix;
        this.verifyAfterStore = b.verifyAfterStore;
        this.enabled = b.enabled;
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.retryDelay = b.retryDelay;
        this.requireAllDestinations = b.requireAllDestinations;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BackupSource getSource() {
        return source;
    }

    public List<StorageProvider> getDestinations() {
        return destinations;
    }

    public Compressor getCompressor() {
        return compressor;
    }

    public Encryptor getEncryptor() {
        return encryptor;
    }

    public RetentionPolicy getRetention() {
        return retention;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public BackupType getDefaultType() {
        return defaultType;
    }

    /** Folder inside each destination that this job's artifacts go into. */
    public String getPathPrefix() {
        return pathPrefix;
    }

    /** Whether to re-read each stored copy and compare checksums after uploading. */
    public boolean isVerifyAfterStore() {
        return verifyAfterStore;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    /** When true, a single failed destination fails the whole run instead of marking it partial. */
    public boolean isRequireAllDestinations() {
        return requireAllDestinations;
    }

    @Override
    public String toString() {
        return "BackupJob[" + name + " " + source.type() + " -> "
                + destinations.stream().map(StorageProvider::name).toList() + " " + schedule + "]";
    }

    public static final class Builder {

        private final String name;
        private String description;
        private BackupSource source;
        private final List<StorageProvider> destinations = new ArrayList<>();
        private Compressor compressor = new GzipCompressor();
        private Encryptor encryptor = NoEncryption.INSTANCE;
        private RetentionPolicy retention = RetentionPolicies.keepLast(10);
        private Schedule schedule = Schedule.manual();
        private BackupType defaultType = BackupType.FULL;
        private String pathPrefix;
        private boolean verifyAfterStore = true;
        private boolean enabled = true;
        private Duration timeout = Duration.ofHours(6);
        private int maxRetries = 2;
        private Duration retryDelay = Duration.ofSeconds(30);
        private boolean requireAllDestinations;

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new BackupException.Configuration("Job name must not be blank");
            }
            this.name = name.trim();
            this.pathPrefix = this.name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder source(BackupSource source) {
            this.source = source;
            return this;
        }

        /** Adds a destination. Artifacts are copied to every destination on every run. */
        public Builder destination(StorageProvider provider) {
            this.destinations.add(provider);
            return this;
        }

        public Builder destinations(List<StorageProvider> providers) {
            this.destinations.addAll(providers);
            return this;
        }

        public Builder compressor(Compressor compressor) {
            this.compressor = compressor != null ? compressor : NoCompression.INSTANCE;
            return this;
        }

        public Builder noCompression() {
            this.compressor = NoCompression.INSTANCE;
            return this;
        }

        public Builder encryptor(Encryptor encryptor) {
            this.encryptor = encryptor != null ? encryptor : NoEncryption.INSTANCE;
            return this;
        }

        /**
         * Encrypts with AES-256-GCM. Store the password somewhere safe and separate from
         * the backups: without it the artifacts are unrecoverable by design.
         */
        public Builder encryptWithPassword(String password) {
            this.encryptor = AesGcmEncryptor.withPassword(password);
            return this;
        }

        public Builder retention(RetentionPolicy retention) {
            this.retention = retention != null ? retention : RetentionPolicy.KEEP_ALL;
            return this;
        }

        public Builder retention(RetentionRule rule) {
            this.retention = RetentionPolicies.of(rule);
            return this;
        }

        public Builder schedule(Schedule schedule) {
            this.schedule = schedule != null ? schedule : Schedule.manual();
            return this;
        }

        public Builder cron(String expression) {
            this.schedule = Schedule.cron(expression);
            return this;
        }

        public Builder dailyAt(int hour, int minute) {
            this.schedule = Schedule.dailyAt(hour, minute);
            return this;
        }

        public Builder defaultType(BackupType type) {
            this.defaultType = type != null ? type : BackupType.FULL;
            return this;
        }

        public Builder pathPrefix(String prefix) {
            this.pathPrefix = prefix;
            return this;
        }

        public Builder verifyAfterStore(boolean verify) {
            this.verifyAfterStore = verify;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Retries apply to the whole run, including the dump. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, maxRetries);
            return this;
        }

        public Builder retryDelay(Duration delay) {
            this.retryDelay = delay;
            return this;
        }

        public Builder requireAllDestinations(boolean require) {
            this.requireAllDestinations = require;
            return this;
        }

        public BackupJob build() {
            Objects.requireNonNull(source, "A backup job needs a source; call source(...)");
            if (destinations.isEmpty()) {
                throw new BackupException.Configuration(
                        "Job '" + name + "' has no destination; call destination(...) at least once");
            }
            if (!source.supportedTypes().contains(defaultType)) {
                throw new BackupException.Configuration(
                        "Source " + source.type() + " does not support " + defaultType
                                + " backups; it supports " + source.supportedTypes());
            }
            return new BackupJob(this);
        }
    }
}
