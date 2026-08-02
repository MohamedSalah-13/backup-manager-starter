package com.backupmanager.spring.actuate;

import com.backupmanager.core.BackupManager;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Registers the backup health indicator and endpoint when Actuator is on the classpath.
 * Kept separate from the main auto-configuration so applications without Actuator never
 * load these classes.
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(BackupManager.class)
@EnableConfigurationProperties(BackupActuatorProperties.class)
public class BackupActuatorAutoConfiguration {

    @Bean("backupHealthIndicator")
    @ConditionalOnMissingBean(name = "backupHealthIndicator")
    @ConditionalOnProperty(prefix = "backup.actuator", name = "health-enabled",
            havingValue = "true", matchIfMissing = true)
    public HealthIndicator backupHealthIndicator(BackupManager manager,
                                                 BackupActuatorProperties properties) {
        Duration staleAfter = properties.getStaleAfter() != null
                ? properties.getStaleAfter() : Duration.ofDays(2);
        return new BackupHealthIndicator(manager, staleAfter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "backup.actuator", name = "endpoint-enabled",
            havingValue = "true", matchIfMissing = true)
    public BackupEndpoint backupEndpoint(BackupManager manager) {
        return new BackupEndpoint(manager);
    }
}
