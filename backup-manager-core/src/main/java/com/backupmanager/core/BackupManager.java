package com.backupmanager.core;

import com.backupmanager.core.api.BackupCatalog;
import com.backupmanager.core.api.BackupListener;
import com.backupmanager.core.api.ProgressCallback;
import com.backupmanager.core.catalog.JsonFileCatalog;
import com.backupmanager.core.engine.BackupEngine;
import com.backupmanager.core.engine.ListenerDispatcher;
import com.backupmanager.core.engine.RestoreEngine;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupJob;
import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupType;
import com.backupmanager.core.schedule.BackupScheduler;
import com.backupmanager.core.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single entry point most applications need.
 *
 * <p>Holds the jobs, the catalog, the scheduler and the worker pool, and exposes backup,
 * restore, history and retention as ordinary method calls. Build one per application and
 * keep it for the process lifetime.
 *
 * <pre>{@code
 * BackupManager manager = BackupManager.builder()
 *         .storageRoot(Path.of("C:/AppData/backups"))
 *         .job(BackupJob.builder("main-db")
 *                 .source(new MySqlBackupSource(config))
 *                 .destination(new LocalStorageProvider(Path.of("D:/backups")))
 *                 .encryptWithPassword(secret)
 *                 .dailyAt(2, 30)
 *                 .retention(RetentionRule.gfs(7, 4, 12, 0))
 *                 .build())
 *         .build();
 *
 * manager.start();
 * BackupRecord record = manager.backupNow("main-db");
 * }</pre>
 */
