package io.github.mohamedsalah13.backupmanager.core.schedule;

import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import io.github.mohamedsalah13.backupmanager.core.model.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fires jobs on their schedules.
 *
 * <p>Each job is scheduled one run at a time — the next fire time is computed only after
 * the current one finishes. A fixed-rate executor would instead queue up every tick missed
 * while a long backup was running and then fire them back to back, which is the opposite
 * of what anyone wants from a nightly dump that overran.
 */
public class BackupScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final ScheduledExecutorService executor;
    private final Consumer<BackupJob> runner;
    private final Map<String, Scheduled> scheduled = new ConcurrentHashMap<>();
    private volatile boolean running;

    public BackupScheduler(Consumer<BackupJob> runner) {
        this(runner, defaultExecutor());
    }

    public BackupScheduler(Consumer<BackupJob> runner, ScheduledExecutorService executor) {
        this.runner = runner;
        this.executor = executor;
    }

    private static ScheduledExecutorService defaultExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "backup-scheduler-" + counter.incrementAndGet());
            // Daemon: a scheduler must never be the reason a desktop app refuses to exit.
            t.setDaemon(true);
            return t;
        };
        return Executors.newScheduledThreadPool(2, factory);
    }

    public void start() {
        running = true;
        log.info("Backup scheduler started with {} job(s)", scheduled.size());
        scheduled.values().forEach(this::scheduleNext);
    }

    /** Registers a job. Manual and disabled jobs are accepted but never fire on their own. */
    public void schedule(BackupJob job) {
        if (!job.isEnabled()) {
            log.debug("Job '{}' is disabled and will not be scheduled", job.getName());
            return;
        }
        if (!job.getSchedule().isAutomatic()) {
            log.debug("Job '{}' is manual and will not be scheduled", job.getName());
            return;
        }
        Scheduled entry = new Scheduled(job);
        Scheduled previous = scheduled.put(job.getName(), entry);
        if (previous != null) {
            previous.cancel();
        }
        if (running) {
            scheduleNext(entry);
        }
    }

    public void unschedule(String jobName) {
        Scheduled entry = scheduled.remove(jobName);
        if (entry != null) {
            entry.cancel();
            log.debug("Unscheduled job '{}'", jobName);
        }
    }

    private void scheduleNext(Scheduled entry) {
        if (!running) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = entry.job.getSchedule().nextRun(now);
        if (next == null) {
            log.warn("Job '{}' has a schedule that never fires; leaving it unscheduled",
                    entry.job.getName());
            return;
        }

        long delayMillis = Math.max(0, Duration.between(now, next).toMillis());
        entry.nextRun = next;
        entry.future = executor.schedule(() -> fire(entry), delayMillis, TimeUnit.MILLISECONDS);

        log.info("Job '{}' scheduled for {} (in {})",
                entry.job.getName(), next, humanDelay(delayMillis));
    }

    private void fire(Scheduled entry) {
        try {
            if (!running) {
                return;
            }
            if (entry.inFlight) {
                log.warn("Job '{}' is still running from its previous trigger; skipping this one",
                        entry.job.getName());
                return;
            }
            entry.inFlight = true;
            entry.lastRun = ZonedDateTime.now();
            try {
                runner.accept(entry.job);
            } finally {
                entry.inFlight = false;
            }
        } catch (RuntimeException e) {
            // Never let a failure stop the schedule; the next run must still be booked.
            log.error("Scheduled run of '{}' threw: {}", entry.job.getName(), e.toString(), e);
        } finally {
            scheduleNext(entry);
        }
    }

    /** Next fire time for a job, if it is scheduled. */
    public Optional<ZonedDateTime> nextRunOf(String jobName) {
        return Optional.ofNullable(scheduled.get(jobName)).map(s -> s.nextRun);
    }

    public Optional<ZonedDateTime> lastRunOf(String jobName) {
        return Optional.ofNullable(scheduled.get(jobName)).map(s -> s.lastRun);
    }

    public boolean isRunning(String jobName) {
        Scheduled entry = scheduled.get(jobName);
        return entry != null && entry.inFlight;
    }

    public Map<String, ZonedDateTime> upcomingRuns() {
        Map<String, ZonedDateTime> result = new java.util.LinkedHashMap<>();
        scheduled.forEach((name, entry) -> {
            if (entry.nextRun != null) {
                result.put(name, entry.nextRun);
            }
        });
        return result;
    }

    public boolean isStarted() {
        return running;
    }

    public void stop() {
        running = false;
        scheduled.values().forEach(Scheduled::cancel);
        log.info("Backup scheduler stopped");
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String humanDelay(long millis) {
        Duration d = Duration.ofMillis(millis);
        if (d.toHours() > 0) {
            return d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        if (d.toMinutes() > 0) {
            return d.toMinutes() + "m " + d.toSecondsPart() + "s";
        }
        return d.toSeconds() + "s";
    }

    /** Mutable per-job scheduling state. */
    private static final class Scheduled {

        final BackupJob job;
        volatile ScheduledFuture<?> future;
        volatile ZonedDateTime nextRun;
        volatile ZonedDateTime lastRun;
        volatile boolean inFlight;

        Scheduled(BackupJob job) {
            this.job = job;
        }

        void cancel() {
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }
    }

    /** Human-readable preview of the next few fire times, for a settings screen. */
    public static java.util.List<ZonedDateTime> preview(Schedule schedule, int count) {
        java.util.List<ZonedDateTime> result = new java.util.ArrayList<>(count);
        ZonedDateTime cursor = ZonedDateTime.now();
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = schedule.nextRun(cursor);
            if (next == null) {
                break;
            }
            result.add(next);
            cursor = next;
        }
        return result;
    }
}
