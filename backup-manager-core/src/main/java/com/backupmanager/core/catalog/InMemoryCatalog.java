package com.backupmanager.core.catalog;

import com.backupmanager.core.api.BackupCatalog;
import com.backupmanager.core.model.BackupRecord;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volatile catalog, for tests and for callers that keep their own index. Everything is
 * lost on restart, so it is never the right choice for a real deployment.
 */
public class InMemoryCatalog implements BackupCatalog {

    private final Map<String, BackupRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(BackupRecord record) {
        records.put(record.getId(), record.copy());
    }

    @Override
    public Optional<BackupRecord> findById(String id) {
        return Optional.ofNullable(records.get(id)).map(BackupRecord::copy);
    }

    @Override
    public List<BackupRecord> findByJob(String jobName) {
        return records.values().stream()
                .filter(r -> jobName.equals(r.getJobName()))
                .sorted(Comparator.comparing(BackupRecord::getStartedAt).reversed())
                .map(BackupRecord::copy)
                .toList();
    }

    @Override
    public List<BackupRecord> findAll() {
        return records.values().stream()
                .sorted(Comparator.comparing(BackupRecord::getStartedAt).reversed())
                .map(BackupRecord::copy)
                .toList();
    }

    @Override
    public void delete(String id) {
        records.remove(id);
    }

    @Override
    public List<String> jobNames() {
        return records.values().stream()
                .map(BackupRecord::getJobName)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    public int size() {
        return records.size();
    }

    public void clear() {
        records.clear();
    }
}
