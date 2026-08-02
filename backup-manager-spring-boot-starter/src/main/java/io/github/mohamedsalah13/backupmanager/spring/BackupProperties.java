package io.github.mohamedsalah13.backupmanager.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the {@code backup.*} section of {@code application.yml}.
 *
 * <pre>{@code
 * backup:
 *   enabled: true
 *   storage-root: ./backup-data
 *   jobs:
 *     main-db:
 *       source:
 *         type: mysql
 *         host: localhost
 *         database: sales
 *         username: backup_user
 *         password: ${DB_BACKUP_PASSWORD}
 *       destinations:
 *         - type: local
 *           path: D:/backups
 *         - type: s3
 *           bucket: company-backups
 *           region: eu-central-1
 *       schedule:
 *         cron: "0 0 2 * * *"
 *       compression: gzip
 *       encryption:
 *         password: ${BACKUP_ENCRYPTION_PASSWORD}
 *       retention:
 *         keep-daily: 7
 *         keep-weekly: 4
 *         keep-monthly: 12
 * }</pre>
 */
@ConfigurationProperties(prefix = "backup")
public class BackupProperties {

    /** Turns the whole subsystem off without removing the configuration. */
    private boolean enabled = true;

    /** Directory for the catalog and staging files. */
    private String storageRoot = "./backup-data";

    /** Overrides the staging directory, which defaults to {@code storageRoot/work}. */
    private String workDirectory;

    /** How many jobs may run at once. */
    private int parallelism = 2;

    /** Starts the scheduler when the application context is ready. */
    private boolean autoStart = true;

    /** Runs every enabled job once at startup. Off by default: startup is already busy. */
    private boolean runOnStartup;

    private final Map<String, JobProperties> jobs = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public String getWorkDirectory() {
        return workDirectory;
    }

