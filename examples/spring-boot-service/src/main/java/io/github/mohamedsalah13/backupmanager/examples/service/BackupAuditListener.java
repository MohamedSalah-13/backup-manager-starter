package io.github.mohamedsalah13.backupmanager.examples.service;

import io.github.mohamedsalah13.backupmanager.core.api.BackupListener;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.util.FileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Any {@link BackupListener} bean is picked up by the auto-configuration automatically.
 *
 * <p>This is where an alert would go — email, Slack, a metrics counter. The engine
 * swallows exceptions thrown from a listener, so a broken notifier can never turn a
 * successful backup into a failed one.
 */
@Component
public class BackupAuditListener implements BackupListener {

    private static final Logger log = LoggerFactory.getLogger(BackupAuditListener.class);

    @Override
    public void onCompleted(BackupRecord record) {
        log.info("Backup of '{}' finished: {} — {} in {}s, stored on {}",
                record.getJobName(),
                record.getStatus(),
                FileNames.humanSize(record.getStoredSize()),
                record.duration().toSeconds(),
                record.availableLocations().stream().map(l -> l.getProviderName()).toList());

        if (record.getStatus() == io.github.mohamedsalah13.backupmanager.core.model.BackupStatus.PARTIAL) {
            // Worth alerting on: the backup exists, but redundancy is gone.
            log.warn("ALERT: '{}' reached only some destinations — {}",
                    record.getJobName(), record.getErrorMessage());
        }
    }

    @Override
    public void onFailed(BackupRecord record, Throwable error) {
        log.error("ALERT: backup of '{}' failed — {}",
                record.getJobName(), record.getErrorMessage(), error);
    }

    @Override
    public void onDeleted(BackupRecord record, String reason) {
        log.info("Retention removed {} ({})", record.getFileName(), reason);
    }
}
