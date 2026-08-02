package com.backupmanager.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streaming encryption stage, applied after compression so the codec still sees
 * compressible plaintext.
 */
public interface Encryptor {

    /** Stable id persisted on the record, e.g. {@code "aes-gcm"} or {@code "none"}. */
    String id();

    /** False for the pass-through implementation, which lets the engine skip the stage. */
    boolean isEnabled();

    OutputStream encrypt(OutputStream out) throws IOException;

    /**
     * @throws com.backupmanager.core.model.BackupException.Crypto on a wrong key or a
     *         tampered artifact
     */
    InputStream decrypt(InputStream in) throws IOException;

    /** Suffix appended to the artifact name, including the dot, or empty for none. */
    default String extension() {
        return isEnabled() ? ".enc" : "";
    }
}