public class BackupManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackupManager.class);

    private final Map<String, BackupJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final BackupCatalog catalog;
    private final ListenerDispatcher listeners;
    private final BackupEngine backupEngine;
    private final RestoreEngine restoreEngine;
    private final BackupScheduler scheduler;
    private final ExecutorService workers;
    private final Path workRoot;
    private final boolean ownsExecutor;

    private volatile boolean started;

    private BackupManager(Builder b) {
        this.catalog = b.catalog;
        this.listeners = new ListenerDispatcher();
        b.listeners.forEach(this.listeners::add);
        this.workRoot = b.workRoot;
        this.backupEngine = new BackupEngine(catalog, listeners, workRoot, b.clock);
        this.restoreEngine = new RestoreEngine(catalog, listeners, workRoot);
        this.ownsExecutor = b.executor == null;
        this.workers = b.executor != null ? b.executor : defaultExecutor(b.parallelism);
        this.scheduler = new BackupScheduler(this::runScheduled);
        b.jobs.forEach(job -> jobs.put(job.getName(), job));
    }

    public static Builder builder() {
        return new Builder();
    }

    private static ExecutorService defaultExecutor(int parallelism) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "backup-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(Math.max(1, parallelism), factory);
    }

    /** Registers scheduled jobs and begins firing them. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        jobs.values().forEach(scheduler::schedule);
        scheduler.start();
        log.info("Backup manager started with {} job(s)", jobs.size());
    }

    public boolean isStarted() {
        return started;
    }

    // ---------------------------------------------------------------- jobs

    /** Adds or replaces a job. Safe to call after {@link #start()}. */
    public void registerJob(BackupJob job) {
        jobs.put(job.getName(), job);
        if (started) {
            scheduler.schedule(job);
        }
        log.info("Registered backup job '{}'", job.getName());
    }

    public void removeJob(String jobName) {
        jobs.remove(jobName);
        scheduler.unschedule(jobName);
    }

    public Optional<BackupJob> getJob(String jobName) {
        return Optional.ofNullable(jobs.get(jobName));
    }

    public List<BackupJob> getJobs() {
        return List.copyOf(jobs.values());
    }

    private BackupJob requireJob(String jobName) {
        BackupJob job = jobs.get(jobName);
        if (job == null) {
            throw new BackupException.Configuration(
                    "No backup job named '" + jobName + "'. Registered: " + jobs.keySet());
        }
        return job;
    }

    // ------------------------------------------------------------- backups

    /** Runs a job on the calling thread and returns when it is done. */
    public BackupRecord backupNow(String jobName) {
        return backupNow(jobName, null, ProgressCallback.NOOP);
    }

    public BackupRecord backupNow(String jobName, BackupType type, ProgressCallback progress) {
        BackupJob job = requireJob(jobName);
        AtomicBoolean cancelFlag = cancelFlags.computeIfAbsent(jobName, k -> new AtomicBoolean());
        cancelFlag.set(false);
        try {
            return backupEngine.run(job, type, progress, cancelFlag);
        } finally {
            cancelFlags.remove(jobName, cancelFlag);
        }
    }

    /**
     * Runs a job on the worker pool. This is what a UI should call: the returned future
     * completes on a background thread, leaving the interface responsive.
     */
    public CompletableFuture<BackupRecord> backupAsync(String jobName) {
        return backupAsync(jobName, null, ProgressCallback.NOOP);
    }

    public CompletableFuture<BackupRecord> backupAsync(String jobName, BackupType type,
                                                       ProgressCallback progress) {
        return CompletableFuture.supplyAsync(() -> backupNow(jobName, type, progress), workers);
    }

    /** Runs every enabled job in sequence. */
    public List<BackupRecord> backupAll(ProgressCallback progress) {
        List<BackupRecord> results = new ArrayList<>();
        for (BackupJob job : jobs.values()) {
            if (job.isEnabled()) {
                results.add(backupNow(job.getName(), null, progress));
            }
        }
        return results;
    }

    /**
     * Asks a running job to stop. The engine checks the flag between units of work, so
     * this is cooperative rather than immediate; a partially written artifact is discarded.
     */
    public boolean cancel(String jobName) {
        AtomicBoolean flag = cancelFlags.get(jobName);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        log.info("Cancellation requested for job '{}'", jobName);
        return true;
    }

    public boolean isRunning(String jobName) {
        return cancelFlags.containsKey(jobName);
    }

    private void runScheduled(BackupJob job) {
        log.info("Scheduled trigger for job '{}'", job.getName());
        try {
            backupNow(job.getName(), job.getDefaultType(), ProgressCallback.NOOP);
        } catch (RuntimeException e) {
            log.error("Scheduled backup of '{}' failed: {}", job.getName(), e.toString(), e);
        }
    }

    // ------------------------------------------------------------ restores

    /** Restores a specific backup. Blocks until finished. */
    public void restore(String recordId) {
        restore(recordId, new RestoreEngine.RestoreOptions(), ProgressCallback.NOOP);
    }

    public void restore(String recordId, RestoreEngine.RestoreOptions options,
                        ProgressCallback progress) {
        BackupRecord record = catalog.findById(recordId).orElseThrow(() ->
                new BackupException("No backup with id " + recordId + " is in the catalog"));
        BackupJob job = requireJob(record.getJobName());
        AtomicBoolean cancelFlag = new AtomicBoolean();
        restoreEngine.restore(job, record, options, progress, cancelFlag);
    }

    /** Restores the most recent successful backup of a job. */
    public void restoreLatest(String jobName, ProgressCallback progress) {
        BackupRecord record = catalog.findLatestSuccessful(jobName).orElseThrow(() ->
                new BackupException("Job '" + jobName + "' has no restorable backup"));
        restore(record.getId(), new RestoreEngine.RestoreOptions(), progress);
    }

    public CompletableFuture<Void> restoreAsync(String recordId,
                                                RestoreEngine.RestoreOptions options,
                                                ProgressCallback progress) {
        return CompletableFuture.runAsync(() -> restore(recordId, options, progress), workers);
    }

    /**
     * Reads a backup end to end and decodes it without writing anything.
     *
     * <p>Worth running on a schedule of its own. A backup nobody has ever read is a
     * hypothesis, not a safety net.
     */
    public boolean testRestore(String recordId, ProgressCallback progress) {
        BackupRecord record = catalog.findById(recordId).orElseThrow(() ->
                new BackupException("No backup with id " + recordId + " is in the catalog"));
        return restoreEngine.testRestore(requireJob(record.getJobName()), record, progress);
    }

    // ------------------------------------------------------------- history

    public List<BackupRecord> history(String jobName) {
        return catalog.findByJob(jobName);
    }

    public List<BackupRecord> allHistory() {
        return catalog.findAll();
    }

    public Optional<BackupRecord> latest(String jobName) {
        return catalog.findLatestSuccessful(jobName);
    }

    public BackupCatalog getCatalog() {
        return catalog;
    }

    /** Deletes a backup from every destination and drops its catalog entry. */
    public boolean deleteBackup(String recordId) {
        BackupRecord record = catalog.findById(recordId).orElse(null);
        if (record == null) {
            return false;
        }
        BackupJob job = jobs.get(record.getJobName());
        if (job == null) {
            throw new BackupException.Configuration(
                    "Cannot delete backups of job '" + record.getJobName() + "' because it is not registered");
        }
        boolean allRemoved = true;
        for (var location : record.getLocations()) {
            var provider = job.getDestinations().stream()
                    .filter(p -> p.name().equals(location.getProviderName()))
                    .findFirst().orElse(null);
            if (provider == null) {
                allRemoved = false;
                continue;
            }
            try {
                provider.delete(location.getPath());
            } catch (IOException e) {
                log.warn("Could not delete {} from {}: {}",
                        location.getPath(), provider.name(), e.toString());
                allRemoved = false;
            }
        }
        if (allRemoved) {
            catalog.delete(recordId);
            listeners.onDeleted(record, "deleted on request");
        }
        return allRemoved;
    }

    /** Runs the retention policy for one job outside its normal post-backup pass. */
    public int applyRetention(String jobName) {
        return backupEngine.applyRetention(requireJob(jobName));
    }

    // ----------------------------------------------------------- scheduling

    public Optional<ZonedDateTime> nextRunOf(String jobName) {
        return scheduler.nextRunOf(jobName);
    }

    public Map<String, ZonedDateTime> upcomingRuns() {
        return scheduler.upcomingRuns();
    }

    public BackupScheduler getScheduler() {
        return scheduler;
    }

    // ------------------------------------------------------------ listeners

    public void addListener(BackupListener listener) {
        listeners.add(listener);
    }

    public void removeListener(BackupListener listener) {
        listeners.remove(listener);
    }

    // -------------------------------------------------------------- status

    /** One-line health summary per job, for a dashboard or an Actuator endpoint. */
    public Map<String, JobStatus> status() {
        Map<String, JobStatus> result = new LinkedHashMap<>();
        for (BackupJob job : jobs.values()) {
            Optional<BackupRecord> last = catalog.findByJob(job.getName()).stream().findFirst();
            result.put(job.getName(), new JobStatus(
                    job.getName(),
                    job.isEnabled(),
                    isRunning(job.getName()),
                    last.map(BackupRecord::getStatus).orElse(null),
                    last.map(BackupRecord::getStartedAt).orElse(null),
                    scheduler.nextRunOf(job.getName()).orElse(null),
                    catalog.findByJob(job.getName()).size()));
        }
        return result;
    }

    /** Snapshot of one job's health. */
    public record JobStatus(
            String jobName,
            boolean enabled,
            boolean running,
            com.backupmanager.core.model.BackupStatus lastStatus,
            java.time.Instant lastRun,
            ZonedDateTime nextRun,
            int totalBackups) {
    }

    @Override
    public void close() {
        log.info("Shutting down the backup manager");
        scheduler.close();
        if (ownsExecutor) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(60, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            catalog.close();
        } catch (Exception e) {
            log.warn("Could not close the catalog cleanly: {}", e.toString());
        }
        started = false;
    }

    public static final class Builder {

        private final List<BackupJob> jobs = new ArrayList<>();
        private final List<BackupListener> listeners = new ArrayList<>();
        private BackupCatalog catalog;
        private Path workRoot;
        private ExecutorService executor;
        private int parallelism = 2;
        private java.time.Clock clock = java.time.Clock.systemDefaultZone();

        /**
         * Convenience for the common layout: the catalog goes in {@code root/catalog.json}
         * and scratch files in {@code root/work}.
         */
        public Builder storageRoot(Path root) {
            this.catalog = new JsonFileCatalog(root.resolve("catalog.json"));
            this.workRoot = root.resolve("work");
            return this;
        }

        public Builder catalog(BackupCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        /**
         * Directory for staging artifacts before upload. It needs room for the largest
         * artifact the biggest job will produce.
         */
        public Builder workDirectory(Path workRoot) {
            this.workRoot = workRoot;
            return this;
        }

        public Builder job(BackupJob job) {
            this.jobs.add(job);
            return this;
        }

        public Builder jobs(List<BackupJob> jobs) {
            this.jobs.addAll(jobs);
            return this;
        }

        public Builder listener(BackupListener listener) {
            this.listeners.add(listener);
            return this;
        }

        /** How many jobs may run at once via the async methods. */
        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        /** Supplies an existing pool instead of creating one; the caller keeps ownership. */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /** Overrides the clock, for deterministic tests of scheduling and retention. */
        public Builder clock(java.time.Clock clock) {
            this.clock = clock;
            return this;
        }

        public BackupManager build() {
            if (catalog == null) {
                throw new BackupException.Configuration(
                        "A catalog is required; call storageRoot(...) or catalog(...)");
            }
            if (workRoot == null) {
                workRoot = Path.of(System.getProperty("java.io.tmpdir"), "backup-manager-work");
            }
            try {
                Files.createDirectories(workRoot);
                // Scratch files hold decrypted plaintext for the length of a run, so a
                // leftover directory from a crashed process is worth clearing at startup.
                clearStaleWorkDirs(workRoot);
            } catch (IOException e) {
                throw new BackupException.Configuration(
                        "Could not create the work directory at " + workRoot, e);
            }
            return new BackupManager(this);
        }

        private void clearStaleWorkDirs(Path root) throws IOException {
            try (var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("run-") || name.startsWith("restore-");
                        })
                        .forEach(FileNames::deleteQuietly);
            }
        }
    }
}
