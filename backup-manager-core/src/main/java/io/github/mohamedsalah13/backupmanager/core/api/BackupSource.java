package io.github.mohamedsalah13.backupmanager.core.api;

import io.github.mohamedsalah13.backupmanager.core.model.BackupType;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Set;

/**
 * Something that can be backed up and restored — a database, a directory tree, a
 * configuration store.
 *
 * <p>The contract is deliberately symmetric: {@link #dump} writes a payload to a stream
 * and {@link #restore} reads that same payload back. Compression, encryption, checksums,
 * fan-out to destinations and retention are the engine's job, so an implementation only
 * has to know how to serialise its own data.
 *
 * <p>Implementations must not close the stream they are given — the engine owns it and
 * has more work to do on the way out.
 */
public interface BackupSource extends AutoCloseable {

    /** Stable identifier persisted on every record, e.g. {@code "mysql"} or {@code "files"}. */
    String type();

    /** Human-readable description of what this instance points at, for logs and UI. */
    default String describe() {
        return type();
    }

    /**
     * Writes the raw payload to {@code out}. Do not close {@code out}.
     *
     * @throws Exception any failure; the engine wraps it and marks the run failed
     */
    void dump(BackupContext ctx, OutputStream out) throws Exception;

    /**
     * Reads a payload previously written by {@link #dump} and applies it. Do not close
     * {@code in}.
     */
    void restore(RestoreContext ctx, InputStream in) throws Exception;

    /** Extension for the raw payload, before compression — {@code "sql"}, {@code "tar"}. */
    default String defaultExtension() {
        return "bin";
    }

    /** Backup types this source can actually produce. */
    default Set<BackupType> supportedTypes() {
        return EnumSet.of(BackupType.FULL);
    }

    /**
     * Checks reachability and configuration before a run starts, so a broken setup fails
     * fast and loudly instead of producing a half-written artifact.
     *
     * @throws io.github.mohamedsalah13.backupmanager.core.model.BackupException when unusable
     */
    default void validate() {
    }

    /**
     * Estimated payload size in bytes, or {@code -1} when unknown. Used only to turn an
     * indeterminate progress bar into a real one.
     */
    default long estimateSize() {
        return -1L;
    }

    @Override
    default void close() throws Exception {
    }
}
