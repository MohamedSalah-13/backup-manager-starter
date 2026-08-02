package io.github.mohamedsalah13.backupmanager.spring;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.api.BackupListener;
import io.github.mohamedsalah13.backupmanager.core.model.BackupJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Wires a {@link BackupManager} from {@code backup.*} properties.
 *
 * <p>Jobs come from two places and both are honoured: entries under {@code backup.jobs},
 * and any {@link BackupJob} beans the application defines. Beans win on a name clash,
 * because code that constructs a job explicitly is being more deliberate than YAML.
 *
 * <p>Set {@code backup.enabled=false} to switch the whole thing off — useful in tests and
 * in local development, where writing real backups is rarely wanted.
 */
@AutoConfiguration
@EnableConfigurationProperties(BackupProperties.class)
@ConditionalOnProperty(prefix = "backup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BackupAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BackupAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public BackupJobFactory backupJobFactory(ObjectProvider<DataSource> dataSource) {
        return new BackupJobFactory(dataSource.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public BackupManager backupManager(BackupProperties properties,
                                       BackupJobFactory factory,
                                       ObjectProvider<BackupJob> jobBeans,
                                       ObjectProvider<BackupListener> listeners) {

        Path storageRoot = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        BackupManager.Builder builder = BackupManager.builder()
                .storageRoot(storageRoot)
                .parallelism(properties.getParallelism());

        if (properties.getWorkDirectory() != null && !properties.getWorkDirectory().isBlank()) {
            builder.workDirectory(Path.of(properties.getWorkDirectory()));
        }

        List<BackupJob> programmatic = jobBeans.orderedStream().toList();
        programmatic.forEach(builder::job);

        for (Map.Entry<String, BackupProperties.JobProperties> entry : properties.getJobs().entrySet()) {
            String name = entry.getKey();
            boolean overriddenByBean = programmatic.stream()
                    .anyMatch(job -> job.getName().equals(name));
            if (overriddenByBean) {
                log.info("Job '{}' is defined as a bean; the properties entry is ignored", name);
                continue;
            }
            if (!entry.getValue().isEnabled()) {
                // Not built at all, deliberately. Constructing it would demand whatever
                // optional module it names, so a job switched off pending credentials
                // would still force the SDK onto the classpath — which defeats the flag.
                log.info("Job '{}' is disabled; skipping it", name);
                continue;
            }
            builder.job(factory.create(name, entry.getValue()));
        }

        listeners.orderedStream().forEach(builder::listener);

        BackupManager manager = builder.build();
        log.info("Backup manager configured with {} job(s), storage root {}",
                manager.getJobs().size(), storageRoot);
        return manager;
    }

    /**
     * Starts the scheduler once the context is fully up.
     *
     * <p>Deliberately on {@code ApplicationReadyEvent} rather than in the bean's
     * initialiser: a job firing while other beans are still being created would be
     * backing up a half-initialised application.
     */
    @Bean
    @ConditionalOnMissingBean
    public BackupLifecycle backupLifecycle(BackupManager manager, BackupProperties properties) {
        return new BackupLifecycle(manager, properties);
    }

    /** Starts the scheduler and, if asked, runs every job once at startup. */
    public static class BackupLifecycle {

        private final BackupManager manager;
        private final BackupProperties properties;

        public BackupLifecycle(BackupManager manager, BackupProperties properties) {
            this.manager = manager;
            this.properties = properties;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            if (properties.isAutoStart()) {
                manager.start();
                manager.upcomingRuns().forEach((job, when) ->
                        log.info("Next run of '{}' is at {}", job, when));
            } else {
                log.info("Backup scheduler is configured but auto-start is off");
            }

            if (properties.isRunOnStartup()) {
                log.info("Running every enabled backup job once at startup");
                manager.getJobs().stream()
                        .filter(BackupJob::isEnabled)
                        // Asynchronous so a slow dump does not hold up the startup sequence.
                        .forEach(job -> manager.backupAsync(job.getName()));
            }
        }
    }
}
