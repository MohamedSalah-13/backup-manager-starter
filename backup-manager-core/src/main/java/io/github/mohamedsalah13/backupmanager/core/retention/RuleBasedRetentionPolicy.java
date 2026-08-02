package io.github.mohamedsalah13.backupmanager.core.retention;

import io.github.mohamedsalah13.backupmanager.core.api.RetentionPolicy;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.RetentionRule;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Applies a {@link RetentionRule}. Rules combine as a union of what to keep, so enabling a
 * second rule can only ever preserve more backups, never fewer.
 *
 * <p>Two details matter more than they look:
 *
 * <ul>
 *   <li>Failed runs do not consume the keep quota. Otherwise a week of failures with
 *       {@code keepLast=7} would quietly evict every good backup and leave nothing to
 *       restore.
 *   <li>{@link RetentionRule#getMinimumKept()} is a floor applied last, so even a
 *       misconfigured age rule cannot empty the archive.
 * </ul>
 */
public class RuleBasedRetentionPolicy implements RetentionPolicy {

    private final RetentionRule rule;
    private final ZoneId zone;

    public RuleBasedRetentionPolicy(RetentionRule rule) {
        this(rule, ZoneId.systemDefault());
    }

    /**
     * @param zone calendar used to bucket GFS periods; pin it so a server relocation does
     *             not silently reshuffle which backup counts as "Monday's"
     */
    public RuleBasedRetentionPolicy(RetentionRule rule, ZoneId zone) {
        this.rule = rule != null ? rule : RetentionRule.unlimited();
        this.zone = zone != null ? zone : ZoneId.systemDefault();
    }

    @Override
    public List<BackupRecord> selectForDeletion(List<BackupRecord> records, Clock clock) {
        if (!rule.isEnabled() || records == null || records.isEmpty()) {
            return List.of();
        }
        Instant now = clock != null ? clock.instant() : Instant.now();

        List<BackupRecord> restorable = records.stream()
                .filter(r -> r.getStatus().isRestorable())
                .sorted(Comparator.comparing(BackupRecord::getStartedAt).reversed())
                .toList();

        if (restorable.isEmpty()) {
            return List.of();
        }

        Set<String> keep = new LinkedHashSet<>();

        if (rule.getKeepLast() > 0) {
            restorable.stream().limit(rule.getKeepLast()).forEach(r -> keep.add(r.getId()));
        }
        if (rule.getMaxAge() != null) {
            Instant cutoff = now.minus(rule.getMaxAge());
            restorable.stream()
                    .filter(r -> !r.getStartedAt().isBefore(cutoff))
                    .forEach(r -> keep.add(r.getId()));
        }
        addGfsKeeps(restorable, keep);

        // Floor, applied after every rule so it always wins.
        int floor = Math.max(rule.getMinimumKept(), 0);
        restorable.stream().limit(floor).forEach(r -> keep.add(r.getId()));

        List<BackupRecord> toDelete = new ArrayList<>(
                restorable.stream().filter(r -> !keep.contains(r.getId())).toList());

        // Failed runs left no artifact, but they should not accumulate in the catalog
        // forever either. Drop the ones older than the oldest backup still kept.
        Instant oldestKept = restorable.stream()
                .filter(r -> keep.contains(r.getId()))
                .map(BackupRecord::getStartedAt)
                .min(Comparator.naturalOrder())
                .orElse(now);

        records.stream()
                .filter(r -> !r.getStatus().isRestorable())
                .filter(r -> r.getStartedAt().isBefore(oldestKept))
                .forEach(toDelete::add);

        return List.copyOf(toDelete);
    }

    private void addGfsKeeps(List<BackupRecord> restorable, Set<String> keep) {
        if (rule.getKeepDaily() > 0) {
            keepNewestPerPeriod(restorable, keep, rule.getKeepDaily(),
                    t -> t.toLocalDate().toString());
        }
        if (rule.getKeepWeekly() > 0) {
            keepNewestPerPeriod(restorable, keep, rule.getKeepWeekly(),
                    t -> t.get(IsoFields.WEEK_BASED_YEAR) + "-W" + t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }
        if (rule.getKeepMonthly() > 0) {
            keepNewestPerPeriod(restorable, keep, rule.getKeepMonthly(),
                    t -> t.getYear() + "-" + t.getMonthValue());
        }
        if (rule.getKeepYearly() > 0) {
            keepNewestPerPeriod(restorable, keep, rule.getKeepYearly(),
                    t -> String.valueOf(t.getYear()));
        }
    }

    /**
     * Keeps the newest backup of each of the most recent {@code periods} buckets. Periods
     * with no backup at all are skipped rather than counted, so a week of downtime does
     * not cost the archive a week of history.
     */
    private void keepNewestPerPeriod(List<BackupRecord> newestFirst, Set<String> keep,
                                     int periods, Function<ZonedDateTime, String> bucketKey) {
        Map<String, BackupRecord> newestPerBucket = new LinkedHashMap<>();
        for (BackupRecord record : newestFirst) {
            String key = bucketKey.apply(record.getStartedAt().atZone(zone));
            newestPerBucket.putIfAbsent(key, record);
        }
        newestPerBucket.values().stream()
                .limit(periods)
                .forEach(r -> keep.add(r.getId()));
    }

    @Override
    public String describe() {
        return rule.toString();
    }

    public RetentionRule getRule() {
        return rule;
    }
}
