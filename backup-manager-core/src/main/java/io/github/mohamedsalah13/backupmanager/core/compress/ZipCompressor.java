package io.github.mohamedsalah13.backupmanager.core.compress;

import io.github.mohamedsalah13.backupmanager.core.api.Compressor;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip with a single entry. Compresses slightly worse than gzip but Windows opens it with
 * a double-click, which matters when an operator has to inspect a backup by hand.
 */
public final class ZipCompressor implements Compressor {

    private static final String ENTRY_NAME = "backup.dat";

    private final int level;

    public ZipCompressor() {
        this(Deflater.DEFAULT_COMPRESSION);
    }

    public ZipCompressor(int level) {
        if (level != Deflater.DEFAULT_COMPRESSION && (level < 0 || level > 9)) {
            throw new IllegalArgumentException("Zip level must be 0..9 or -1 but was " + level);
        }
        this.level = level;
    }

    @Override
    public String id() {
        return "zip";
    }

    @Override
    public String extension() {
        return ".zip";
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(level);
        zip.putNextEntry(new ZipEntry(ENTRY_NAME));
        // closeEntry on close() is handled by ZipOutputStream itself when finishing.
        return zip;
    }

    @Override
    public InputStream decompress(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry = zip.getNextEntry();
        if (entry == null) {
            throw new BackupException.Verification("Zip artifact contains no entries");
        }
        return zip;
    }
}
