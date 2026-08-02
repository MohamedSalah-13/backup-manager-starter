package io.github.mohamedsalah13.backupmanager.core.catalog;

import io.github.mohamedsalah13.backupmanager.core.api.BackupCatalog;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Catalog backed by a single JSON file. The whole index is held in memory and rewritten on
 * every change, which is the right trade-off here: even a decade of nightly backups is a
 * few thousand records.
 *
 * <p>Writes go to a temporary file that is then moved into place, and the previous
 * version is kept as {@code .bak}. Losing the catalog means losing the ability to restore,
 * so it is worth the extra copy.
 */
public class JsonFileCatalog implements BackupCatalog {

    private static final Logger log = LoggerFactory.getLogger(JsonFileCatalog.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, BackupRecord> records = new LinkedHashMap<>();

    public JsonFileCatalog(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Tolerate records written by a newer build that added fields.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        load();
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(file)) {
                log.debug("No catalog at {} yet; starting empty", file);
                return;
            }
            readInto(file);
            log.info("Loaded {} backup records from {}", records.size(), file);
        } catch (IOException e) {
            Path backup = backupPath();
            log.error("Catalog at {} is unreadable ({}); trying {}", file, e.getMessage(), backup);
            try {
                if (Files.exists(backup)) {
                    readInto(backup);
                    log.warn("Recovered {} records from the catalog backup", records.size());
                    return;
                }
            } catch (IOException secondary) {
                log.error("Catalog backup is also unreadable: {}", secondary.getMessage());
            }
            throw new BackupException(
                    "Backup catalog at " + file + " is corrupt and no usable backup copy exists. "
                            + "Move it aside to start a fresh catalog; existing artifacts stay on disk.", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void readInto(Path source) throws IOException {
        List<BackupRecord> loaded = mapper.readValue(
                Files.readAllBytes(source), new TypeReference<List<BackupRecord>>() {
                });
        records.clear();
        for (BackupRecord record : loaded) {
            if (record.getId() != null) {
                records.put(record.getId(), record);
            }
        }
    }

    @Override
    public void save(BackupRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.getId() == null) {
            throw new IllegalArgumentException("BackupRecord must have an id before it is saved");
        }
        lock.writeLock().lock();
        try {
            records.put(record.getId(), record.copy());
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<BackupRecord> findById(String id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(records.get(id)).map(BackupRecord::copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<BackupRecord> findByJob(String jobName) {
        lock.readLock().lock();
        try {
            return records.values().stream()
                    .filter(r -> Objects.equals(jobName, r.getJobName()))
                    .sorted(Comparator.comparing(BackupRecord::getStartedAt).reversed())
                    .map(BackupRecord::copy)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<BackupRecord> findAll() {
        lock.readLock().lock();
        try {
            return records.values().stream()
                    .sorted(Comparator.comparing(BackupRecord::getStartedAt).reversed())
                    .map(BackupRecord::copy)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(String id) {
        lock.writeLock().lock();
        try {
            if (records.remove(id) != null) {
                persist();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<String> jobNames() {
        lock.readLock().lock();
        try {
            return records.values().stream()
                    .map(BackupRecord::getJobName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void flush() {
        lock.writeLock().lock();
        try {
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Caller must hold the write lock. */
    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            List<BackupRecord> snapshot = new ArrayList<>(records.values());
            mapper.writeValue(temp.toFile(), snapshot);

            if (Files.exists(file)) {
                Files.copy(file, backupPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BackupException("Could not write the backup catalog to " + file, e);
        }
    }

    private Path backupPath() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public Path getFile() {
        return file;
    }

    public int size() {
        lock.readLock().lock();
        try {
            return records.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
