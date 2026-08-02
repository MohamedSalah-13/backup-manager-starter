package io.github.mohamedsalah13.backupmanager.examples.service;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.engine.RestoreEngine;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP layer over the injected {@link BackupManager}.
 *
 * <p>Restore is a deliberate outlier here: it overwrites live data, so it requires an
 * explicit {@code confirm=true} parameter. In a real service it would sit behind an admin
 * role as well — a backup endpoint is a data-destruction endpoint pointed the other way.
 */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);

    private final BackupManager backups;

    public BackupController(BackupManager backups) {
        this.backups = backups;
    }

    /** Status of every configured job. */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        backups.status().forEach((name, status) -> result.put(name, Map.of(
                "enabled", status.enabled(),
                "running", status.running(),
                "lastStatus", String.valueOf(status.lastStatus()),
                "lastRun", String.valueOf(status.lastRun()),
                "nextRun", String.valueOf(status.nextRun()),
                "totalBackups", status.totalBackups())));
        return result;
    }

    /** History for one job, newest first. */
    @GetMapping("/{job}")
    public List<Map<String, Object>> history(@PathVariable String job) {
        return backups.history(job).stream().map(BackupController::describe).toList();
    }

    /**
     * Runs a backup and waits for it to finish, so the response carries the outcome.
     *
     * <p>A production service would return 202 and a job id instead — a large database
     * dump will outlive any sensible HTTP timeout.
     */
    @PostMapping("/{job}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String job) {
        if (backups.getJob(job).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backup job named '" + job + "'");
        }
        BackupRecord record = backups.backupNow(job);
        HttpStatus status = record.getStatus().isRestorable()
                ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(describe(record));
    }

    /** Reads a backup end to end and decodes it, writing nothing. */
    @PostMapping("/records/{id}/verify")
    public Map<String, Object> verify(@PathVariable String id) {
        boolean ok = backups.testRestore(id, null);
        return Map.of("id", id, "verified", ok,
                "message", ok
                        ? "The artifact decoded end to end and can be restored."
                        : "The artifact could not be decoded. Do not rely on it.");
    }

    /** Restores a backup over the live database. Requires explicit confirmation. */
    @PostMapping("/records/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id,
                                       @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Restoring overwrites live data. Repeat the call with ?confirm=true.");
        }
        log.warn("Restoring backup {} over the live database", id);
        backups.restore(id, new RestoreEngine.RestoreOptions(), null);
        return Map.of("id", id, "restored", true);
    }

    private static Map<String, Object> describe(BackupRecord record) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", record.getId());
        detail.put("job", record.getJobName());
        detail.put("status", record.getStatus());
        detail.put("fileName", record.getFileName());
        detail.put("startedAt", record.getStartedAt());
        detail.put("durationSeconds", record.duration().toSeconds());
        detail.put("rawSize", record.getRawSize());
        detail.put("storedSize", record.getStoredSize());
        detail.put("encrypted", record.isEncrypted());
        detail.put("checksum", record.getChecksum());
        detail.put("storedOn", record.availableLocations().stream()
                .map(l -> l.getProviderName()).toList());
        if (record.getErrorMessage() != null) {
            detail.put("error", record.getErrorMessage());
        }
        return detail;
    }
}
