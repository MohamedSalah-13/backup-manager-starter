package com.backupmanager.spring;

import com.backupmanager.core.BackupManager;
import com.backupmanager.core.model.BackupException;
import com.backupmanager.core.model.BackupJob;
import com.backupmanager.core.model.BackupRecord;
import com.backupmanager.core.model.BackupStatus;
import com.backupmanager.core.source.FileSystemBackupSource;
import com.backupmanager.core.storage.LocalStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupAutoConfigurationTest {

    @TempDir
    Path temp;

    private ApplicationContextRunner runner;
    private Path sourceDir;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = Files.createDirectories(temp.resolve("data"));
        Files.writeString(sourceDir.resolve("report.txt"), "quarterly figures");

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BackupAutoConfiguration.class))
                .withPropertyValues("backup.storage-root=" + temp.resolve("state").toString()
                        .replace('\\', '/'));
    }

    private String[] fileJobProperties(String jobName) {
        return new String[]{
                "backup.jobs." + jobName + ".source.type=files",
                "backup.jobs." + jobName + ".source.paths[0]="
                        + sourceDir.toString().replace('\\', '/'),
                "backup.jobs." + jobName + ".destinations[0].type=local",
                "backup.jobs." + jobName + ".destinations[0].path="
                        + temp.resolve("backups").toString().replace('\\', '/')
        };
    }

    @Test
    void createsAManagerFromProperties() {
        runner.withPropertyValues(fileJobProperties("docs")).run(context -> {
            assertThat(context).hasSingleBean(BackupManager.class);
            BackupManager manager = context.getBean(BackupManager.class);
            assertThat(manager.getJobs()).hasSize(1);
            assertThat(manager.getJob("docs")).isPresent();
        });
    }

    @Test
    void backsUpUsingThePropertiesConfiguration() {
        runner.withPropertyValues(fileJobProperties("docs")).run(context -> {
            BackupManager manager = context.getBean(BackupManager.class);

            BackupRecord record = manager.backupNow("docs");

            assertThat(record.getStatus()).isEqualTo(BackupStatus.SUCCESS);
            assertThat(record.availableLocations()).hasSize(1);
        });
    }

    @Test
    void bindsTheScheduleFromCron() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues("backup.jobs.docs.schedule.cron=0 0 3 * * *")
                .run(context -> {
                    BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
                    assertThat(job.getSchedule().getKind())
                            .isEqualTo(com.backupmanager.core.model.Schedule.Kind.CRON);
                    assertThat(job.getSchedule().getCron()).isEqualTo("0 0 3 * * *");
                });
    }

    @Test
    void bindsRetentionRules() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues(
                        "backup.jobs.docs.retention.keep-daily=7",
                        "backup.jobs.docs.retention.keep-weekly=4",
                        "backup.jobs.docs.retention.max-age-days=90")
                .run(context -> {
                    BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
                    assertThat(job.getRetention().describe())
                            .contains("gfs=7/4/0/0")
                            .contains("maxAge");
                });
    }

    @Test
    void enablesEncryptionWhenAPasswordIsSet() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues("backup.jobs.docs.encryption.password=from-yaml")
                .run(context -> {
                    BackupManager manager = context.getBean(BackupManager.class);
                    assertThat(manager.getJob("docs").orElseThrow().getEncryptor().isEnabled()).isTrue();

                    BackupRecord record = manager.backupNow("docs");
                    assertThat(record.isEncrypted()).isTrue();
                    assertThat(record.getFileName()).endsWith(".enc");
                });
    }

    @Test
    void leavesEncryptionOffWhenNoPasswordIsSet() {
        runner.withPropertyValues(fileJobProperties("docs")).run(context -> {
            BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
            assertThat(job.getEncryptor().isEnabled()).isFalse();
        });
    }

    @Test
    void selectsTheCompressionCodec() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues("backup.jobs.docs.compression=bzip2")
                .run(context -> {
                    BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
                    assertThat(job.getCompressor().id()).isEqualTo("bzip2");
                });
    }

    @Test
    void backsOffEntirelyWhenDisabled() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues("backup.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BackupManager.class));
    }

    @Test
    void jobBeansTakePrecedenceOverProperties() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withUserConfiguration(ProgrammaticJobConfig.class)
                .run(context -> {
                    BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
                    // The bean sets a distinctive prefix the properties entry does not.
                    assertThat(job.getPathPrefix()).isEqualTo("from-the-bean");
                });
    }

    @Test
    void reportsAMissingRequiredPropertyByName() {
        runner.withPropertyValues(
                        "backup.jobs.broken.source.type=mysql",
                        "backup.jobs.broken.destinations[0].type=local",
                        "backup.jobs.broken.destinations[0].path="
                                + temp.resolve("b").toString().replace('\\', '/'))
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(BackupException.Configuration.class)
                        .hasMessageContaining("backup.jobs.broken.source.database"));
    }

    @Test
    void reportsAnUnknownSourceTypeClearly() {
        runner.withPropertyValues(
                        "backup.jobs.broken.source.type=oracle",
                        "backup.jobs.broken.destinations[0].type=local",
                        "backup.jobs.broken.destinations[0].path="
                                + temp.resolve("b").toString().replace('\\', '/'))
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("Unknown source type 'oracle'"));
    }

    @Test
    void reportsAJobWithNoDestination() {
        runner.withPropertyValues(
                        "backup.jobs.broken.source.type=files",
                        "backup.jobs.broken.source.paths[0]=" + sourceDir.toString().replace('\\', '/'))
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("has no destinations"));
    }

    @Test
    void supportsSeveralDestinations() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues(
                        "backup.jobs.docs.destinations[1].type=local",
                        "backup.jobs.docs.destinations[1].name=secondary",
                        "backup.jobs.docs.destinations[1].path="
                                + temp.resolve("backups2").toString().replace('\\', '/'))
                .run(context -> {
                    BackupManager manager = context.getBean(BackupManager.class);
                    assertThat(manager.getJob("docs").orElseThrow().getDestinations()).hasSize(2);

                    BackupRecord record = manager.backupNow("docs");
                    assertThat(record.availableLocations()).hasSize(2);
                });
    }

    @Test
    void respectsTheDisabledFlagOnAJob() {
        runner.withPropertyValues(fileJobProperties("docs"))
                .withPropertyValues("backup.jobs.docs.enabled=false")
                .run(context -> {
                    BackupJob job = context.getBean(BackupManager.class).getJob("docs").orElseThrow();
                    assertThat(job.isEnabled()).isFalse();
                });
    }

    @Configuration
    static class ProgrammaticJobConfig {

        @Bean
        BackupJob docs(@org.springframework.beans.factory.annotation.Value("${java.io.tmpdir}") String tmp) {
            Path root = Path.of(tmp, "bm-bean-test");
            return BackupJob.builder("docs")
                    .source(new FileSystemBackupSource(root))
                    .destination(new LocalStorageProvider("bean-disk", root.resolve("out")))
                    .pathPrefix("from-the-bean")
                    .build();
        }
    }
}
