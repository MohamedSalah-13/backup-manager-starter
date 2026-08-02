package io.github.mohamedsalah13.backupmanager.fx;

import io.github.mohamedsalah13.backupmanager.core.engine.RestoreEngine;
import io.github.mohamedsalah13.backupmanager.core.model.BackupRecord;
import io.github.mohamedsalah13.backupmanager.core.model.BackupStatus;
import io.github.mohamedsalah13.backupmanager.core.util.FileNames;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Drop-in pane listing every backup with buttons to run, restore, verify and delete.
 *
 * <pre>{@code
 * FxBackupService service = new FxBackupService(manager);
 * tab.setContent(new BackupHistoryPane(service));
 * }</pre>
 *
 * <p>Restore and delete both ask for confirmation first, and the restore dialog names the
 * exact backup and target — these are the two actions a mis-click cannot be taken back.
 */
public class BackupHistoryPane extends BorderPane {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FxBackupService service;
    private final TableView<BackupRecord> table = new TableView<>();
    private final FilteredList<BackupRecord> filtered;
    private final ComboBox<String> jobFilter = new ComboBox<>();
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar();

    public BackupHistoryPane(FxBackupService service) {
        this.service = service;
        this.filtered = new FilteredList<>(service.getHistory(), r -> true);

        setTop(buildToolBar());
        setCenter(buildTable());
        setBottom(buildStatusBar());
        setPadding(new Insets(8));

        service.refreshHistory();
        refreshJobFilter();
    }

    private ToolBar buildToolBar() {
        Button runButton = new Button("Back up now");
        runButton.setOnAction(e -> runSelectedJob());
        runButton.disableProperty().bind(service.runningProperty());

        Button restoreButton = new Button("Restore…");
        restoreButton.setOnAction(e -> restoreSelected());

        Button verifyButton = new Button("Verify");
        verifyButton.setOnAction(e -> verifySelected());

        Button deleteButton = new Button("Delete");
        deleteButton.setOnAction(e -> deleteSelected());

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> service.cancel(service.currentJobProperty().get()));
        cancelButton.disableProperty().bind(service.runningProperty().not());

        // Everything that acts on a specific backup needs one selected.
        var noSelection = table.getSelectionModel().selectedItemProperty().isNull();
        restoreButton.disableProperty().bind(noSelection.or(service.runningProperty()));
        verifyButton.disableProperty().bind(noSelection.or(service.runningProperty()));
        deleteButton.disableProperty().bind(noSelection.or(service.runningProperty()));

