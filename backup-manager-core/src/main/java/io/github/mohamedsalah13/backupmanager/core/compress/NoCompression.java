package io.github.mohamedsalah13.backupmanager.core.compress;

import io.github.mohamedsalah13.backupmanager.core.api.Compressor;

import java.io.InputStream;
import java.io.OutputStream;

/** Pass-through stage, for payloads that are already compressed or must stay readable. */
public final class NoCompression implements Compressor {

    public static final NoCompression INSTANCE = new NoCompression();

    @Override
    public String id() {
        return "none";
    }

    @Override
    public String extension() {
        return "";
    }

    @Override
    public OutputStream compress(OutputStream out) {
        return out;
    }

    @Override
    public InputStream decompress(InputStream in) {
        return in;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
