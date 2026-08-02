package com.backupmanager.core.retention;

import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupStatus;
import com.backupmanager.core.model.RetentionRule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private static BackupRecord record(String id, int daysAgo, BackupStatus status) {
        BackupRecord r = new BackupRecord();
        r.setId(id);
        r.setJobName("job");
        r.setStartedAt(NOW.minus(Duration.ofDays(daysAgo)));
        r.setStatus(status);
        return r;
    }

    private static BackupRecord ok(String id, int daysAgo) {
        return record(id, daysAgo, BackupStatus.SUCCESS);
    }

    private static List<String> idsOf(List<BackupRecord> records) {
        return records.stream().map(BackupRecord::getId).sorted().toList();
    }

    @Test
    void keepsAllWhenNoRuleIsEnabled() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.unlimited(), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("a", 1), ok("b", 100), ok("c", 1000));

        assertThat(policy.selectForDeletion(records, CLOCK)).isEmpty();
    }

    @Test
    void keepsOnlyTheNewestN() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.keepLast(2), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("newest", 1), ok("middle", 2), ok("oldest", 3));

        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("oldest");
    }

    @Test
    void deletesBeyondTheMaximumAge() {
        var policy = new RuleBasedRetentionPolicy(
                RetentionRule.maxAgeDays(30).setMinimumKept(0), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("fresh", 5), ok("edge", 29), ok("stale", 45));

        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("stale");
    }

    @Test
    void failedRunsDoNotConsumeTheKeepQuota() {
        // The scenario this guards: a week of failures must not evict the last good backup.
        var policy = new RuleBasedRetentionPolicy(RetentionRule.keepLast(2), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(
                record("fail1", 1, BackupStatus.FAILED),
                record("fail2", 2, BackupStatus.FAILED),
                record("fail3", 3, BackupStatus.FAILED),
                ok("good1", 4),
                ok("good2", 5),
                ok("good3", 6));

        List<String> deleted = idsOf(policy.selectForDeletion(records, CLOCK));

        assertThat(deleted).contains("good3");
        assertThat(deleted).doesNotContain("good1", "good2");
    }

    @Test
    void minimumKeptOverridesAnAggressiveAgeRule() {
        var policy = new RuleBasedRetentionPolicy(
                RetentionRule.maxAgeDays(1).setMinimumKept(2), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("a", 100), ok("b", 200), ok("c", 300));

        // Every backup is older than the limit, but the floor rescues the two newest.
        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("c");
    }

    @Test
    void neverDeletesEverything() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.maxAgeDays(1), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("only", 9999));

        assertThat(policy.selectForDeletion(records, CLOCK)).isEmpty();
    }

    @Test
    void gfsKeepsOneBackupPerPeriod() {
        // One backup a day for 40 days.
        List<BackupRecord> records = new ArrayList<>();
        for (int day = 0; day < 40; day++) {
            records.add(ok("day-" + day, day));
        }
        var policy = new RuleBasedRetentionPolicy(RetentionRule.gfs(7, 4, 2, 0), ZoneId.of("UTC"));

        List<BackupRecord> deleted = policy.selectForDeletion(records, CLOCK);
        List<String> kept = records.stream()
                .map(BackupRecord::getId)
                .filter(id -> deleted.stream().noneMatch(d -> d.getId().equals(id)))
                .toList();

        // Seven dailies, plus weekly and monthly anchors that partly overlap them.
        assertThat(kept).hasSizeBetween(7, 13);
        assertThat(kept).contains("day-0", "day-1", "day-6");
        assertThat(deleted).isNotEmpty();
    }

    @Test
    void rulesCombineAsAUnionOfWhatToKeep() {
        // keepLast(1) alone would drop everything older; the age rule rescues more.
        var policy = new RuleBasedRetentionPolicy(
                RetentionRule.keepLast(1).setMaxAge(Duration.ofDays(10)), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(ok("a", 1), ok("b", 5), ok("c", 9), ok("d", 40));

        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("d");
    }

    @Test
    void prunesFailedRunsOlderThanTheOldestKeptBackup() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.keepLast(1), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(
                ok("keep", 1),
                record("ancient-failure", 90, BackupStatus.FAILED));

        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("ancient-failure");
    }

    @Test
    void handlesAnEmptyHistory() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.keepLast(5), ZoneId.of("UTC"));

        assertThat(policy.selectForDeletion(List.of(), CLOCK)).isEmpty();
    }

    @Test
    void treatsPartialBackupsAsRestorable() {
        var policy = new RuleBasedRetentionPolicy(RetentionRule.keepLast(1), ZoneId.of("UTC"));
        List<BackupRecord> records = List.of(
                record("partial", 1, BackupStatus.PARTIAL),
                ok("older", 2));

        // A partial copy still exists somewhere, so it counts toward the quota.
        assertThat(idsOf(policy.selectForDeletion(records, CLOCK))).containsExactly("older");
    }
}