        jobFilter.setPromptText("All jobs");
        jobFilter.valueProperty().addListener((obs, old, selected) -> applyFilter(selected));

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            service.refreshHistory();
            refreshJobFilter();
        });

        return new ToolBar(runButton, restoreButton, verifyButton, deleteButton,
                cancelButton, new javafx.scene.control.Separator(),
                new Label("Job:"), jobFilter, refreshButton);
    }

    private TableView<BackupRecord> buildTable() {
        TableColumn<BackupRecord, String> jobColumn = new TableColumn<>("Job");
        jobColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getJobName()));
        jobColumn.setPrefWidth(130);

        TableColumn<BackupRecord, String> whenColumn = new TableColumn<>("Taken");
        whenColumn.setCellValueFactory(c -> new SimpleStringProperty(
                TIMESTAMP.format(c.getValue().getStartedAt().atZone(ZoneId.systemDefault()))));
        whenColumn.setPrefWidth(160);

        TableColumn<BackupRecord, BackupStatus> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getStatus()));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(BackupStatus status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(status.name());
                // Colour rather than an icon so the pane needs no bundled resources.
                setStyle(switch (status) {
                    case SUCCESS -> "-fx-text-fill: #1a7f37; -fx-font-weight: bold;";
                    case PARTIAL -> "-fx-text-fill: #9a6700; -fx-font-weight: bold;";
                    case FAILED -> "-fx-text-fill: #cf222e; -fx-font-weight: bold;";
                    case CANCELLED -> "-fx-text-fill: #6e7781;";
                    default -> "";
                });
            }
        });
        statusColumn.setPrefWidth(90);

        TableColumn<BackupRecord, String> sizeColumn = new TableColumn<>("Size");
        sizeColumn.setCellValueFactory(c -> new SimpleStringProperty(
                FileNames.humanSize(c.getValue().getStoredSize())));
        sizeColumn.setPrefWidth(90);

        TableColumn<BackupRecord, String> durationColumn = new TableColumn<>("Duration");
        durationColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().duration().toSeconds() + "s"));
        durationColumn.setPrefWidth(80);

        TableColumn<BackupRecord, String> whereColumn = new TableColumn<>("Stored on");
        whereColumn.setCellValueFactory(c -> new SimpleStringProperty(
                String.join(", ", c.getValue().availableLocations().stream()
                        .map(l -> l.getProviderName()).toList())));
        whereColumn.setPrefWidth(160);

        TableColumn<BackupRecord, String> detailColumn = new TableColumn<>("Details");
        detailColumn.setCellValueFactory(c -> {
            BackupRecord r = c.getValue();
            StringBuilder sb = new StringBuilder(r.getType().name().toLowerCase());
            if (r.isEncrypted()) {
                sb.append(" · encrypted");
            }
            if (!"none".equals(r.getCompressorId())) {
                sb.append(" · ").append(r.getCompressorId());
            }
            if (r.getErrorMessage() != null) {
                sb.append(" · ").append(r.getErrorMessage());
            }
            return new SimpleStringProperty(sb.toString());
        });
        detailColumn.setPrefWidth(280);

        table.getColumns().setAll(List.of(jobColumn, whenColumn, statusColumn, sizeColumn,
                durationColumn, whereColumn, detailColumn));
        table.setItems(filtered);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setPlaceholder(new Label("No backups yet. Press \"Back up now\" to create one."));
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private HBox buildStatusBar() {
        statusLabel.textProperty().bind(service.statusMessageProperty());
        progressBar.progressProperty().bind(service.progressProperty());
        progressBar.visibleProperty().bind(service.runningProperty());
        progressBar.setPrefWidth(220);

        Label countLabel = new Label();
        countLabel.textProperty().bind(Bindings.createStringBinding(
                () -> filtered.size() + " backup(s)", filtered));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, statusLabel, spacer, countLabel, progressBar);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 2, 2, 2));
        return bar;
    }

    private void applyFilter(String jobName) {
        filtered.setPredicate(record -> jobName == null || jobName.isBlank()
                || jobName.equals(record.getJobName()));
    }

    private void refreshJobFilter() {
        var names = FXCollections.observableArrayList(
                service.getManager().getJobs().stream().map(j -> j.getName()).sorted().toList());
        names.add(0, "");
        String current = jobFilter.getValue();
        jobFilter.setItems(names);
        if (current != null && names.contains(current)) {
            jobFilter.setValue(current);
        }
    }

    // ---------------------------------------------------------------- actions

    private void runSelectedJob() {
        String jobName = jobFilter.getValue();
        if (jobName == null || jobName.isBlank()) {
            var jobs = service.getManager().getJobs();
            if (jobs.size() == 1) {
                jobName = jobs.get(0).getName();
            } else {
                alert(Alert.AlertType.INFORMATION, "Choose a job",
                        "Select a job in the filter above, then press \"Back up now\".");
                return;
            }
        }
        service.backup(jobName);
    }

    private void restoreSelected() {
        BackupRecord record = table.getSelectionModel().getSelectedItem();
        if (record == null) {
            return;
        }
        if (!record.getStatus().isRestorable()) {
            alert(Alert.AlertType.WARNING, "Not restorable",
                    "This backup finished with status " + record.getStatus()
                            + " and holds no usable copy.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm restore");
        confirm.setHeaderText("Restore " + record.getFileName() + "?");
        confirm.setContentText("""
                This replaces the current contents of the target with the state captured at %s.

                Anything changed since then will be lost. This cannot be undone.
                """.formatted(TIMESTAMP.format(
                        record.getStartedAt().atZone(ZoneId.systemDefault()))));
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, new ButtonType("Restore"));

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get().getButtonData().isCancelButton()) {
            return;
        }

        service.restore(record.getId(), new RestoreEngine.RestoreOptions(),
                () -> alert(Alert.AlertType.INFORMATION, "Restore complete",
                        "The backup was restored successfully."),
                error -> alert(Alert.AlertType.ERROR, "Restore failed", message(error)));
    }

    private void verifySelected() {
        BackupRecord record = table.getSelectionModel().getSelectedItem();
        if (record == null) {
            return;
        }
        service.verify(record.getId(), ok -> alert(
                ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                ok ? "Backup verified" : "Verification failed",
                ok ? "The backup was read and decoded end to end. It can be restored."
                   : "This backup could not be decoded. Do not rely on it."));
    }

    private void deleteSelected() {
        BackupRecord record = table.getSelectionModel().getSelectedItem();
        if (record == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm delete");
        confirm.setHeaderText("Delete " + record.getFileName() + "?");
        confirm.setContentText("The backup will be removed from every destination it was stored on.");

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.OK) {
            try {
                service.getManager().deleteBackup(record.getId());
                service.refreshHistory();
            } catch (RuntimeException e) {
                alert(Alert.AlertType.ERROR, "Delete failed", message(e));
            }
        }
    }

    private static String message(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }

    private void alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.initOwner(getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }

    public TableView<BackupRecord> getTable() {
        return table;
    }

    /** Restricts the pane to one job and hides the filter. */
    public void pinToJob(String jobName) {
        jobFilter.setValue(jobName);
        jobFilter.setVisible(false);
        jobFilter.setManaged(false);
        applyFilter(jobName);
    }
}
