package com.backupmanager.db.mysql;

import com.backupmanager.core.api.BackupContext;
import com.backupmanager.core.model.BackupPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces a {@code mysqldump}-compatible SQL script using nothing but JDBC.
 *
 * <p>This is what makes the library usable on an end user's machine. A desktop application
 * installed from an MSI has no MySQL client tools, and asking customers to install them is
 * not a plan. The output is deliberately close to what {@code mysqldump} writes, so either
 * dump can be restored by either path.
 */
class MySqlJdbcDumper {

    private static final Logger log = LoggerFactory.getLogger(MySqlJdbcDumper.class);

    /** Keeps generated INSERT statements from exceeding a default {@code max_allowed_packet}. */
    private static final int MAX_STATEMENT_BYTES = 900_000;

    private final MySqlConfig config;

    MySqlJdbcDumper(MySqlConfig config) {
        this.config = config;
    }

    void dump(BackupContext ctx, PrintWriter out) throws SQLException, InterruptedException {
        try (Connection connection = config.openConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String serverVersion = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            ctx.putAttribute("serverVersion", serverVersion);
            ctx.putAttribute("dumpStrategy", "jdbc");

            writeHeader(out, serverVersion);

            List<String> tables = listTables(connection, "BASE TABLE");
            List<String> views = listTables(connection, "VIEW");
            ctx.putAttribute("tableCount", String.valueOf(tables.size()));
            ctx.putAttribute("viewCount", String.valueOf(views.size()));

            int index = 0;
            for (String table : tables) {
                ctx.checkCancelled();
                index++;
                ctx.report(BackupPhase.DUMPING,
                        "Exporting table " + table + " (" + index + " of " + tables.size() + ")",
                        index, tables.size());
                dumpTable(connection, out, table);
            }

            // Views come after tables because a view's definition references them.
            for (String view : views) {
                ctx.checkCancelled();
                dumpView(connection, out, view);
            }

            if (config.isIncludeTriggers() && !config.isDataOnly()) {
                dumpTriggers(connection, out);
            }
            if (config.isIncludeRoutines() && !config.isDataOnly()) {
                dumpRoutines(connection, out, "PROCEDURE");
                dumpRoutines(connection, out, "FUNCTION");
            }

            writeFooter(out);
            out.flush();
        }
    }

