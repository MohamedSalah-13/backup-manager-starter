package io.github.mohamedsalah13.backupmanager.spring;

import io.github.mohamedsalah13.backupmanager.core.api.BackupSource;
import io.github.mohamedsalah13.backupmanager.core.api.Compressor;
import io.github.mohamedsalah13.backupmanager.core.api.Encryptor;
import io.github.mohamedsalah13.backupmanager.core.api.RetentionPolicy;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.compress.Compressors;
import io.github.mohamedsalah13.backupmanager.core.crypto.AesGcmEncryptor;
import io.github.mohamedsalah13.backupmanager.core.crypto.NoEncryption;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import io.github.mohamedsalah13.backupmanager.core.model.BackupType;
import io.github.mohamedsalah13.backupmanager.core.model.RetentionRule;
import io.github.mohamedsalah13.backupmanager.core.model.Schedule;
import io.github.mohamedsalah13.backupmanager.core.retention.RetentionPolicies;
import io.github.mohamedsalah13.backupmanager.core.source.FileSystemBackupSource;
import io.github.mohamedsalah13.backupmanager.core.storage.LocalStorageProvider;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Turns {@link BackupProperties} into the objects the engine works with.
 *
 * <p><strong>This class must never name a type from an optional module in its own code.</strong>
 * Spring introspects every bean class with {@code getDeclaredMethods()}, and the JVM
 * resolves the types a class references while doing so — which happens long before any
 * guard inside a method body could run. A single direct reference to, say, {@code MySqlConfig}
 * therefore stops the whole application context from starting whenever
 * {@code backup-manager-db-mysql} is absent, even for an application that only backs up files.
 *
 * <p>Each optional integration consequently lives in its own nested class. Nested classes are
 * separate class files loaded on first active use, so {@link MySqlSupport} is only resolved
 * once a job actually asks for MySQL — by which point {@link #requireModule} has already run
 * and produced a message naming the dependency to add.
 */
public class BackupJobFactory {

    private final DataSource applicationDataSource;

    public BackupJobFactory(DataSource applicationDataSource) {
        this.applicationDataSource = applicationDataSource;
    }

    public BackupJob create(String jobName, BackupProperties.JobProperties props) {
        BackupJob.Builder builder = BackupJob.builder(jobName)
                .description(props.getDescription())
                .source(createSource(jobName, props.getSource()))
                .compressor(createCompressor(props.getCompression()))
                .encryptor(createEncryptor(props.getEncryption()))
                .schedule(createSchedule(props.getSchedule()))
                .retention(createRetention(props.getRetention()))
                .defaultType(parseType(props.getType()))
                .verifyAfterStore(props.isVerifyAfterStore())
                .requireAllDestinations(props.isRequireAllDestinations())
                .enabled(props.isEnabled())
                .timeout(props.getTimeout())
                .maxRetries(props.getMaxRetries())
                .retryDelay(props.getRetryDelay());

        if (props.getPathPrefix() != null) {
            builder.pathPrefix(props.getPathPrefix());
        }
        if (props.getDestinations().isEmpty()) {
            throw new BackupException.Configuration(
                    "Job '" + jobName + "' has no destinations. Add at least one under"
                            + " backup.jobs." + jobName + ".destinations");
        }
        for (BackupProperties.DestinationProperties destination : props.getDestinations()) {
            builder.destination(createDestination(jobName, destination));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ source

    private BackupSource createSource(String jobName, BackupProperties.SourceProperties props) {
        String type = require(props.getType(), jobName, "source.type");

        return switch (type.toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.db.mysql.MySqlBackupSource",
                        jobName, "mysql", "backup-manager-db-mysql");
                yield MySqlSupport.create(jobName, props, applicationDataSource);
            }
            case "sqlite" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.db.file.SqliteBackupSource",
                        jobName, "sqlite", "backup-manager-db-file");
                yield FileDatabaseSupport.sqlite(jobName, props);
            }
            case "h2" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.db.file.H2BackupSource",
                        jobName, "h2", "backup-manager-db-file");
                yield FileDatabaseSupport.h2(jobName, props);
            }
            case "derby" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.db.file.DerbyBackupSource",
                        jobName, "derby", "backup-manager-db-file");
                yield FileDatabaseSupport.derby(jobName, props);
            }
            // Files live in the core, so no guard is needed.
            case "files", "filesystem" -> createFileSource(jobName, props);
            default -> throw new BackupException.Configuration(
                    "Unknown source type '" + type + "' for job '" + jobName
                            + "'. Supported: mysql, sqlite, h2, derby, files");
        };
    }

    private BackupSource createFileSource(String jobName, BackupProperties.SourceProperties props) {
        if (props.getPaths().isEmpty()) {
            throw new BackupException.Configuration(
                    "Job '" + jobName + "' has source.type=files but no source.paths");
        }
        var source = new FileSystemBackupSource();
        props.getPaths().forEach(p -> source.addRoot(Path.of(p)));
        props.getExclude().forEach(source::exclude);
        if (props.getRestoreTo() != null) {
            source.restoreTo(Path.of(props.getRestoreTo()));
        }
        return source;
    }

    // ------------------------------------------------------------- destination

    private StorageProvider createDestination(String jobName,
                                              BackupProperties.DestinationProperties props) {
        String type = props.getType() != null ? props.getType() : "local";
        String name = props.getName() != null ? props.getName() : type;

        return switch (type.toLowerCase(Locale.ROOT)) {
            case "local", "file", "share" -> new LocalStorageProvider(
                    name,
                    Path.of(require(props.getPath(), jobName, "destination.path")),
                    props.isCreateIfMissing());
            case "s3" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.storage.s3.S3StorageProvider",
                        jobName, "s3", "backup-manager-storage-s3");
                yield S3Support.create(jobName, name, props);
            }
            case "gdrive", "googledrive", "google-drive" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.storage.gdrive.GoogleDriveStorageProvider",
                        jobName, "gdrive", "backup-manager-storage-gdrive");
                yield GoogleDriveSupport.create(name, props);
            }
            case "dropbox" -> {
                requireModule("io.github.mohamedsalah13.backupmanager.storage.dropbox.DropboxStorageProvider",
                        jobName, "dropbox", "backup-manager-storage-dropbox");
                yield DropboxSupport.create(name, props);
            }
            default -> throw new BackupException.Configuration(
                    "Unknown destination type '" + type + "' for job '" + jobName
                            + "'. Supported: local, s3, gdrive, dropbox");
        };
    }

    // --------------------------------------------------------------- pipeline

    private Compressor createCompressor(String id) {
        return Compressors.byId(id);
    }

    private Encryptor createEncryptor(BackupProperties.EncryptionProperties props) {
        if (props == null || !props.isConfigured()) {
            return NoEncryption.INSTANCE;
        }
        if (props.getKey() != null && !props.getKey().isBlank()) {
            byte[] key;
            try {
                key = Base64.getDecoder().decode(props.getKey().trim());
            } catch (IllegalArgumentException e) {
                throw new BackupException.Configuration(
                        "backup encryption key must be valid Base64", e);
            }
            return AesGcmEncryptor.withKey(key);
        }
        return AesGcmEncryptor.withPassword(props.getPassword());
    }

    private Schedule createSchedule(BackupProperties.ScheduleProperties props) {
        if (props == null) {
            return Schedule.manual();
        }
        if (props.getCron() != null && !props.getCron().isBlank()) {
            ZoneId zone = props.getZone() != null ? ZoneId.of(props.getZone()) : ZoneId.systemDefault();
            return Schedule.cron(props.getCron(), zone);
        }
        if (props.getFixedRate() != null && !props.getFixedRate().isZero()) {
            return Schedule.every(props.getFixedRate());
        }
        return Schedule.manual();
    }

    private RetentionPolicy createRetention(BackupProperties.RetentionProperties props) {
        if (props == null) {
            return RetentionPolicy.KEEP_ALL;
        }
        RetentionRule rule = new RetentionRule()
                .setKeepLast(props.getKeepLast())
                .setKeepDaily(props.getKeepDaily())
                .setKeepWeekly(props.getKeepWeekly())
                .setKeepMonthly(props.getKeepMonthly())
                .setKeepYearly(props.getKeepYearly())
                .setMinimumKept(props.getMinimumKept());
        if (props.getMaxAgeDays() != null) {
            rule.setMaxAge(Duration.ofDays(props.getMaxAgeDays()));
        }
        return RetentionPolicies.of(rule);
    }

    private BackupType parseType(String type) {
        if (type == null || type.isBlank()) {
            return BackupType.FULL;
        }
        try {
            return BackupType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BackupException.Configuration(
                    "Unknown backup type '" + type + "'. Supported: " + List.of(BackupType.values()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String require(String value, String jobName, String property) {
        if (value == null || value.isBlank()) {
            throw new BackupException.Configuration(
                    "backup.jobs." + jobName + "." + property + " is required but was not set");
        }
        return value;
    }

    /**
     * Fails with a message naming the dependency to add, instead of letting a bare
     * {@link NoClassDefFoundError} surface from inside a nested support class.
     */
    private static void requireModule(String className, String jobName,
                                      String feature, String artifactId) {
        // The context class loader, not this class's own: in a container the application's
        // classpath and the library's need not be the same, and the application's is the
        // one that decides whether the optional module is really available.
        if (!ClassUtils.isPresent(className, ClassUtils.getDefaultClassLoader())) {
            throw new BackupException.Configuration(
                    "Job '" + jobName + "' uses '" + feature + "', which needs the optional module '"
                            + artifactId + "'. Add it to your dependencies:\n"
                            + "  <dependency>\n"
                            + "    <groupId>io.github.mohamedsalah13</groupId>\n"
                            + "    <artifactId>" + artifactId + "</artifactId>\n"
                            + "  </dependency>");
        }
    }

    // ------------------------------------------------------------------------
    // Optional-module support. Each nested class is a separate class file and is
    // loaded only when its branch above actually executes.
    // ------------------------------------------------------------------------

    /** Requires {@code backup-manager-db-mysql}. */
    static final class MySqlSupport {

        private MySqlSupport() {
        }

        static BackupSource create(String jobName, BackupProperties.SourceProperties props,
                                   DataSource applicationDataSource) {
            var config = new io.github.mohamedsalah13.backupmanager.db.mysql.MySqlConfig()
                    .setHost(props.getHost())
                    .setPort(props.getPort() != null ? props.getPort() : 3306)
                    .setDatabase(require(props.getDatabase(), jobName, "source.database"))
                    .setUsername(props.getUsername())
                    .setPassword(props.getPassword())
                    .setUseSsl(props.isUseSsl())
                    .setIncludeRoutines(props.isIncludeRoutines())
                    .setIncludeTriggers(props.isIncludeTriggers())
                    .setSchemaOnly(props.isSchemaOnly());

            if (props.getJdbcUrl() != null) {
                config.setJdbcUrl(props.getJdbcUrl());
            }
            if (props.getMysqldumpPath() != null) {
                config.setMysqldumpPath(props.getMysqldumpPath());
            }
            if (props.getMysqlClientPath() != null) {
                config.setMysqlClientPath(props.getMysqlClientPath());
            }
            if (props.isUseApplicationDataSource()) {
                if (applicationDataSource == null) {
                    throw new BackupException.Configuration(
                            "Job '" + jobName + "' asks for the application DataSource but no"
                                    + " DataSource bean is defined");
                }
                config.setDataSource(applicationDataSource);
            }
            props.getIncludeTables().forEach(config::includeTable);
            props.getExcludeTables().forEach(config::excludeTable);

            var strategy = io.github.mohamedsalah13.backupmanager.db.mysql.MySqlBackupSource.Strategy
                    .valueOf(props.getStrategy().toUpperCase(Locale.ROOT));
            return new io.github.mohamedsalah13.backupmanager.db.mysql.MySqlBackupSource(config, strategy);
        }
    }

    /** Requires {@code backup-manager-db-file}. */
    static final class FileDatabaseSupport {

        private FileDatabaseSupport() {
        }

        static BackupSource sqlite(String jobName, BackupProperties.SourceProperties props) {
            var source = new io.github.mohamedsalah13.backupmanager.db.file.SqliteBackupSource(
                    Path.of(require(props.getFile(), jobName, "source.file")));
            if (props.getRestoreTo() != null) {
                source.restoreTo(Path.of(props.getRestoreTo()));
            }
            return source;
        }

        static BackupSource h2(String jobName, BackupProperties.SourceProperties props) {
            String url = props.getJdbcUrl();
            if (url == null && props.getFile() != null) {
                url = "jdbc:h2:file:" + Path.of(props.getFile()).toAbsolutePath();
            }
            if (url == null) {
                throw new BackupException.Configuration(
                        "Job '" + jobName + "' needs source.jdbc-url or source.file for an H2 backup");
            }
            return new io.github.mohamedsalah13.backupmanager.db.file.H2BackupSource(
                    url, props.getUsername(), props.getPassword());
        }

        static BackupSource derby(String jobName, BackupProperties.SourceProperties props) {
            String file = require(props.getFile(), jobName, "source.file");
            var source = io.github.mohamedsalah13.backupmanager.db.file.DerbyBackupSource
                    .ofDirectory(Path.of(file));
            if (props.getRestoreTo() != null) {
                source.restoreTo(Path.of(props.getRestoreTo()));
            }
            return source;
        }
    }

    /** Requires {@code backup-manager-storage-s3}. */
    static final class S3Support {

        private S3Support() {
        }

        static StorageProvider create(String jobName, String name,
                                      BackupProperties.DestinationProperties props) {
            var builder = io.github.mohamedsalah13.backupmanager.storage.s3.S3StorageProvider
                    .builder(require(props.getBucket(), jobName, "destination.bucket"))
                    .name(name)
                    .region(props.getRegion())
                    .endpoint(props.getEndpoint())
                    .keyPrefix(props.getKeyPrefix())
                    .createBucketIfMissing(props.isCreateBucketIfMissing());

            if (props.getAccessKey() != null && props.getSecretKey() != null) {
                builder.credentials(props.getAccessKey(), props.getSecretKey());
            }
            if (props.getStorageClass() != null) {
                builder.storageClass(software.amazon.awssdk.services.s3.model.StorageClass
                        .fromValue(props.getStorageClass()));
            }
            return builder.build();
        }
    }

    /** Requires {@code backup-manager-storage-gdrive}. */
    static final class GoogleDriveSupport {

        private GoogleDriveSupport() {
        }

        static StorageProvider create(String name, BackupProperties.DestinationProperties props) {
            var builder = io.github.mohamedsalah13.backupmanager.storage.gdrive
                    .GoogleDriveStorageProvider.builder()
                    .name(name)
                    .rootFolderId(props.getFolderId());
            if (props.getServiceAccountKey() != null) {
                builder.serviceAccountKey(Path.of(props.getServiceAccountKey()));
            }
            return builder.build();
        }
    }

    /** Requires {@code backup-manager-storage-dropbox}. */
    static final class DropboxSupport {

        private DropboxSupport() {
        }

        static StorageProvider create(String name, BackupProperties.DestinationProperties props) {
            var builder = io.github.mohamedsalah13.backupmanager.storage.dropbox
                    .DropboxStorageProvider.builder()
                    .name(name);
            if (props.getRootPath() != null) {
                builder.rootPath(props.getRootPath());
            }
            if (props.getRefreshToken() != null) {
                builder.refreshToken(props.getRefreshToken(), props.getAppKey(), props.getAppSecret());
            }
            if (props.getAccessToken() != null) {
                builder.accessToken(props.getAccessToken());
            }
            return builder.build();
        }
    }
}
