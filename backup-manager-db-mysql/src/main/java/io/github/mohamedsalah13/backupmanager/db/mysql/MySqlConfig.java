package io.github.mohamedsalah13.backupmanager.db.mysql;

import io.github.mohamedsalah13.backupmanager.core.model.BackupException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connection and tool settings for a MySQL or MariaDB backup.
 *
 * <p>Can be built from explicit values, from a JDBC URL, or from an existing
 * {@link DataSource}. The {@code DataSource} route only enables the JDBC strategy —
 * {@code mysqldump} needs a host and credentials it can pass on its own.
 */
public class MySqlConfig {

    /** Recognises the host, port and database in a standard MySQL or MariaDB JDBC URL. */
    private static final Pattern JDBC_URL = Pattern.compile(
            "jdbc:(?:mysql|mariadb)://([^:/?]+)(?::(\\d+))?/([^?]+)(?:\\?(.*))?");

    private String host = "localhost";
    private int port = 3306;
    private String database;
    private String username;
    private String password;
    private boolean useSsl;

    private DataSource dataSource;
    private String jdbcUrl;

    /** Path to {@code mysqldump}; resolved from {@code PATH} when left null. */
    private String mysqldumpPath = "mysqldump";
    /** Path to the {@code mysql} client, used for restores. */
    private String mysqlClientPath = "mysql";

    private final List<String> extraDumpArgs = new ArrayList<>();
    private final Set<String> includeTables = new LinkedHashSet<>();
    private final Set<String> excludeTables = new LinkedHashSet<>();

    private boolean includeRoutines = true;
    private boolean includeTriggers = true;
    private boolean includeEvents;
    private boolean schemaOnly;
    private boolean dataOnly;
    private boolean addDropTable = true;
    private int fetchSize = 1000;

    public static MySqlConfig of(String host, int port, String database, String username, String password) {
        MySqlConfig c = new MySqlConfig();
        c.host = host;
        c.port = port;
        c.database = database;
        c.username = username;
        c.password = password;
        return c;
    }

    /**
     * Parses host, port and database out of a JDBC URL. Credentials are not read from the
     * URL's query string: set them explicitly so they never end up in a log line.
     */
    public static MySqlConfig fromJdbcUrl(String url, String username, String password) {
        Matcher m = JDBC_URL.matcher(url);
        if (!m.matches()) {
            throw new BackupException.Configuration(
                    "Could not parse the MySQL JDBC URL: " + url
                            + " (expected jdbc:mysql://host:port/database)");
        }
        MySqlConfig c = new MySqlConfig();
        c.host = m.group(1);
        c.port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 3306;
        c.database = m.group(3);
        c.username = username;
        c.password = password;
        c.jdbcUrl = url;
        return c;
    }

    /**
     * Reuses the application's pool. Convenient, but note that only the JDBC strategy can
     * work from it — a {@code DataSource} exposes no credentials to hand to a command-line
     * tool. Set the host and credentials too if you want {@code mysqldump} as an option.
     */
    public static MySqlConfig fromDataSource(DataSource dataSource, String database) {
        MySqlConfig c = new MySqlConfig();
        c.dataSource = dataSource;
        c.database = database;
        return c;
    }

    /** Opens a connection, preferring the pool when one was supplied. */
    public Connection openConnection() throws SQLException {
        if (dataSource != null) {
            return dataSource.getConnection();
        }
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("useSSL", String.valueOf(useSsl));
        props.setProperty("allowPublicKeyRetrieval", String.valueOf(!useSsl));
        // Required for streaming reads; without it the driver buffers whole tables in memory.
        props.setProperty("useCursorFetch", "true");
        return DriverManager.getConnection(effectiveJdbcUrl(), props);
    }

