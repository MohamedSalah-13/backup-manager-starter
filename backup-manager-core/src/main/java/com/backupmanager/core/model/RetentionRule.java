package com.backupmanager.core.model;

import java.time.Duration;

/**
 * Declarative retention settings. Any field left at its disabled value is ignored, and
 * the rules combine with OR: a backup survives if <em>any</em> enabled rule wants to keep it.
 * That is the safe direction — a misconfigured rule keeps too much rather than deleting
 * something still needed.
 */
public class RetentionRule {

    /** Keep at most this many backups per job. {@code 0} disables the rule. */
    private int keepLast;
    /** Delete backups older than this. {@code null} disables the rule. */
    private Duration maxAge;

    /** Grandfather-father-son: how many daily / weekly / monthly / yearly copies to keep. */
    private int keepDaily;
    private int keepWeekly;
    private int keepMonthly;
    private int keepYearly;

    /** Never drop below this many backups, whatever the other rules say. */
    private int minimumKept = 1;

    public static RetentionRule keepLast(int count) {
        RetentionRule r = new RetentionRule();
        r.keepLast = count;
        return r;
    }

    public static RetentionRule maxAge(Duration age) {
        RetentionRule r = new RetentionRule();
        r.maxAge = age;
        return r;
    }

    public static RetentionRule maxAgeDays(int days) {
        return maxAge(Duration.ofDays(days));
    }

    /** Classic grandfather-father-son rotation. */
    public static RetentionRule gfs(int daily, int weekly, int monthly, int yearly) {
        RetentionRule r = new RetentionRule();
        r.keepDaily = daily;
        r.keepWeekly = weekly;
        r.keepMonthly = monthly;
        r.keepYearly = yearly;
        return r;
    }

    /** Keeps everything. */
    public static RetentionRule unlimited() {
        return new RetentionRule();
    }

    public boolean isEnabled() {
        return keepLast > 0 || maxAge != null || keepDaily > 0 || keepWeekly > 0
                || keepMonthly > 0 || keepYearly > 0;
    }

    public int getKeepLast() {
        return keepLast;
    }

    public RetentionRule setKeepLast(int keepLast) {
        this.keepLast = keepLast;
        return this;
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    public RetentionRule setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    public int getKeepDaily() {
        return keepDaily;
    }

    public RetentionRule setKeepDaily(int keepDaily) {
        this.keepDaily = keepDaily;
        return this;
    }

    public int getKeepWeekly() {
        return keepWeekly;
    }

    public RetentionRule setKeepWeekly(int keepWeekly) {
        this.keepWeekly = keepWeekly;
        return this;
    }

    public int getKeepMonthly() {
        return keepMonthly;
    }

    public RetentionRule setKeepMonthly(int keepMonthly) {
        this.keepMonthly = keepMonthly;
        return this;
    }

    public int getKeepYearly() {
        return keepYearly;
    }

    public RetentionRule setKeepYearly(int keepYearly) {
        this.keepYearly = keepYearly;
        return this;
    }

    public int getMinimumKept() {
        return minimumKept;
    }

    public RetentionRule setMinimumKept(int minimumKept) {
        this.minimumKept = minimumKept;
        return this;
    }

    @Override
    public String toString() {
        if (!isEnabled()) {
            return "RetentionRule[unlimited]";
        }
        StringBuilder sb = new StringBuilder("RetentionRule[");
        if (keepLast > 0) {
            sb.append("keepLast=").append(keepLast).append(' ');
        }
        if (maxAge != null) {
            sb.append("maxAge=").append(maxAge).append(' ');
        }
        if (keepDaily > 0 || keepWeekly > 0 || keepMonthly > 0 || keepYearly > 0) {
            sb.append("gfs=").append(keepDaily).append('/').append(keepWeekly)
                    .append('/').append(keepMonthly).append('/').append(keepYearly).append(' ');
        }
        return sb.append("min=").append(minimumKept).append(']').toString();
    }
}
