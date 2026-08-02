package io.github.mohamedsalah13.backupmanager.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streaming compression stage. Wrappers must not close the stream they wrap when the
 * wrapper is closed only to flush a trailer — the engine closes the chain in order.
 */
public interface Compressor {

    /** Stable id persisted on the record so a restore can pick the same codec. */
    String id();

    /** Suffix appended to the artifact name, including the dot, or empty for none. */
    String extension();

    /** Wraps {@code out} so bytes written are compressed. Closing the result flushes the trailer. */
    OutputStream compress(OutputStream out) throws IOException;

    /** Wraps {@code in} so bytes read are decompressed. */
    InputStream decompress(InputStream in) throws IOException;

    default boolean isEnabled() {
        return true;
    }
}
