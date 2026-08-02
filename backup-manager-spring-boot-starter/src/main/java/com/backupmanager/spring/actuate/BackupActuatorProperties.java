package com.backupmanager.spring.actuate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code backup.actuator.*}. */
@ConfigurationProperties(prefix = "backup.actuator")
public class BackupActuatorProperties {

    /** Contributes a {@code backup} entry to the health endpoint. */
    private boolean healthEnabled = true;

    /** Exposes the {@code /actuator/backup} endpoint. */
    private boolean endpointEnabled = true;

    /**
     * A job whose last successful backup is older than this reports DOWN. Set it a little
     * above the job's own interval so one skipped nightly run raises the alarm.
     */
    private Duration staleAfter = Duration.ofDays(2);

    public boolean isHealthEnabled() {
        return healthEnabled;
    }

    public void setHealthEnabled(boolean healthEnabled) {
        this.healthEnabled = healthEnabled;
    }

    public boolean isEndpointEnabled() {
        return endpointEnabled;
    }

    public void setEndpointEnabled(boolean endpointEnabled) {
        this.endpointEnabled = endpointEnabled;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }
}