    public void setWorkDirectory(String workDirectory) {
        this.workDirectory = workDirectory;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public boolean isRunOnStartup() {
        return runOnStartup;
    }

    public void setRunOnStartup(boolean runOnStartup) {
        this.runOnStartup = runOnStartup;
    }

    public Map<String, JobProperties> getJobs() {
        return jobs;
    }

    /** One entry under {@code backup.jobs}. */
    public static class JobProperties {

        /**
         * When false the job is not created at all — it cannot be scheduled or triggered,
         * and none of the optional modules it names have to be on the classpath. That is
         * what makes it safe to leave a fully written cloud job switched off until its
         * credentials exist.
         *
         * <p>For a job that should exist but never fire on its own, leave it enabled and
         * give it no schedule; it is then manual-only.
         */
        private boolean enabled = true;
        private String description;
        private SourceProperties source = new SourceProperties();
        private List<DestinationProperties> destinations = new ArrayList<>();
        private ScheduleProperties schedule = new ScheduleProperties();
        private RetentionProperties retention = new RetentionProperties();
        private EncryptionProperties encryption = new EncryptionProperties();

        /** {@code none}, {@code gzip}, {@code zip} or {@code bzip2}. */
        private String compression = "gzip";

        /** Folder inside each destination. Defaults to the job's name. */
        private String pathPrefix;

        /** Re-downloads each stored copy and compares checksums after uploading. */
        private boolean verifyAfterStore = true;

        /** Fails the run if any destination is unreachable, instead of marking it partial. */
        private boolean requireAllDestinations;

        private Duration timeout = Duration.ofHours(6);
        private int maxRetries = 2;
        private Duration retryDelay = Duration.ofSeconds(30);

        /** {@code FULL}, {@code INCREMENTAL} or {@code DIFFERENTIAL}. */
        private String type = "FULL";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public SourceProperties getSource() {
            return source;
        }

        public void setSource(SourceProperties source) {
            this.source = source;
        }

        public List<DestinationProperties> getDestinations() {
            return destinations;
        }

        public void setDestinations(List<DestinationProperties> destinations) {
            this.destinations = destinations;
        }

        public ScheduleProperties getSchedule() {
            return schedule;
        }

        public void setSchedule(ScheduleProperties schedule) {
            this.schedule = schedule;
        }

        public RetentionProperties getRetention() {
            return retention;
        }

        public void setRetention(RetentionProperties retention) {
            this.retention = retention;
        }

        public EncryptionProperties getEncryption() {
            return encryption;
        }

        public void setEncryption(EncryptionProperties encryption) {
            this.encryption = encryption;
        }

        public String getCompression() {
            return compression;
        }

        public void setCompression(String compression) {
            this.compression = compression;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public boolean isVerifyAfterStore() {
            return verifyAfterStore;
        }

        public void setVerifyAfterStore(boolean verifyAfterStore) {
            this.verifyAfterStore = verifyAfterStore;
        }

        public boolean isRequireAllDestinations() {
            return requireAllDestinations;
        }

        public void setRequireAllDestinations(boolean requireAllDestinations) {
            this.requireAllDestinations = requireAllDestinations;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /** What to back up. */
    public static class SourceProperties {

        /** {@code mysql}, {@code sqlite}, {@code h2}, {@code derby} or {@code files}. */
        private String type;

        // --- database sources ---
        private String host = "localhost";
        private Integer port;
        private String database;
        private String username;
        private String password;
        private String jdbcUrl;
        private boolean useSsl;

        /**
         * Reuses the application's own {@code DataSource} bean instead of opening new
         * connections. Only the JDBC dump strategy can work this way.
         */
        private boolean useApplicationDataSource;

        /** {@code AUTO}, {@code NATIVE_TOOL} or {@code JDBC}. MySQL only. */
        private String strategy = "AUTO";

        /** Full path to {@code mysqldump} when it is not on {@code PATH}. */
        private String mysqldumpPath;
        private String mysqlClientPath;

        private List<String> includeTables = new ArrayList<>();
        private List<String> excludeTables = new ArrayList<>();
        private boolean includeRoutines = true;
        private boolean includeTriggers = true;
        private boolean schemaOnly;

        // --- file source ---
        private List<String> paths = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();
        private String restoreTo;

        // --- file databases ---
        /** Path to the SQLite or Derby database. */
        private String file;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public boolean isUseSsl() {
            return useSsl;
        }

        public void setUseSsl(boolean useSsl) {
            this.useSsl = useSsl;
        }

        public boolean isUseApplicationDataSource() {
            return useApplicationDataSource;
        }

        public void setUseApplicationDataSource(boolean useApplicationDataSource) {
            this.useApplicationDataSource = useApplicationDataSource;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getMysqldumpPath() {
            return mysqldumpPath;
        }

        public void setMysqldumpPath(String mysqldumpPath) {
            this.mysqldumpPath = mysqldumpPath;
        }

        public String getMysqlClientPath() {
            return mysqlClientPath;
        }

        public void setMysqlClientPath(String mysqlClientPath) {
            this.mysqlClientPath = mysqlClientPath;
        }

        public List<String> getIncludeTables() {
            return includeTables;
        }

        public void setIncludeTables(List<String> includeTables) {
            this.includeTables = includeTables;
        }

        public List<String> getExcludeTables() {
            return excludeTables;
        }

        public void setExcludeTables(List<String> excludeTables) {
            this.excludeTables = excludeTables;
        }

        public boolean isIncludeRoutines() {
            return includeRoutines;
        }

        public void setIncludeRoutines(boolean includeRoutines) {
            this.includeRoutines = includeRoutines;
        }

        public boolean isIncludeTriggers() {
            return includeTriggers;
        }

        public void setIncludeTriggers(boolean includeTriggers) {
            this.includeTriggers = includeTriggers;
        }

        public boolean isSchemaOnly() {
            return schemaOnly;
        }

        public void setSchemaOnly(boolean schemaOnly) {
            this.schemaOnly = schemaOnly;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude;
        }

        public String getRestoreTo() {
            return restoreTo;
        }

        public void setRestoreTo(String restoreTo) {
            this.restoreTo = restoreTo;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }

    /** Where artifacts go. */
    public static class DestinationProperties {

        /** {@code local}, {@code s3}, {@code gdrive} or {@code dropbox}. */
        private String type = "local";

        /** Name recorded on every stored copy; defaults to the type. */
        private String name;

        // --- local ---
        /** Directory or UNC share, e.g. {@code \\\\nas\\backups}. */
        private String path;
        private boolean createIfMissing = true;

        // --- s3 ---
        private String bucket;
        private String region;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String keyPrefix;
        private String storageClass;
        private boolean createBucketIfMissing;

        // --- google drive ---
        private String serviceAccountKey;
        private String folderId;

        // --- dropbox ---
        private String accessToken;
        private String refreshToken;
        private String appKey;
        private String appSecret;
        private String rootPath;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isCreateIfMissing() {
            return createIfMissing;
        }

        public void setCreateIfMissing(boolean createIfMissing) {
            this.createIfMissing = createIfMissing;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public String getStorageClass() {
            return storageClass;
        }

        public void setStorageClass(String storageClass) {
            this.storageClass = storageClass;
        }

        public boolean isCreateBucketIfMissing() {
            return createBucketIfMissing;
        }

        public void setCreateBucketIfMissing(boolean createBucketIfMissing) {
            this.createBucketIfMissing = createBucketIfMissing;
        }

        public String getServiceAccountKey() {
            return serviceAccountKey;
        }

        public void setServiceAccountKey(String serviceAccountKey) {
            this.serviceAccountKey = serviceAccountKey;
        }

        public String getFolderId() {
            return folderId;
        }

        public void setFolderId(String folderId) {
            this.folderId = folderId;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }
    }

    /** When the job runs. Leave all fields unset for a manual job. */
    public static class ScheduleProperties {

        /** Six-field or five-field cron, or a macro such as {@code @daily}. */
        private String cron;

        /** Fixed interval, used when no cron is given. */
        private Duration fixedRate;

        /** Time zone for the cron expression; defaults to the system zone. */
        private String zone;

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public Duration getFixedRate() {
            return fixedRate;
        }

        public void setFixedRate(Duration fixedRate) {
            this.fixedRate = fixedRate;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    /** How long backups are kept. Rules combine as a union of what to keep. */
    public static class RetentionProperties {

        private int keepLast;
        private Integer maxAgeDays;
        private int keepDaily;
        private int keepWeekly;
        private int keepMonthly;
        private int keepYearly;

        /** Floor that no other rule can go below. */
        private int minimumKept = 1;

        public int getKeepLast() {
            return keepLast;
        }

        public void setKeepLast(int keepLast) {
            this.keepLast = keepLast;
        }

        public Integer getMaxAgeDays() {
            return maxAgeDays;
        }

        public void setMaxAgeDays(Integer maxAgeDays) {
            this.maxAgeDays = maxAgeDays;
        }

        public int getKeepDaily() {
            return keepDaily;
        }

        public void setKeepDaily(int keepDaily) {
            this.keepDaily = keepDaily;
        }

        public int getKeepWeekly() {
            return keepWeekly;
        }

        public void setKeepWeekly(int keepWeekly) {
            this.keepWeekly = keepWeekly;
        }

        public int getKeepMonthly() {
            return keepMonthly;
        }

        public void setKeepMonthly(int keepMonthly) {
            this.keepMonthly = keepMonthly;
        }

        public int getKeepYearly() {
            return keepYearly;
        }

        public void setKeepYearly(int keepYearly) {
            this.keepYearly = keepYearly;
        }

        public int getMinimumKept() {
            return minimumKept;
        }

        public void setMinimumKept(int minimumKept) {
            this.minimumKept = minimumKept;
        }
    }

    /** AES-256-GCM settings. Setting a password is what switches encryption on. */
    public static class EncryptionProperties {

        /**
         * Passphrase. Supply it through an environment variable or a secrets manager —
         * a password committed next to the backups protects nothing.
         */
        private String password;

        /** Base64-encoded 32-byte key, as an alternative to a password. */
        private String key;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public boolean isConfigured() {
            return (password != null && !password.isBlank()) || (key != null && !key.isBlank());
        }
    }
}