    private void writeHeader(PrintWriter out, String serverVersion) {
        out.println("-- Backup Manager SQL dump (JDBC strategy)");
        out.println("-- Database: " + config.getDatabase());
        out.println("-- Server:   " + serverVersion);
        out.println("-- Written:  " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()));
        out.println();
        // Saved and restored in the footer so a restore leaves the session as it found it.
        out.println("/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;");
        out.println("/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;");
        out.println("/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;");
        out.println("/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;");
        out.println("/*!40101 SET NAMES utf8mb4 */;");
        out.println("SET @OLD_AUTOCOMMIT=@@AUTOCOMMIT, AUTOCOMMIT=0;");
        out.println();
    }

    private void writeFooter(PrintWriter out) {
        out.println();
        out.println("COMMIT;");
        out.println("SET AUTOCOMMIT=@OLD_AUTOCOMMIT;");
        out.println("/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;");
        out.println("/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;");
        out.println("/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;");
        out.println("/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;");
        out.println();
        out.println("-- Dump complete");
    }

    private List<String> listTables(Connection connection, String type) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData()
                .getTables(connection.getCatalog(), null, "%", new String[]{type})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (config.acceptsTable(name)) {
                    names.add(name);
                }
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private void dumpTable(Connection connection, PrintWriter out, String table) throws SQLException {
        String quoted = SqlValueWriter.identifier(table);

        if (!config.isDataOnly()) {
            out.println();
            out.println("-- Structure for table " + quoted);
            if (config.isAddDropTable()) {
                out.println("DROP TABLE IF EXISTS " + quoted + ";");
            }
            out.println(showCreate(connection, "TABLE", quoted, 2) + ";");
        }

        if (config.isSchemaOnly()) {
            return;
        }

        out.println();
        out.println("-- Data for table " + quoted);
        out.println("LOCK TABLES " + quoted + " WRITE;");
        long rows = dumpRows(connection, out, table, quoted);
        out.println("UNLOCK TABLES;");
        log.debug("Exported {} rows from {}", rows, table);
    }

    private long dumpRows(Connection connection, PrintWriter out, String table, String quoted)
            throws SQLException {

        long rowCount = 0;
        try (Statement statement = createStreamingStatement(connection);
             ResultSet rs = statement.executeQuery("SELECT * FROM " + quoted)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();

            int[] types = new int[columns + 1];
            StringBuilder columnList = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                types[i] = meta.getColumnType(i);
                if (i > 1) {
                    columnList.append(", ");
                }
                columnList.append(SqlValueWriter.identifier(meta.getColumnName(i)));
            }

            String insertPrefix = "INSERT INTO " + quoted + " (" + columnList + ") VALUES ";
            StringBuilder batch = new StringBuilder();

            while (rs.next()) {
                StringBuilder row = new StringBuilder(64);
                row.append('(');
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append(',');
                    }
                    row.append(SqlValueWriter.literal(rs, i, types[i]));
                }
                row.append(')');

                // Flush before the batch could exceed max_allowed_packet on the server.
                if (batch.length() > 0
                        && batch.length() + row.length() + insertPrefix.length() > MAX_STATEMENT_BYTES) {
                    out.println(insertPrefix + batch + ";");
                    batch.setLength(0);
                }
                if (batch.length() > 0) {
                    batch.append(',');
                }
                batch.append(row);
                rowCount++;
            }
            if (batch.length() > 0) {
                out.println(insertPrefix + batch + ";");
            }
        }
        return rowCount;
    }

    /**
     * Creates a statement that streams rows instead of materialising the table.
     *
     * <p>Connector/J only streams when the fetch size is {@code Integer.MIN_VALUE}, which
     * other drivers reject outright — hence the attempt-and-fall-back. Without this a
     * multi-gigabyte table is read entirely into the heap.
     */
    private Statement createStreamingStatement(Connection connection) throws SQLException {
        Statement statement = connection.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            statement.setFetchSize(Integer.MIN_VALUE);
        } catch (SQLException e) {
            log.debug("Driver rejected streaming fetch size; using {} instead", config.getFetchSize());
            statement.setFetchSize(config.getFetchSize());
        }
        return statement;
    }

    private void dumpView(Connection connection, PrintWriter out, String view) throws SQLException {
        if (config.isDataOnly()) {
            return;
        }
        String quoted = SqlValueWriter.identifier(view);
        out.println();
        out.println("-- Definition for view " + quoted);
        out.println("DROP VIEW IF EXISTS " + quoted + ";");
        out.println(showCreate(connection, "VIEW", quoted, 2) + ";");
    }

    private void dumpTriggers(Connection connection, PrintWriter out) throws SQLException {
        List<String[]> triggers = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW TRIGGERS")) {
            while (rs.next()) {
                triggers.add(new String[]{rs.getString("Trigger"), rs.getString("Table")});
            }
        } catch (SQLException e) {
            log.warn("Could not list triggers: {}", e.getMessage());
            return;
        }

        for (String[] trigger : triggers) {
            if (!config.acceptsTable(trigger[1])) {
                continue;
            }
            String quoted = SqlValueWriter.identifier(trigger[0]);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SHOW CREATE TRIGGER " + quoted)) {
                if (rs.next()) {
                    out.println();
                    out.println("-- Trigger " + quoted);
                    out.println("DROP TRIGGER IF EXISTS " + quoted + ";");
                    out.println("DELIMITER ;;");
                    out.println(rs.getString("SQL Original Statement") + ";;");
                    out.println("DELIMITER ;");
                }
            } catch (SQLException e) {
                log.warn("Could not export trigger {}: {}", trigger[0], e.getMessage());
            }
        }
    }

    private void dumpRoutines(Connection connection, PrintWriter out, String kind) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SHOW " + kind + " STATUS WHERE Db = " + SqlValueWriter.quote(connection.getCatalog()))) {
            while (rs.next()) {
                names.add(rs.getString("Name"));
            }
        } catch (SQLException e) {
            log.warn("Could not list {}s: {}", kind.toLowerCase(java.util.Locale.ROOT), e.getMessage());
            return;
        }

        for (String name : names) {
            String quoted = SqlValueWriter.identifier(name);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SHOW CREATE " + kind + " " + quoted)) {
                if (rs.next()) {
                    String body = rs.getString("Create " + capitalise(kind));
                    if (body == null || body.isBlank()) {
                        continue;
                    }
                    out.println();
                    out.println("-- " + capitalise(kind) + " " + quoted);
                    out.println("DROP " + kind + " IF EXISTS " + quoted + ";");
                    // Routine bodies contain semicolons, so the client delimiter must change.
                    out.println("DELIMITER ;;");
                    out.println(body + ";;");
                    out.println("DELIMITER ;");
                }
            } catch (SQLException e) {
                log.warn("Could not export {} {}: {}", kind, name, e.getMessage());
            }
        }
    }

    private String showCreate(Connection connection, String kind, String quotedName, int column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW CREATE " + kind + " " + quotedName)) {
            if (rs.next()) {
                return rs.getString(column);
            }
        }
        throw new SQLException("SHOW CREATE " + kind + " " + quotedName + " returned nothing");
    }

    private static String capitalise(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
