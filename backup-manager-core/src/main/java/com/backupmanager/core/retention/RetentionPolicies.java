package com.backupmanager.core.retention;

import com.backupmanager.core.api.RetentionPolicy;
import com.backupmanager.core.model.RetentionRule;

import java.time.Duration;
import java.time.ZoneId;

/** Shorthand constructors for the retention setups desktop applications actually use. */
public final class RetentionPolicies {

    private RetentionPolicies() {
    }

    public static RetentionPolicy keepAll() {
        return RetentionPolicy.KEEP_ALL;
    }

    public static RetentionPolicy keepLast(int count) {
        return new RuleBasedRetentionPolicy(RetentionRule.keepLast(count));
    }

    public static RetentionPolicy maxAgeDays(int days) {
        return new RuleBasedRetentionPolicy(RetentionRule.maxAgeDays(days));
    }

    public static RetentionPolicy maxAge(Duration age) {
        return new RuleBasedRetentionPolicy(RetentionRule.maxAge(age));
    }

    /** Grandfather-father-son rotation. */
    public static RetentionPolicy gfs(int daily, int weekly, int monthly, int yearly) {
        return new RuleBasedRetentionPolicy(RetentionRule.gfs(daily, weekly, monthly, yearly));
    }

    /** Seven dailies, four weeklies and twelve monthlies — a common default. */
    public static RetentionPolicy standard() {
        return gfs(7, 4, 12, 0);
    }

    public static RetentionPolicy of(RetentionRule rule) {
        return new RuleBasedRetentionPolicy(rule);
    }

    public static RetentionPolicy of(RetentionRule rule, ZoneId zone) {
        return new RuleBasedRetentionPolicy(rule, zone);
    }
}
