package com.backupmanager.db.mysql;

import com.backupmanager.core.api.BackupContext;
import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupPhase;
import com.backupmanager.core.model.BackupType;
import com.backupmanager.core.util.FileNames;
import com.backupmanager.core.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Backs up and restores a MySQL or MariaDB database.
 *
 * <p>Two strategies are available. {@link Strategy#NATIVE_TOOL} shells out to
 * {@code mysqldump}, which is fastest and most faithful. {@link Strategy#JDBC} generates
 * an equivalent script over the JDBC connection, which needs nothing installed on the
 * machine — the case that matters for a desktop application shipped to customers.
 *
 * <p>{@link Strategy#AUTO}, the default, probes for {@code mysqldump} once and falls back
 * to JDBC when it is not there.
 */
public class MySqlBackupSource implements BackupSource {

    private static final Logger log = LoggerFactory.getLogger(MySqlBackupSource.class);

    public enum Strategy {
        /** Probe for {@code mysqldump}, fall back to JDBC. */
        AUTO,
        /** Require {@code mysqldump}; fail if it is missing. */
        NATIVE_TOOL,
        /** Always generate the dump over JDBC. */
        JDBC
    }

    private final MySqlConfig config;
    private final Strategy strategy;

    /** Cached probe result; {@code null} until the first check. */
    private volatile Boolean nativeToolAvailable;

    public MySqlBackupSource(MySqlConfig config) {
        this(config, Strategy.AUTO);
    }

    public MySqlBackupSource(MySqlConfig config, Strategy strategy) {
        this.config = config;
        this.strategy = strategy != null ? strategy : Strategy.AUTO;
    }

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public String describe() {
        return "MySQL " + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
    }

    @Override
    public String defaultExtension() {
        return "sql";
    }

    @Override
    public Set<BackupType> supportedTypes() {
        // A logical dump is always a complete snapshot; there is no delta format here.
        return EnumSet.of(BackupType.FULL);
    }

    @Override
    public void validate() {
        config.validate();
        if (strategy == Strategy.NATIVE_TOOL && !isNativeToolAvailable()) {
            throw new BackupException.Configuration(
                    "mysqldump was not found at '" + config.getMysqldumpPath()
                            + "'. Install the MySQL client tools, set the full path with"
                            + " setMysqldumpPath(...), or switch to Strategy.JDBC.");
        }
        // Prove the credentials work now rather than at 2am.
        try (Connection connection = config.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException e) {
            throw new BackupException.Configuration(
                    "Could not connect to " + describe() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long estimateSize() {
        // information_schema sizes are approximate but good enough for a progress bar.
        String sql = "SELECT SUM(data_length + index_length) FROM information_schema.TABLES"
                + " WHERE table_schema = ?";
        try (Connection connection = config.openConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, config.getDatabase());
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    long bytes = rs.getLong(1);
                    // A dump is text, so it runs larger than the on-disk size; assume ~1.3x.
                    return bytes > 0 ? (long) (bytes * 1.3) : -1L;
                }
            }
        } catch (SQLException e) {
            log.debug("Could not estimate the size of {}: {}", config.getDatabase(), e.getMessage());
        }
        return -1L;
    }

    @Override
    public void dump(BackupContext ctx, OutputStream out) throws Exception {
        boolean useNative = switch (strategy) {
            case NATIVE_TOOL -> true;
            case JDBC -> false;
            case AUTO -> config.canUseNativeTools() && isNativeToolAvailable();
        };

        if (useNative) {
            ctx.report(BackupPhase.DUMPING, "Running mysqldump");
            dumpWithNativeTool(ctx, out);
        } else {
            ctx.report(BackupPhase.DUMPING, "Exporting over JDBC");
            // The writer must not close the stream; the engine owns it.
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
            new MySqlJdbcDumper(config).dump(ctx, writer);
            writer.flush();
        }
    }

    private void dumpWithNativeTool(BackupContext ctx, OutputStream out) throws Exception {
        Path optionsFile = null;
        try {
            optionsFile = writeOptionsFile(ctx.getWorkDir());

            List<String> command = new ArrayList<>();
            command.add(config.getMysqldumpPath());
            // Must come first: mysqldump rejects it anywhere else.
            command.add("--defaults-extra-file=" + optionsFile.toAbsolutePath());
            command.add("--host=" + config.getHost());
            command.add("--port=" + config.getPort());
            // Consistent snapshot on InnoDB without locking writers out of the application.
            command.add("--single-transaction");
            command.add("--quick");
            command.add("--hex-blob");
            command.add("--default-character-set=utf8mb4");
            command.add("--set-gtid-purged=OFF");

            if (config.isAddDropTable()) {
                command.add("--add-drop-table");
            }
            if (config.isIncludeRoutines()) {
                command.add("--routines");
            }
            if (config.isIncludeTriggers()) {
                command.add("--triggers");
            } else {
                command.add("--skip-triggers");
            }
            if (config.isIncludeEvents()) {
                command.add("--events");
            }
            if (config.isSchemaOnly()) {
                command.add("--no-data");
            }
            if (config.isDataOnly()) {
                command.add("--no-create-info");
            }
            command.addAll(config.getExtraDumpArgs());

            command.add(config.getDatabase());
            for (String table : config.getIncludeTables()) {
                command.add(table);
            }
            for (String table : config.getExcludeTables()) {
                command.add("--ignore-table=" + config.getDatabase() + "." + table);
            }

            ctx.putAttribute("dumpStrategy", "mysqldump");
            long bytes = ProcessRunner.of(command)
                    .cancelWhen(ctx::isCancelled)
                    .onBytes(written -> ctx.report(BackupPhase.DUMPING,
                            "Dumped " + FileNames.humanSize(written), written, estimateSize()))
                    .pipeTo(out);
            ctx.putAttribute("dumpBytes", String.valueOf(bytes));

        } finally {
            FileNames.deleteQuietly(optionsFile);
        }
    }

    @Override
    public void restore(RestoreContext ctx, InputStream in) throws Exception {
        String targetDatabase = ctx.getTargetOverride() != null && !ctx.getTargetOverride().isBlank()
                ? ctx.getTargetOverride()
                : config.getDatabase();

        boolean useNative = switch (strategy) {
            case NATIVE_TOOL -> true;
            case JDBC -> false;
            case AUTO -> config.canUseNativeTools() && isNativeToolAvailable(config.getMysqlClientPath());
        };

        if (ctx.isDryRun()) {
            // Read the script through without executing it, proving it decodes end to end.
            long lines = countLines(in);
            log.info("Dry run: the dump decoded cleanly, {} lines", lines);
            return;
        }

        if (useNative) {
            ctx.report(BackupPhase.PREPARING, "Restoring with the mysql client");
            restoreWithNativeTool(ctx, in, targetDatabase);
        } else {
            ctx.report(BackupPhase.PREPARING, "Restoring over JDBC");
            restoreWithJdbc(ctx, in, targetDatabase);
        }
    }

    private void restoreWithNativeTool(RestoreContext ctx, InputStream in, String targetDatabase)
            throws Exception {
        Path optionsFile = null;
        try {
            optionsFile = writeOptionsFile(ctx.getWorkDir());
            List<String> command = new ArrayList<>();
            command.add(config.getMysqlClientPath());
            command.add("--defaults-extra-file=" + optionsFile.toAbsolutePath());
            command.add("--host=" + config.getHost());
            command.add("--port=" + config.getPort());
            command.add("--default-character-set=utf8mb4");
            if (!ctx.isContinueOnError()) {
                // Stop at the first error rather than leaving a half-restored database.
                command.add("--abort-source-on-error");
            } else {
                command.add("--force");
            }
            command.add(targetDatabase);

            ProcessRunner.of(command).pipeFrom(in);
        } finally {
            FileNames.deleteQuietly(optionsFile);
        }
    }

    private void restoreWithJdbc(RestoreContext ctx, InputStream in, String targetDatabase)
            throws Exception {
        try (Connection connection = config.openConnection()) {
            if (!targetDatabase.equals(connection.getCatalog())) {
                connection.setCatalog(targetDatabase);
            }
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
                SqlScriptRunner runner = new SqlScriptRunner(connection, ctx.isContinueOnError());
                runner.run(reader, ctx);
                log.info("Restore executed {} statements ({} failed and were ignored)",
                        runner.getExecuted(), runner.getFailed());
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    /**
     * Writes the credentials to a {@code [client]} options file.
     *
     * <p>{@code --password=} on the command line is readable by every user on the machine
     * through the process list, so the password never appears as an argument.
     */
    private Path writeOptionsFile(Path workDir) throws IOException {
        Path dir = workDir != null ? workDir : Files.createTempDirectory("backup-mysql");
        StringBuilder content = new StringBuilder("[client]\n");
        if (config.getUsername() != null) {
            content.append("user=").append(config.getUsername()).append('\n');
        }
        if (config.getPassword() != null) {
            content.append("password=").append(config.getPassword()).append('\n');
        }
        if (config.isUseSsl()) {
            content.append("ssl-mode=REQUIRED\n");
        }
        return ProcessRunner.writeSecureOptionsFile(
                dir, "backup-mysql-" + System.nanoTime() + ".cnf", content.toString());
    }

    private boolean isNativeToolAvailable() {
        Boolean cached = nativeToolAvailable;
        if (cached == null) {
            cached = isNativeToolAvailable(config.getMysqldumpPath());
            nativeToolAvailable = cached;
            log.info("mysqldump {} at '{}'", cached ? "found" : "not found", config.getMysqldumpPath());
        }
        return cached;
    }

    private static boolean isNativeToolAvailable(String executable) {
        return ProcessRunner.isAvailable(executable, "--version");
    }

    private static long countLines(InputStream in) throws IOException {
        long lines = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            while (reader.readLine() != null) {
                lines++;
            }
        }
        return lines;
    }

    public MySqlConfig getConfig() {
        return config;
    }

    public Strategy getStrategy() {
        return strategy;
    }
}
