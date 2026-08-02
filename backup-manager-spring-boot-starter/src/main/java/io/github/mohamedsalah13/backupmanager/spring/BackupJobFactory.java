package io.github.mohamedsalah13.backupmanager.spring;

import io.github.mohamedsalah13.backupmanager.core.api.BackupSource;
import io.github.mohamedsalah13.backupmanager.core.api.Compressor;
import io.github.mohamedsalah13.backupmanager.core.api.Encryptor;
import io.github.mohamedsalah13.backupmanager.core.api.StorageProvider;
import io.github.mohamedsalah13.backupmanager.core.compress.Compressors;
import io.github.mohamedsalah13.backupmanager.core.crypto.AesGcmEncryptor;
import io.github.mohamedsalah13.backupmanager.core.crypto.NoEncryption;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;
import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import io.github.mohamedsalah13.backupmanager.core.model.BackupType;
import io.github.mohamedsalah13.backupmanager.core.model.RetentionRule;
import io.github.mohamedsalah13.backupmanager.core.model.Schedule;
import io.github.mohamedsalah13.backupmanager.core.source.FileSystemBackupSource;
import io.github.mohamedsalah13.backupmanager.core.storage.LocalStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Sources and destinations whose modules are not on the classpath are reported with a
 * message naming the missing artifact, rather than a raw {@link ClassNotFoundException} —
 * this is the most common misconfiguration, since the cloud and database modules are all
 * optional dependencies.
 */
public class BackupJobFactory {

    private static final Logger log = LoggerFactory.getLogger(BackupJobFactory.class);

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
            case "mysql", "mariadb" -> createMySqlSource(jobName, props);
            case "sqlite" -> createSqliteSource(jobName, props);
            case "h2" -> createH2Source(jobName, props);
            case "derby" -> createDerbySource(jobName, props);
            case "files", "filesystem" -> createFileSource(jobName, props);
            default -> throw new BackupException.Configuration(
                    "Unknown source type '" + type + "' for job '" + jobName
                            + "'. Supported: mysql, sqlite, h2, derby, files");
        };
    }

    private BackupSource createMySqlSource(String jobName, BackupProperties.SourceProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.db.mysql.MySqlBackupSource",
                jobName, "mysql", "backup-manager-db-mysql");

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

    private BackupSource createSqliteSource(String jobName, BackupProperties.SourceProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.db.file.SqliteBackupSource",
                jobName, "sqlite", "backup-manager-db-file");

        var source = new io.github.mohamedsalah13.backupmanager.db.file.SqliteBackupSource(
                Path.of(require(props.getFile(), jobName, "source.file")));
        if (props.getRestoreTo() != null) {
            source.restoreTo(Path.of(props.getRestoreTo()));
        }
        return source;
    }

    private BackupSource createH2Source(String jobName, BackupProperties.SourceProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.db.file.H2BackupSource",
                jobName, "h2", "backup-manager-db-file");

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

    private BackupSource createDerbySource(String jobName, BackupProperties.SourceProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.db.file.DerbyBackupSource",
                jobName, "derby", "backup-manager-db-file");

        String file = require(props.getFile(), jobName, "source.file");
        var source = io.github.mohamedsalah13.backupmanager.db.file.DerbyBackupSource.ofDirectory(Path.of(file));
        if (props.getRestoreTo() != null) {
            source.restoreTo(Path.of(props.getRestoreTo()));
        }
        return source;
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
            case "s3" -> createS3(jobName, name, props);
            case "gdrive", "googledrive", "google-drive" -> createGoogleDrive(jobName, name, props);
            case "dropbox" -> createDropbox(jobName, name, props);
            default -> throw new BackupException.Configuration(
                    "Unknown destination type '" + type + "' for job '" + jobName
                            + "'. Supported: local, s3, gdrive, dropbox");
        };
    }

    private StorageProvider createS3(String jobName, String name,
                                     BackupProperties.DestinationProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.storage.s3.S3StorageProvider",
                jobName, "s3", "backup-manager-storage-s3");

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

    private StorageProvider createGoogleDrive(String jobName, String name,
                                              BackupProperties.DestinationProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.storage.gdrive.GoogleDriveStorageProvider",
                jobName, "gdrive", "backup-manager-storage-gdrive");

        var builder = io.github.mohamedsalah13.backupmanager.storage.gdrive.GoogleDriveStorageProvider.builder()
                .name(name)
                .rootFolderId(props.getFolderId());
        if (props.getServiceAccountKey() != null) {
            builder.serviceAccountKey(Path.of(props.getServiceAccountKey()));
        }
        return builder.build();
    }

    private StorageProvider createDropbox(String jobName, String name,
                                          BackupProperties.DestinationProperties props) {
        requireModule("io.github.mohamedsalah13.backupmanager.storage.dropbox.DropboxStorageProvider",
                jobName, "dropbox", "backup-manager-storage-dropbox");

        var builder = io.github.mohamedsalah13.backupmanager.storage.dropbox.DropboxStorageProvider.builder()
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

    private io.github.mohamedsalah13.backupmanager.core.api.RetentionPolicy createRetention(
            BackupProperties.RetentionProperties props) {
        if (props == null) {
            return io.github.mohamedsalah13.backupmanager.core.api.RetentionPolicy.KEEP_ALL;
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
        return io.github.mohamedsalah13.backupmanager.core.retention.RetentionPolicies.of(rule);
    }

    private BackupType parseType(String type) {
        if (type == null || type.isBlank()) {
            return BackupType.FULL;
        }
        try {
            return BackupType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BackupException.Configuration(
                    "Unknown backup type '" + type + "'. Supported: "
                            + List.of(BackupType.values()));
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
     * Checks that an optional module is present, so the failure names the dependency to add
     * instead of surfacing as a bare NoClassDefFoundError deep in a builder.
     */
    private static void requireModule(String className, String jobName,
                                      String feature, String artifactId) {
        if (!ClassUtils.isPresent(className, BackupJobFactory.class.getClassLoader())) {
            throw new BackupException.Configuration(
                    "Job '" + jobName + "' uses '" + feature + "', which needs the optional module '"
                            + artifactId + "'. Add it to your dependencies:\n"
                            + "  <dependency>\n"
                            + "    <groupId>io.github.mohamedsalah13</groupId>\n"
                            + "    <artifactId>" + artifactId + "</artifactId>\n"
                            + "  </dependency>");
        }
        log.debug("Optional module for '{}' is present", feature);
    }
}
