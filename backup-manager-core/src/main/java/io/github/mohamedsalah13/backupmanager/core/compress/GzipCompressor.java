package io.github.mohamedsalah13.backupmanager.core.compress;

import io.github.mohamedsalah13.backupmanager.core.api.Compressor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip, the sensible default for SQL dumps — they compress to roughly a tenth of their
 * size, and every platform has a tool that can open the result.
 */
public final class GzipCompressor implements Compressor {

    private static final int BUFFER = 64 * 1024;

    private final int level;

    public GzipCompressor() {
        this(Deflater.DEFAULT_COMPRESSION);
    }

    /**
     * @param level {@code 0}–{@code 9}, or {@link Deflater#DEFAULT_COMPRESSION}. Above 6
     *              the extra CPU rarely pays for itself on text.
     */
    public GzipCompressor(int level) {
        if (level != Deflater.DEFAULT_COMPRESSION && (level < 0 || level > 9)) {
            throw new IllegalArgumentException("Gzip level must be 0..9 or -1 but was " + level);
        }
        this.level = level;
    }

    @Override
    public String id() {
        return "gzip";
    }

    @Override
    public String extension() {
        return ".gz";
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        // GZIPOutputStream exposes no level setter, so reach the protected Deflater at construction.
        return new GZIPOutputStream(out, BUFFER) {
            {
                def.setLevel(level);
            }
        };
    }

    @Override
    public InputStream decompress(InputStream in) throws IOException {
        return new GZIPInputStream(in, BUFFER);
    }
}
