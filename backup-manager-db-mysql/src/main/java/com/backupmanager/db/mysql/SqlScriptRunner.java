package com.backupmanager.db.mysql;

import com.backupmanager.core.api.RestoreContext;
import com.backupmanager.core.model.BackupPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Executes a SQL dump statement by statement.
 *
 * <p>Splitting SQL is not a matter of cutting on semicolons: they appear inside string
 * literals, inside comments, and legitimately inside stored routine bodies, which is why
 * dumps carry {@code DELIMITER} directives. This scanner tracks quoting and comment state
 * so those cases survive a restore intact.
 */
class SqlScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlScriptRunner.class);

    private final Connection connection;
    private final boolean continueOnError;

    private String delimiter = ";";
    private long executed;
    private long failed;

    SqlScriptRunner(Connection connection, boolean continueOnError) {
        this.connection = connection;
        this.continueOnError = continueOnError;
    }

    long getExecuted() {
        return executed;
    }

    long getFailed() {
        return failed;
    }

    void run(BufferedReader reader, RestoreContext ctx) throws IOException, SQLException, InterruptedException {
        StringBuilder statement = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            ctx.checkCancelled();
            String trimmed = line.trim();

            // A DELIMITER directive is a client instruction, never sent to the server.
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                flush(statement, ctx);
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (isIgnorableLine(trimmed, statement.length() == 0)) {
                continue;
            }

            statement.append(line).append('\n');

            // Only split when the delimiter is at the end of a statement and not inside a
            // quoted section, which the scan below determines.
            int end = findDelimiterEnd(statement);
            while (end >= 0) {
                String sql = statement.substring(0, end).trim();
                statement.delete(0, end + delimiter.length());
                execute(sql, ctx);
                end = findDelimiterEnd(statement);
            }
        }
        flush(statement, ctx);
    }

    private void flush(StringBuilder statement, RestoreContext ctx) throws SQLException {
        String remaining = statement.toString().trim();
        statement.setLength(0);
        if (!remaining.isEmpty()) {
            execute(remaining, ctx);
        }
    }

    private static boolean isIgnorableLine(String trimmed, boolean atStatementStart) {
        if (!atStatementStart) {
            // Mid-statement lines are kept verbatim; a "--" there may be inside a literal.
            return false;
        }
        if (trimmed.isEmpty()) {
            return true;
        }
        // "--" needs the trailing space or end of line to count as a comment in MySQL.
        if (trimmed.equals("--") || trimmed.startsWith("-- ")) {
            return true;
        }
        return trimmed.startsWith("#");
    }

    /**
     * Index of the delimiter that terminates the statement, or {@code -1} when the buffer
     * does not yet hold a complete one. Quotes, escapes and comments are tracked so a
     * delimiter inside any of them is ignored.
     */
    private int findDelimiterEnd(CharSequence sql) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }

            // The delimiter is checked before the quote openers, so a one-character
            // delimiter is still recognised whatever character it happens to be.
            if (matchesDelimiterAt(sql, i)) {
                return i;
            }

            switch (c) {
                case '\'' -> inSingle = true;
                case '"' -> inDouble = true;
                case '`' -> inBacktick = true;
                case '#' -> inLineComment = true;
                case '-' -> {
                    if (next == '-') {
                        inLineComment = true;
                        i++;
                    }
                }
                case '/' -> {
                    // "/*!40101 ..." is an executable comment MySQL runs, so it is left in place.
                    if (next == '*' && (i + 2 >= sql.length() || sql.charAt(i + 2) != '!')) {
                        inBlockComment = true;
                        i++;
                    }
                }
                default -> {
                    // ordinary character
                }
            }
        }
        return -1;
    }

    private boolean matchesDelimiterAt(CharSequence sql, int index) {
        if (index + delimiter.length() > sql.length()) {
            return false;
        }
        for (int i = 0; i < delimiter.length(); i++) {
            if (sql.charAt(index + i) != delimiter.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void execute(String sql, RestoreContext ctx) throws SQLException {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (ctx.isDryRun()) {
            executed++;
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(trimmed);
            executed++;
            if (executed % 500 == 0) {
                ctx.report(BackupPhase.PREPARING, "Executed " + executed + " statements");
            }
        } catch (SQLException e) {
            failed++;
            if (!continueOnError) {
                throw new SQLException("Statement failed during restore: " + abbreviate(trimmed)
                        + " -> " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
            }
            log.warn("Ignoring failed statement ({}): {}", e.getMessage(), abbreviate(trimmed));
        }
    }

    private static String abbreviate(String sql) {
        String single = sql.replaceAll("\\s+", " ");
        return single.length() <= 200 ? single : single.substring(0, 200) + "…";
    }
}
