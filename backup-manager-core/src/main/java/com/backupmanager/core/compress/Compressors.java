package com.backupmanager.core.compress;

import com.backupmanager.core.api.Compressor;
import com.backupmanager.core.model.BackupException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps the codec id stored on a record back to an implementation, so a
 * restore can decompress an artifact written months earlier by a different configuration.
 *
 * <p>Register custom codecs at startup with {@link #register}; ids are matched
 * case-insensitively.
 */
public final class Compressors {

    private static final Map<String, Compressor> REGISTRY = new ConcurrentHashMap<>();

    static {
        register(NoCompression.INSTANCE);
        register(new GzipCompressor());
        register(new ZipCompressor());
        register(new Bzip2Compressor());
    }

    private Compressors() {
    }

    public static void register(Compressor compressor) {
        REGISTRY.put(compressor.id().toLowerCase(Locale.ROOT), compressor);
    }

    /**
     * @throws BackupException.Configuration when no codec is registered under {@code id},
     *         which on restore means the artifact was written by a build that had one more
     *         codec than this one
     */
    public static Compressor byId(String id) {
        if (id == null || id.isBlank()) {
            return NoCompression.INSTANCE;
        }
        Compressor found = REGISTRY.get(id.toLowerCase(Locale.ROOT));
        if (found == null) {
            throw new BackupException.Configuration(
                    "Unknown compressor '" + id + "'. Registered: " + availableIds());
        }
        return found;
    }

    public static List<String> availableIds() {
        return REGISTRY.keySet().stream().sorted().toList();
    }

    /** Snapshot of the registry, for a settings dropdown. */
    public static Map<String, Compressor> all() {
        return new LinkedHashMap<>(REGISTRY);
    }
}
