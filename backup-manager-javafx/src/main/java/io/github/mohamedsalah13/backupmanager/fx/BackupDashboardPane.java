package io.github.mohamedsalah13.backupmanager.fx;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.core.model.BackupStatus;
import io.github.mohamedsalah13.backupmanager.core.util.FileNames;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * At-a-glance panel showing each job's last result, next run and total size.
 *
 * <p>Refreshes on a timer so a scheduled backup that runs while the window is open shows
 * up without the user pressing anything. The timeline stops when the pane leaves the scene
 * graph, so a closed window does not keep a timer alive.
 */
public class BackupDashboardPane extends VBox {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BackupManager manager;
    private final GridPane grid = new GridPane();
    private final Timeline refreshTimer;

    public BackupDashboardPane(BackupManager manager) {
        this(manager, Duration.seconds(15));
    }

    public BackupDashboardPane(BackupManager manager, Duration refreshInterval) {
        this.manager = manager;

        setSpacing(10);
        setPadding(new Insets(12));
        grid.setHgap(16);
        grid.setVgap(6);

        getChildren().add(new TitledPane("Backup jobs", grid));

        refresh();

        refreshTimer = new Timeline(new KeyFrame(refreshInterval, e -> refresh()));
        refreshTimer.setCycleCount(Animation.INDEFINITE);
        refreshTimer.play();

        // Without this the timer would hold a reference to a pane nobody can see any more.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                refreshTimer.stop();
            } else if (refreshTimer.getStatus() != Animation.Status.RUNNING) {
                refreshTimer.play();
            }
        });
    }

    /** Rebuilds the grid from the manager's current state. */
    public void refresh() {
        grid.getChildren().clear();

        int row = 0;
        grid.add(header("Job"), 0, row);
        grid.add(header("Last run"), 1, row);
        grid.add(header("Result"), 2, row);
        grid.add(header("Next run"), 3, row);
        grid.add(header("Backups"), 4, row);
        row++;

        var statuses = manager.status();
        if (statuses.isEmpty()) {
            grid.add(new Label("No jobs configured."), 0, row, 5, 1);
            return;
        }

        for (var entry : statuses.entrySet()) {
            var status = entry.getValue();

            Label name = new Label(status.jobName());
            if (!status.enabled()) {
                name.setStyle("-fx-text-fill: #6e7781;");
                name.setText(status.jobName() + " (disabled)");
            }

            grid.add(name, 0, row);
            grid.add(new Label(status.lastRun() != null
                    ? TIMESTAMP.format(status.lastRun().atZone(ZoneId.systemDefault()))
                    : "never"), 1, row);
            grid.add(resultLabel(status.lastStatus(), status.lastRun(), status.running()), 2, row);
            grid.add(new Label(status.nextRun() != null
                    ? TIMESTAMP.format(status.nextRun())
                    : "manual"), 3, row);
            grid.add(new Label(String.valueOf(status.totalBackups())), 4, row);
            row++;
        }

        long totalSize = manager.allHistory().stream()
                .filter(r -> r.getStatus().isRestorable())
                .mapToLong(r -> r.getStoredSize())
                .sum();
        Label footer = new Label("Total stored: " + FileNames.humanSize(totalSize));
        footer.setStyle("-fx-font-style: italic; -fx-text-fill: #57606a;");
        grid.add(footer, 0, row, 5, 1);
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label resultLabel(BackupStatus status, Instant lastRun, boolean running) {
        if (running) {
            Label label = new Label("running…");
            label.setStyle("-fx-text-fill: #0969da; -fx-font-weight: bold;");
            return label;
        }
        if (status == null) {
            Label label = new Label("no backup yet");
            // Never having run is a real problem, not a neutral state.
            label.setStyle("-fx-text-fill: #cf222e;");
            return label;
        }
        Label label = new Label(status.name().toLowerCase());
        label.setStyle(switch (status) {
            case SUCCESS -> "-fx-text-fill: #1a7f37; -fx-font-weight: bold;";
            case PARTIAL -> "-fx-text-fill: #9a6700; -fx-font-weight: bold;";
            case FAILED -> "-fx-text-fill: #cf222e; -fx-font-weight: bold;";
            default -> "-fx-text-fill: #6e7781;";
        });
        return label;
    }

    /** Stops the refresh timer. Call this if the pane is discarded without leaving a scene. */
    public void stopRefreshing() {
        refreshTimer.stop();
    }
}
