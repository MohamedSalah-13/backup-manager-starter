package com.backupmanager.core.model;

import java.util.Objects;

/**
 * Where one copy of an artifact ended up. A single backup record holds one of these
 * per destination, so a run that uploads to local disk and S3 produces two.
 */
public final class StoredLocation {

    private String providerName;
    private String path;
    private long size;
    private boolean available = true;
    private String failureMessage;

    /** Jackson. */
    StoredLocation() {
    }

    private StoredLocation(String providerName, String path, long size, boolean available, String failureMessage) {
        this.providerName = providerName;
        this.path = path;
        this.size = size;
        this.available = available;
        this.failureMessage = failureMessage;
    }

    public static StoredLocation success(String providerName, String path, long size) {
        return new StoredLocation(providerName, path, size, true, null);
    }

    public static StoredLocation failure(String providerName, String path, String failureMessage) {
        return new StoredLocation(providerName, path, 0L, false, failureMessage);
    }

    public String getProviderName() {
        return providerName;
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    /** False when the upload failed or a later verification found the copy missing. */
    public boolean isAvailable() {
        return available;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void markUnavailable(String reason) {
        this.available = false;
        this.failureMessage = reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StoredLocation other)) {
            return false;
        }
        return Objects.equals(providerName, other.providerName) && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerName, path);
    }

    @Override
    public String toString() {
        return providerName + ":" + path + (available ? "" : " (unavailable)");
    }
}