    public String effectiveJdbcUrl() {
        if (jdbcUrl != null) {
            return jdbcUrl;
        }
        if (database == null) {
            throw new BackupException.Configuration("MySQL backup needs a database name");
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /** True when there is enough information to launch {@code mysqldump}. */
    public boolean canUseNativeTools() {
        return host != null && database != null && username != null;
    }

    public String getHost() {
        return host;
    }

    public MySqlConfig setHost(String host) {
        this.host = host;
        return this;
    }

    public int getPort() {
        return port;
    }

    public MySqlConfig setPort(int port) {
        this.port = port;
        return this;
    }

    public String getDatabase() {
        return database;
    }

    public MySqlConfig setDatabase(String database) {
        this.database = database;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public MySqlConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public MySqlConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public MySqlConfig setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
        return this;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public MySqlConfig setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public MySqlConfig setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    public String getMysqldumpPath() {
        return mysqldumpPath;
    }

    /** Absolute path to {@code mysqldump}, for installations that are not on {@code PATH}. */
    public MySqlConfig setMysqldumpPath(String mysqldumpPath) {
        this.mysqldumpPath = mysqldumpPath;
        return this;
    }

    public String getMysqlClientPath() {
        return mysqlClientPath;
    }

    public MySqlConfig setMysqlClientPath(String mysqlClientPath) {
        this.mysqlClientPath = mysqlClientPath;
        return this;
    }

    public List<String> getExtraDumpArgs() {
        return extraDumpArgs;
    }

    /** Extra {@code mysqldump} flags, appended verbatim. Ignored by the JDBC strategy. */
    public MySqlConfig addDumpArg(String arg) {
        this.extraDumpArgs.add(arg);
        return this;
    }

    public Set<String> getIncludeTables() {
        return includeTables;
    }

    /** Restricts the backup to these tables. Empty means every table. */
    public MySqlConfig includeTable(String... tables) {
        this.includeTables.addAll(List.of(tables));
        return this;
    }

    public Set<String> getExcludeTables() {
        return excludeTables;
    }

    /** Skips these tables — audit logs, caches, anything reproducible. */
    public MySqlConfig excludeTable(String... tables) {
        this.excludeTables.addAll(List.of(tables));
        return this;
    }

    public boolean isIncludeRoutines() {
        return includeRoutines;
    }

    public MySqlConfig setIncludeRoutines(boolean includeRoutines) {
        this.includeRoutines = includeRoutines;
        return this;
    }

    public boolean isIncludeTriggers() {
        return includeTriggers;
    }

    public MySqlConfig setIncludeTriggers(boolean includeTriggers) {
        this.includeTriggers = includeTriggers;
        return this;
    }

    public boolean isIncludeEvents() {
        return includeEvents;
    }

    public MySqlConfig setIncludeEvents(boolean includeEvents) {
        this.includeEvents = includeEvents;
        return this;
    }

    public boolean isSchemaOnly() {
        return schemaOnly;
    }

    public MySqlConfig setSchemaOnly(boolean schemaOnly) {
        this.schemaOnly = schemaOnly;
        return this;
    }

    public boolean isDataOnly() {
        return dataOnly;
    }

    public MySqlConfig setDataOnly(boolean dataOnly) {
        this.dataOnly = dataOnly;
        return this;
    }

    public boolean isAddDropTable() {
        return addDropTable;
    }

    /** Emits {@code DROP TABLE IF EXISTS} before each {@code CREATE}. */
    public MySqlConfig setAddDropTable(boolean addDropTable) {
        this.addDropTable = addDropTable;
        return this;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public MySqlConfig setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
        return this;
    }

    /** Whether a table survives the include and exclude filters. */
    public boolean acceptsTable(String tableName) {
        if (!includeTables.isEmpty() && !includeTables.contains(tableName)) {
            return false;
        }
        return !excludeTables.contains(tableName);
    }

    public void validate() {
        if (database == null || database.isBlank()) {
            throw new BackupException.Configuration("MySQL backup needs a database name");
        }
        if (dataSource == null && (username == null || username.isBlank())) {
            throw new BackupException.Configuration(
                    "MySQL backup needs a username, or a DataSource to borrow connections from");
        }
        if (schemaOnly && dataOnly) {
            throw new BackupException.Configuration(
                    "schemaOnly and dataOnly are mutually exclusive");
        }
    }

    @Override
    public String toString() {
        return "MySqlConfig[" + host + ":" + port + "/" + database + " as " + username + "]";
    }
}
