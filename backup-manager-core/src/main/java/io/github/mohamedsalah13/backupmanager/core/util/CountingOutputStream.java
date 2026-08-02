package io.github.mohamedsalah13.backupmanager.core.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/**
 * Counts bytes written and reports them at intervals, so progress can be shown for a
 * stream whose total length is unknown.
 */
public class CountingOutputStream extends FilterOutputStream {

    private static final long DEFAULT_REPORT_EVERY = 1024L * 1024L;

    private final LongConsumer reporter;
    private final long reportEvery;
    private long count;
    private long lastReported;

    public CountingOutputStream(OutputStream out) {
        this(out, null, DEFAULT_REPORT_EVERY);
    }

    public CountingOutputStream(OutputStream out, LongConsumer reporter) {
        this(out, reporter, DEFAULT_REPORT_EVERY);
    }

    public CountingOutputStream(OutputStream out, LongConsumer reporter, long reportEvery) {
        super(out);
        this.reporter = reporter;
        this.reportEvery = reportEvery > 0 ? reportEvery : DEFAULT_REPORT_EVERY;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        advance(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        // FilterOutputStream's inherited version writes byte by byte; delegate in bulk instead.
        out.write(b, off, len);
        advance(len);
    }

    private void advance(long delta) {
        count += delta;
        if (reporter != null && count - lastReported >= reportEvery) {
            lastReported = count;
            reporter.accept(count);
        }
    }

    public long getCount() {
        return count;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (reporter != null && count != lastReported) {
            lastReported = count;
            reporter.accept(count);
        }
    }
}
