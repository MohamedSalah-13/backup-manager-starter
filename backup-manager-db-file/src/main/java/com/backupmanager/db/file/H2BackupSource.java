package com.backupmanager.db.file;

import com.backupmanager.core.api.BackupContext;
import com.backupmanager.core.api.BackupSource;
import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupPhase;
import com.backupmanager.core.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Backs up an H2 database with the engine's own {@code SCRIPT} command.
 *
 * <p>H2 offers two routes: {@code BACKUP TO} writes a zip of the database files, and
 * {@code SCRIPT TO} writes portable SQL. SQL is the default here because it survives an H2
 * version upgrade — a binary backup taken on 2.1 will not open on 2.3, which is a
 * miserable thing to discover during a recovery.
 */
public class H2BackupSource implements BackupSource {

    private static final Logger log = LoggerFactory.getLogger(H2BackupSource.class);

    public enum Format {
        /** Portable SQL script. Survives engine upgrades. */
        SQL_SCRIPT,
        /** Zipped database files. Faster, but tied to the H2 version that wrote it. */
        NATIVE_ZIP
    }

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Format format;

    public H2BackupSource(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, Format.SQL_SCRIPT);
    }

    public H2BackupSource(String jdbcUrl, String username, String password, Format format) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.format = format != null ? format : Format.SQL_SCRIPT;
    }

    /** Convenience for a file-based database at {@code path}. */
    public static H2BackupSource ofFile(Path databaseFile, String username, String password) {
        String base = databaseFile.toAbsolutePath().toString().replaceAll("\\.mv\\.db$", "");
        return new H2BackupSource("jdbc:h2:file:" + base, username, password);
    }

    @Override
    public String type() {
        return "h2";
    }

    @Override
    public String describe() {
        return "H2 " + jdbcUrl;
    }

    @Override
    public String defaultExtension() {
        return format == Format.SQL_SCRIPT ? "sql" : "zip";
    }

    @Override
    public void validate() {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException e) {
            throw new BackupException.Configuration(
                    "Could not connect to the H2 database at " + jdbcUrl + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void dump(BackupContext ctx, OutputStream out) throws Exception {
        Path temp = ctx.getWorkDir().resolve(
                "h2-dump-" + System.nanoTime() + (format == Format.SQL_SCRIPT ? ".sql" : ".zip"));
        try {
            ctx.report(BackupPhase.DUMPING, "Exporting H2 database");
            try (Connection connection = open();
                 Statement statement = connection.createStatement()) {
                String escaped = temp.toString().replace("'", "''");
                if (format == Format.SQL_SCRIPT) {
                    // DROP re-creates cleanly on restore; the script stays plain text.
                    statement.execute("SCRIPT DROP TO '" + escaped + "'");
                } else {
                    statement.execute("BACKUP TO '" + escaped + "'");
                }
            }

            long size = Files.size(temp);
            ctx.putAttribute("h2Format", format.name());
            ctx.putAttribute("h2Bytes", String.valueOf(size));

            try (InputStream in = Files.newInputStream(temp)) {
                in.transferTo(out);
            }
        } finally {
            FileNames.deleteQuietly(temp);
        }
    }

    @Override
    public void restore(RestoreContext ctx, InputStream in) throws Exception {
        if (format == Format.NATIVE_ZIP) {
            throw new BackupException.Configuration(
                    "Restoring an H2 NATIVE_ZIP backup means replacing the database files while"
                            + " the engine is shut down. Stop the application, unzip the artifact"
                            + " over the data directory, and start again. Use Format.SQL_SCRIPT"
                            + " for restores this library can perform online.");
        }

        Path script = ctx.getWorkDir().resolve("h2-restore-" + System.nanoTime() + ".sql");
        try {
            try (OutputStream out = Files.newOutputStream(script)) {
                in.transferTo(out);
            }

            if (ctx.isDryRun()) {
                long lines;
                try (BufferedReader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
                    lines = reader.lines().count();
                }
                log.info("Dry run: the H2 script decoded cleanly, {} lines", lines);
                return;
            }

            try (Connection connection = open();
                 Statement statement = connection.createStatement()) {
                if (ctx.isReplaceExisting()) {
                    // The script was written with DROP, but an object it does not know
                    // about would otherwise survive and collide.
                    statement.execute("DROP ALL OBJECTS");
                }
                String escaped = script.toString().replace("'", "''");
                statement.execute("RUNSCRIPT FROM '" + escaped + "'");
            }
            log.info("Restored the H2 database at {}", jdbcUrl);
        } finally {
            FileNames.deleteQuietly(script);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public long estimateSize() {
        return -1L;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public Format getFormat() {
        return format;
    }
}
