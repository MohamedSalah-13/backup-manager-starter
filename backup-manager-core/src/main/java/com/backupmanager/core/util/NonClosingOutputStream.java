package com.backupmanager.core.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shields a stream from being closed by a stage that owns only part of the chain.
 *
 * <p>The engine hands sources and codecs a view of the staging stream; several of them
 * close what they are given as a matter of course. Closing here flushes but leaves the
 * underlying stream open, so later stages can still write their trailers.
 */
public final class NonClosingOutputStream extends FilterOutputStream {

    public NonClosingOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        flush();
    }

    /** Input-side counterpart. */
    public static final class NonClosingInputStream extends java.io.FilterInputStream {

        public NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // deliberately left open
        }
    }
}
