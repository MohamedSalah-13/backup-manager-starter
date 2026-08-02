package io.github.mohamedsalah13.backupmanager.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an external dump or restore tool ({@code mysqldump}, {@code mysql}) and pipes its
 * streams without deadlocking.
 *
 * <p>The classic trap here is reading stdout while stderr fills its pipe buffer, which
 * blocks the child forever. stderr is therefore drained on its own thread, and only the
 * tail is kept so a million warning lines cannot exhaust the heap.
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int STDERR_TAIL_LINES = 200;

    private final List<String> command = new ArrayList<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private Path workingDirectory;
    private Duration timeout = Duration.ofHours(6);
    private java.util.function.BooleanSupplier cancelled;
    private java.util.function.LongConsumer byteReporter;

    public static ProcessRunner of(String... command) {
        ProcessRunner runner = new ProcessRunner();
        runner.command.addAll(List.of(command));
        return runner;
    }

    public static ProcessRunner of(List<String> command) {
        ProcessRunner runner = new ProcessRunner();
        runner.command.addAll(command);
        return runner;
    }

    public ProcessRunner arg(String value) {
        command.add(value);
        return this;
    }

    public ProcessRunner args(List<String> values) {
        command.addAll(values);
        return this;
    }

    /**
     * Adds an environment variable. Prefer this over a command-line flag for anything
     * secret: arguments are visible to every user via the process list, the environment
     * of a child process is not.
     */
    public ProcessRunner env(String key, String value) {
        environment.put(key, value);
        return this;
    }

    public ProcessRunner workingDirectory(Path dir) {
        this.workingDirectory = dir;
        return this;
    }

    public ProcessRunner timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public ProcessRunner cancelWhen(AtomicBoolean flag) {
        this.cancelled = flag != null ? flag::get : null;
        return this;
    }

    /**
     * Polled between reads while piping, so a cancellation can kill a long-running dump.
     * Takes a supplier rather than a flag so callers can delegate straight to their own
     * state without keeping a second copy of it in sync.
     */
    public ProcessRunner cancelWhen(java.util.function.BooleanSupplier flag) {
        this.cancelled = flag;
        return this;
    }

    /** Called periodically with the running total of bytes piped to the output. */
    public ProcessRunner onBytes(java.util.function.LongConsumer reporter) {
        this.byteReporter = reporter;
        return this;
    }

    /**
     * Runs the command, copying its stdout into {@code out}.
     *
     * @return bytes copied
     * @throws IOException if the process fails, times out, or exits non-zero
     */
    public long pipeTo(OutputStream out) throws IOException, InterruptedException {
        return run(out, null);
    }

    /**
     * Runs the command, feeding {@code in} to its stdin and discarding stdout. Used to
     * replay a SQL dump through a client.
     */
    public long pipeFrom(InputStream in) throws IOException, InterruptedException {
        return run(null, in);
    }

    /** Runs the command and returns its stdout as a string. For short probes only. */
    public String captureOutput() throws IOException, InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        run(buffer, null);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private long run(OutputStream out, InputStream stdinSource) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(false);

        log.debug("Running external command: {}", maskedCommand());
        Process process = builder.start();

        Deque<String> stderrTail = new ArrayDeque<>();
        Thread stderrPump = drainStderr(process, stderrTail);

        Thread stdinPump = null;
        if (stdinSource != null) {
            stdinPump = feedStdin(process, stdinSource);
        } else {
            process.getOutputStream().close();
        }

        long copied = 0;
        try {
            if (out != null) {
                copied = copyStdout(process, out);
            } else {
                // Nobody wants stdout, but it still has to be drained or the child blocks.
                try (InputStream stdout = process.getInputStream()) {
                    stdout.transferTo(OutputStream.nullOutputStream());
                }
            }

            if (stdinPump != null) {
                stdinPump.join();
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + timeout + ": " + maskedCommand());
            }

            stderrPump.join(5_000);

            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("Command failed with exit code " + exit + ": " + maskedCommand()
                        + System.lineSeparator() + String.join(System.lineSeparator(), stderrTail));
            }
            if (!stderrTail.isEmpty()) {
                log.debug("Command stderr: {}", String.join(" | ", stderrTail));
            }
            return copied;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private long copyStdout(Process process, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        long lastReport = 0;
        try (InputStream stdout = process.getInputStream()) {
            int read;
            while ((read = stdout.read(buffer)) != -1) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new IOException("Cancelled: " + maskedCommand());
                }
                out.write(buffer, 0, read);
                copied += read;
                if (byteReporter != null && copied - lastReport >= 1024L * 1024L) {
                    lastReport = copied;
                    byteReporter.accept(copied);
                }
            }
        }
        if (byteReporter != null && copied != lastReport) {
            byteReporter.accept(copied);
        }
        return copied;
    }

    private Thread drainStderr(Process process, Deque<String> tail) {
        Thread thread = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > STDERR_TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException e) {
                log.trace("stderr pump ended: {}", e.getMessage());
            }
        }, "backup-proc-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Thread feedStdin(Process process, InputStream source) {
        Thread thread = new Thread(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                source.transferTo(stdin);
            } catch (IOException e) {
                // The child exiting early closes the pipe; that surfaces as a non-zero exit code.
                log.debug("stdin pump ended early: {}", e.getMessage());
            }
        }, "backup-proc-stdin");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Command text with anything password-shaped replaced, safe to log. */
    private String maskedCommand() {
        List<String> masked = new ArrayList<>(command.size());
        for (String arg : command) {
            if (arg.startsWith("--password=") || arg.startsWith("-p") && arg.length() > 2) {
                masked.add(arg.substring(0, arg.indexOf('=') + 1) + "***");
            } else {
                masked.add(arg);
            }
        }
        return String.join(" ", masked);
    }

    /** True when {@code executable} can be launched, used to decide on a fallback path. */
    public static boolean isAvailable(String executable, String... probeArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.addAll(List.of(probeArgs));
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Writes a 0600 options file, the only way to hand a MySQL client a password without
     * exposing it in the process list.
     */
    public static Path writeSecureOptionsFile(Path dir, String fileName, String content) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        restrictToOwner(file);
        return file;
    }

    /** Best-effort owner-only permissions on both POSIX and Windows ACL filesystems. */
    public static void restrictToOwner(Path file) {
        try {
            var posixView = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (posixView != null) {
                posixView.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                return;
            }
            java.io.File f = file.toFile();
            // setReadable(false, false) clears the group/other bits before re-granting the owner.
            boolean ok = f.setReadable(false, false) && f.setWritable(false, false)
                    && f.setReadable(true, true) && f.setWritable(true, true);
            if (!ok) {
                log.debug("Could not fully restrict permissions on {}", file);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("Could not restrict permissions on {}: {}", file, e.getMessage());
        }
    }
}
