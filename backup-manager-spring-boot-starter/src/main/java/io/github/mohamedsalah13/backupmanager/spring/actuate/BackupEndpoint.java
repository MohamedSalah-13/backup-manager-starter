package io.github.mohamedsalah13.backupmanager.spring.actuate;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator endpoint at {@code /actuator/backup}: inspect history and trigger a run.
 *
 * <p>The write operation starts a backup, so expose this endpoint only on a secured
 * management port. It is read-mostly by design — restore is deliberately absent, since
 * overwriting a live database is not something that should be one HTTP call away.
 */
@Endpoint(id = "backup")
public class BackupEndpoint {

    private final BackupManager manager;

    public BackupEndpoint(BackupManager manager) {
        this.manager = manager;
    }

    /** Every job with its current status. */
    @ReadOperation
    public Map<String, Object> jobs() {
        Map<String, Object> result = new LinkedHashMap<>();
        manager.status().forEach((name, status) -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("enabled", status.enabled());
            detail.put("running", status.running());
            detail.put("lastStatus", status.lastStatus());
            detail.put("lastRun", status.lastRun());
            detail.put("nextRun", status.nextRun());
            detail.put("totalBackups", status.totalBackups());
            result.put(name, detail);
        });
        return result;
    }

    /** History for one job, newest first. */
    @ReadOperation
    public Map<String, Object> jobHistory(@Selector String jobName) {
        List<Map<String, Object>> history = manager.history(jobName).stream()
                .map(BackupEndpoint::describe)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", jobName);
        result.put("backups", history);
        return result;
    }

    /** Starts a backup and returns immediately; poll the read operation for the outcome. */
    @WriteOperation
    public Map<String, Object> runJob(@Selector String jobName) {
        manager.backupAsync(jobName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", jobName);
        result.put("started", true);
        return result;
    }

    private static Map<String, Object> describe(BackupRecord record) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", record.getId());
        detail.put("status", record.getStatus());
        detail.put("type", record.getType());
        detail.put("fileName", record.getFileName());
        detail.put("startedAt", record.getStartedAt());
        detail.put("durationSeconds", record.duration().toSeconds());
        detail.put("storedSize", record.getStoredSize());
        detail.put("encrypted", record.isEncrypted());
        detail.put("locations", record.getLocations().stream()
                .map(l -> l.getProviderName() + (l.isAvailable() ? "" : " (unavailable)"))
                .toList());
        if (record.getErrorMessage() != null) {
            detail.put("error", record.getErrorMessage());
        }
        return detail;
    }
}
