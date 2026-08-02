package com.backupmanager.spring.actuate;

import com.backupmanager.core.BackupManager;
import com.backupmanager.core.model.BackupStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports backup health at {@code /actuator/health/backup}.
 *
 * <p>The check that matters is staleness, not just the last status. A job whose most recent
 * run succeeded three weeks ago is broken in a way that "last run: SUCCESS" hides
 * completely, so anything older than {@link #staleAfter} is reported as DOWN.
 */
public class BackupHealthIndicator implements HealthIndicator {

    private final BackupManager manager;
    private final Duration staleAfter;

    public BackupHealthIndicator(BackupManager manager, Duration staleAfter) {
        this.manager = manager;
        this.staleAfter = staleAfter != null ? staleAfter : Duration.ofDays(2);
    }

    @Override
    public Health health() {
        Map<String, BackupManager.JobStatus> statuses = manager.status();
        if (statuses.isEmpty()) {
            return Health.up().withDetail("jobs", "none configured").build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        Status worst = Status.UP;
        Instant now = Instant.now();

        for (var entry : statuses.entrySet()) {
            BackupManager.JobStatus status = entry.getValue();
            Map<String, Object> jobDetail = new LinkedHashMap<>();
            jobDetail.put("enabled", status.enabled());
            jobDetail.put("running", status.running());
            jobDetail.put("totalBackups", status.totalBackups());
            jobDetail.put("lastStatus", status.lastStatus() != null
                    ? status.lastStatus().name() : "never run");
            if (status.lastRun() != null) {
                jobDetail.put("lastRun", status.lastRun().toString());
                jobDetail.put("age", humanAge(Duration.between(status.lastRun(), now)));
            }
            if (status.nextRun() != null) {
                jobDetail.put("nextRun", status.nextRun().toString());
            }

            if (!status.enabled()) {
                jobDetail.put("health", "disabled");
            } else if (status.lastRun() == null) {
                jobDetail.put("health", "no backup has run yet");
                worst = Status.DOWN;
            } else if (status.lastStatus() == BackupStatus.FAILED) {
                jobDetail.put("health", "the last run failed");
                worst = Status.DOWN;
            } else if (Duration.between(status.lastRun(), now).compareTo(staleAfter) > 0) {
                jobDetail.put("health", "the last successful backup is older than " + staleAfter);
                worst = Status.DOWN;
            } else if (status.lastStatus() == BackupStatus.PARTIAL) {
                jobDetail.put("health", "stored on some destinations but not all");
                if (worst == Status.UP) {
                    worst = new Status("DEGRADED");
                }
            } else {
                jobDetail.put("health", "ok");
            }
            details.put(entry.getKey(), jobDetail);
        }

        return Health.status(worst).withDetails(details).build();
    }

    private static String humanAge(Duration age) {
        if (age.toDays() > 0) {
            return age.toDays() + "d " + age.toHoursPart() + "h";
        }
        if (age.toHours() > 0) {
            return age.toHours() + "h " + age.toMinutesPart() + "m";
        }
        return age.toMinutes() + "m";
    }
}
