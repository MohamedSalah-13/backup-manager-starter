package io.github.mohamedsalah13.backupmanager.core.model;

import io.github.mohamedsalah13.backupmanager.core.schedule.CronExpression;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a job should run. A schedule is either cron-driven, a fixed interval, or manual
 * (triggered only by an explicit call).
 */
public final class Schedule {

    public enum Kind {
        MANUAL, CRON, FIXED_INTERVAL
    }

    private final Kind kind;
    private final String cron;
    private final Duration interval;
    private final Duration initialDelay;
    private final ZoneId zone;

    private transient CronExpression parsedCron;

    private Schedule(Kind kind, String cron, Duration interval, Duration initialDelay, ZoneId zone) {
        this.kind = kind;
        this.cron = cron;
        this.interval = interval;
        this.initialDelay = initialDelay;
        this.zone = zone != null ? zone : ZoneId.systemDefault();
    }

    /** Never fires on its own; run it with an explicit trigger. */
    public static Schedule manual() {
        return new Schedule(Kind.MANUAL, null, null, null, null);
    }

    /** Cron in the system default zone. */
    public static Schedule cron(String expression) {
        return cron(expression, ZoneId.systemDefault());
    }

    /**
     * Cron in an explicit zone. Pin the zone when backups must line up with a business
     * calendar rather than the server's locale.
     */
    public static Schedule cron(String expression, ZoneId zone) {
        CronExpression parsed = CronExpression.parse(expression);
        Schedule s = new Schedule(Kind.CRON, expression, null, null, zone);
        s.parsedCron = parsed;
        return s;
    }

    public static Schedule every(Duration interval) {
        return every(interval, interval);
    }

    public static Schedule every(Duration interval, Duration initialDelay) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new BackupException.Configuration("Schedule interval must be positive but was " + interval);
        }
        return new Schedule(Kind.FIXED_INTERVAL, null, interval,
                initialDelay != null ? initialDelay : interval, null);
    }

    /** Convenience shorthands for the schedules desktop apps ask for most. */
    public static Schedule dailyAt(int hour, int minute) {
        return cron("0 " + minute + " " + hour + " * * *");
    }

    public static Schedule hourly() {
        return cron("0 0 * * * *");
    }

    public static Schedule weeklyOn(java.time.DayOfWeek day, int hour, int minute) {
        return cron("0 " + minute + " " + hour + " ? * " + (day.getValue() % 7));
    }

    /** Next fire time after {@code from}, or {@code null} for manual schedules. */
    public ZonedDateTime nextRun(ZonedDateTime from) {
        return switch (kind) {
            case MANUAL -> null;
            case CRON -> cronExpression().next(from.withZoneSameInstant(zone));
            case FIXED_INTERVAL -> from.plus(interval);
        };
    }

    /** Delay before the very first run of a fixed-interval schedule. */
    public Duration firstDelay() {
        return kind == Kind.FIXED_INTERVAL ? initialDelay : Duration.ZERO;
    }

    private CronExpression cronExpression() {
        CronExpression local = parsedCron;
        if (local == null) {
            local = CronExpression.parse(cron);
            parsedCron = local;
        }
        return local;
    }

    public Kind getKind() {
        return kind;
    }

    public String getCron() {
        return cron;
    }

    public Duration getInterval() {
        return interval;
    }

    public ZoneId getZone() {
        return zone;
    }

    public boolean isAutomatic() {
        return kind != Kind.MANUAL;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case MANUAL -> "manual";
            case CRON -> "cron(" + cron + " @" + zone + ")";
            case FIXED_INTERVAL -> "every " + interval;
        };
    }
}
