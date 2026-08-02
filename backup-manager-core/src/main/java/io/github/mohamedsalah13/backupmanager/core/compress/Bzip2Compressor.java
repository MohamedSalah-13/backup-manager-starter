package io.github.mohamedsalah13.backupmanager.core.compress;

import io.github.mohamedsalah13.backupmanager.core.api.Compressor;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bzip2. Noticeably smaller output than gzip on large SQL dumps, at several times the CPU
 * cost — worth it when the backup crosses a slow link or a metered cloud bucket.
 */
public final class Bzip2Compressor implements Compressor {

    private final int blockSize;

    public Bzip2Compressor() {
        this(BZip2CompressorOutputStream.MAX_BLOCKSIZE);
    }

    /** @param blockSize 1–9, in units of 100 kB */
    public Bzip2Compressor(int blockSize) {
        if (blockSize < BZip2CompressorOutputStream.MIN_BLOCKSIZE
                || blockSize > BZip2CompressorOutputStream.MAX_BLOCKSIZE) {
            throw new IllegalArgumentException("Bzip2 block size must be 1..9 but was " + blockSize);
        }
        this.blockSize = blockSize;
    }

    @Override
    public String id() {
        return "bzip2";
    }

    @Override
    public String extension() {
        return ".bz2";
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        return new BZip2CompressorOutputStream(out, blockSize);
    }

    @Override
    public InputStream decompress(InputStream in) throws IOException {
        // Multi-stream mode reads files produced by pbzip2 as well as our own single stream.
        return new BZip2CompressorInputStream(in, true);
    }
}
