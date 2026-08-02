package io.github.mohamedsalah13.backupmanager.examples.desktop;

import io.github.mohamedsalah13.backupmanager.core.BackupManager;
import io.github.mohamedsalah13.backupmanager.fx.BackupDashboardPane;
import io.github.mohamedsalah13.backupmanager.fx.BackupHistoryPane;
import io.github.mohamedsalah13.backupmanager.fx.FxBackupService;
import io.github.mohamedsalah13.backupmanager.fx.ScheduleEditorPane;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Desktop application showing the library end to end: a SQLite database and a documents
 * folder, backed up to two locations, encrypted, on a schedule, with the ready-made panes
 * for history and scheduling.
 *
 * <p>Run it from this module's own directory, after {@code mvn install} at the repository
 * root. Running it from there with {@code -pl} fails: the {@code javafx} plugin prefix is
 * resolved against the root POM, which does not declare the plugin.
 * <pre>
 *   cd examples/desktop-javafx &amp;&amp; mvn javafx:run
 * </pre>
 *
 * <p>Everything it writes lives under {@code ./demo-data}, so deleting that folder resets
 * the example completely.
 */
public class DesktopExampleApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(DesktopExampleApp.class);

    private static final Path ROOT = Path.of("./demo-data");

    private BackupManager manager;
    private FxBackupService service;
    private DemoDatabase database;
    private Label rowCountLabel;

    @Override
    public void init() throws Exception {
        database = new DemoDatabase(ROOT.resolve("app/customers.db"));
        database.createIfMissing();

        Path documents = ROOT.resolve("app/documents");
        if (!Files.exists(documents)) {
            Files.createDirectories(documents);
            Files.writeString(documents.resolve("notes.txt"), "Covered by the documents job.\n");
            Files.writeString(documents.resolve("scratch.tmp"), "Excluded by **/*.tmp.\n");
        }

        manager = DemoBackupSetup.createManager(
                ROOT, database.getFile(), documents,
                // A real application would read this from the user's settings or the OS
                // credential store, never from a literal.
                "demo-passphrase-change-me");

        manager.start();
        service = new FxBackupService(manager);
    }

    @Override
    public void start(Stage stage) {
        TabPane tabs = new TabPane(
                new Tab("Overview", new BackupDashboardPane(manager)),
                new Tab("History", new BackupHistoryPane(service)),
                new Tab("Schedule", new ScheduleEditorPane(
                        manager.getJob("customers-db").orElseThrow().getSchedule())));
        tabs.getTabs().forEach(tab -> tab.setClosable(false));

        BorderPane layout = new BorderPane();
        layout.setTop(buildDemoBar());
        layout.setCenter(tabs);

        stage.setTitle("Backup Manager — desktop example");
        stage.setScene(new Scene(layout, 1080, 680));
        stage.show();

        log.info("Backups are written under {}", ROOT.toAbsolutePath());
    }

    /**
     * Controls that exist only to make the library's behaviour visible: change the data,
     * back up, change it again, then restore and watch the row count go back.
     */
    private VBox buildDemoBar() {
        rowCountLabel = new Label();
        refreshRowCount();

        Button addRow = new Button("Add a customer");
        addRow.setOnAction(e -> {
            try {
                database.addCustomer("Added at " + java.time.LocalTime.now().withNano(0));
                refreshRowCount();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            }
        });

        Button refresh = new Button("Refresh count");
        refresh.setOnAction(e -> refreshRowCount());

        Label hint = new Label("""
                Try this: note the row count, back up on the History tab, add a few \
                customers, then restore the backup and press Refresh — the count returns \
                to what it was.""");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #57606a;");

        HBox controls = new HBox(10, rowCountLabel, addRow, refresh);
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox bar = new VBox(6, controls, hint);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #f6f8fa; -fx-border-color: #d0d7de;"
                + " -fx-border-width: 0 0 1 0;");
        return bar;
    }

    private void refreshRowCount() {
        rowCountLabel.setText("Customers in the database: " + database.countRows() + "   ");
    }

    @Override
    public void stop() {
        // Shuts down the scheduler and the worker pool, and flushes the catalog.
        if (manager != null) {
            manager.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
