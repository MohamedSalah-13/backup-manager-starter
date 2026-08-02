package com.backupmanager.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog entry describing one backup run: what was captured, how it was packaged,
 * and where every copy of it lives. This is the object a restore is driven from, so it
 * carries enough detail to reverse the pipeline without consulting the original config.
 */
public class BackupRecord {

    private String id = UUID.randomUUID().toString();
    private String jobName;
    private String sourceType;
    private BackupType type = BackupType.FULL;
    private BackupStatus status = BackupStatus.PENDING;

    private Instant startedAt = Instant.now();
    private Instant finishedAt;

    private String fileName;
    /** Size of the artifact as written to storage, after compression and encryption. */
    private long storedSize;
    /** Size of the raw dump before compression, when known. */
    private long rawSize;

    private String compressorId = "none";
    private boolean encrypted;
    private String checksumAlgorithm = "SHA-256";
    private String checksum;

    /** Id of the backup this one builds on, for incremental and differential chains. */
    private String baseRecordId;

    private final List<StoredLocation> locations = new ArrayList<>();
    private String errorMessage;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public BackupType getType() {
        return type;
    }

    public void setType(BackupType type) {
        this.type = type;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public void setStatus(BackupStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getStoredSize() {
        return storedSize;
    }

    public void setStoredSize(long storedSize) {
        this.storedSize = storedSize;
    }

    public long getRawSize() {
        return rawSize;
    }

    public void setRawSize(long rawSize) {
        this.rawSize = rawSize;
    }

    public String getCompressorId() {
        return compressorId;
    }

    public void setCompressorId(String compressorId) {
        this.compressorId = compressorId;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public void setChecksumAlgorithm(String checksumAlgorithm) {
        this.checksumAlgorithm = checksumAlgorithm;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getBaseRecordId() {
        return baseRecordId;
    }

    public void setBaseRecordId(String baseRecordId) {
        this.baseRecordId = baseRecordId;
    }

    public List<StoredLocation> getLocations() {
        return locations;
    }

    public void setLocations(List<StoredLocation> value) {
        this.locations.clear();
        if (value != null) {
            this.locations.addAll(value);
        }
    }

    public void addLocation(StoredLocation location) {
        this.locations.add(location);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> value) {
        this.attributes.clear();
        if (value != null) {
            this.attributes.putAll(value);
        }
    }

    public void putAttribute(String key, String value) {
        this.attributes.put(key, value);
    }

    /** Copies that are still usable for a restore. */
    public List<StoredLocation> availableLocations() {
        return locations.stream().filter(StoredLocation::isAvailable).toList();
    }

    public Duration duration() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    /** Ratio of stored to raw size; empty when the raw size was never measured. */
    public double compressionRatio() {
        if (rawSize <= 0 || storedSize <= 0) {
            return 0d;
        }
        return (double) storedSize / (double) rawSize;
    }

    /** Immutable snapshot, safe to hand to a UI thread. */
    public BackupRecord copy() {
        BackupRecord c = new BackupRecord();
        c.id = id;
        c.jobName = jobName;
        c.sourceType = sourceType;
        c.type = type;
        c.status = status;
        c.startedAt = startedAt;
        c.finishedAt = finishedAt;
        c.fileName = fileName;
        c.storedSize = storedSize;
        c.rawSize = rawSize;
        c.compressorId = compressorId;
        c.encrypted = encrypted;
        c.checksumAlgorithm = checksumAlgorithm;
        c.checksum = checksum;
        c.baseRecordId = baseRecordId;
        c.locations.addAll(locations);
        c.errorMessage = errorMessage;
        c.attributes.putAll(attributes);
        return c;
    }

    @Override
    public String toString() {
        return "BackupRecord[" + jobName + "/" + fileName + " " + status + " "
                + Collections.unmodifiableList(locations) + "]";
    }
}
