package io.github.mohamedsalah13.backupmanager.fx;

import io.github.mohamedsalah13.backupmanager.core.model.Schedule;
import io.github.mohamedsalah13.backupmanager.core.schedule.BackupScheduler;
import io.github.mohamedsalah13.backupmanager.core.schedule.CronExpression;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Editor for a backup schedule, with a live preview of the next five fire times.
 *
 * <p>The preview is the point of this pane. Cron syntax is easy to get subtly wrong —
 * {@code 0 0 * * *} means hourly in six-field form and daily in five-field form — and
 * seeing the actual dates catches that immediately, rather than a month later when
 * somebody notices the backups are missing.
 */
public class ScheduleEditorPane extends VBox {

    private static final DateTimeFormatter PREVIEW_FORMAT =
            DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss");

    private final ObjectProperty<Schedule> schedule = new SimpleObjectProperty<>(Schedule.manual());

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton manualMode = new RadioButton("Manual only");
    private final RadioButton dailyMode = new RadioButton("Daily");
    private final RadioButton weeklyMode = new RadioButton("Weekly");
    private final RadioButton intervalMode = new RadioButton("Every");
    private final RadioButton cronMode = new RadioButton("Cron expression");

    private final Spinner<Integer> hourSpinner = new Spinner<>(0, 23, 2);
    private final Spinner<Integer> minuteSpinner = new Spinner<>(0, 59, 30);
    private final ComboBox<DayOfWeek> daySelector = new ComboBox<>();
    private final Spinner<Integer> intervalSpinner = new Spinner<>(1, 720, 60);
    private final ComboBox<String> intervalUnit = new ComboBox<>();
    private final TextField cronField = new TextField("0 0 2 * * *");
    private final Label cronError = new Label();
    private final ListView<String> preview = new ListView<>();

    public ScheduleEditorPane() {
        this(Schedule.manual());
    }

    public ScheduleEditorPane(Schedule initial) {
        setSpacing(10);
        setPadding(new Insets(12));

        buildControls();
        applyInitial(initial);
        wireUpdates();
        rebuildSchedule();
    }

    private void buildControls() {
        List.of(manualMode, dailyMode, weeklyMode, intervalMode, cronMode)
                .forEach(button -> button.setToggleGroup(modeGroup));

        daySelector.setItems(FXCollections.observableArrayList(DayOfWeek.values()));
        daySelector.setValue(DayOfWeek.SUNDAY);
        daySelector.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DayOfWeek day) {
                return day == null ? "" : day.getDisplayName(TextStyle.FULL, Locale.getDefault());
            }

            @Override
            public DayOfWeek fromString(String value) {
                return null;
            }
        });

        intervalUnit.setItems(FXCollections.observableArrayList("minutes", "hours", "days"));
        intervalUnit.setValue("hours");

        hourSpinner.setEditable(true);
        minuteSpinner.setEditable(true);
        intervalSpinner.setEditable(true);
        hourSpinner.setPrefWidth(80);
        minuteSpinner.setPrefWidth(80);
        intervalSpinner.setPrefWidth(90);

        cronField.setPromptText("second minute hour day-of-month month day-of-week");
        cronError.setStyle("-fx-text-fill: #cf222e;");
        cronError.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);

        grid.add(manualMode, 0, 0, 3, 1);

        grid.add(dailyMode, 0, 1);
        grid.add(new HBox(6, new Label("at"), hourSpinner, new Label(":"), minuteSpinner), 1, 1, 2, 1);

        grid.add(weeklyMode, 0, 2);
        grid.add(new HBox(6, new Label("on"), daySelector), 1, 2, 2, 1);

        grid.add(intervalMode, 0, 3);
        grid.add(new HBox(6, intervalSpinner, intervalUnit), 1, 3, 2, 1);

        grid.add(cronMode, 0, 4);
        grid.add(cronField, 1, 4, 2, 1);
        grid.add(cronError, 1, 5, 2, 1);

        preview.setPrefHeight(140);
        preview.setPlaceholder(new Label("This schedule never fires on its own."));

        getChildren().addAll(
                new Label("When should this backup run?"),
                grid,
                new Label("Next runs:"),
                preview);
    }

    private void applyInitial(Schedule initial) {
        if (initial == null || initial.getKind() == Schedule.Kind.MANUAL) {
            manualMode.setSelected(true);
            return;
        }
        if (initial.getKind() == Schedule.Kind.FIXED_INTERVAL) {
            intervalMode.setSelected(true);
            Duration interval = initial.getInterval();
            if (interval.toDays() > 0 && interval.toHoursPart() == 0) {
                intervalUnit.setValue("days");
                intervalSpinner.getValueFactory().setValue((int) interval.toDays());
            } else if (interval.toHours() > 0 && interval.toMinutesPart() == 0) {
                intervalUnit.setValue("hours");
                intervalSpinner.getValueFactory().setValue((int) interval.toHours());
            } else {
                intervalUnit.setValue("minutes");
                intervalSpinner.getValueFactory().setValue((int) Math.max(1, interval.toMinutes()));
            }
            return;
        }
        cronMode.setSelected(true);
        cronField.setText(initial.getCron());
    }

    private void wireUpdates() {
        modeGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            updateEnabledState();
            rebuildSchedule();
        });
        hourSpinner.valueProperty().addListener((o, a, b) -> rebuildSchedule());
        minuteSpinner.valueProperty().addListener((o, a, b) -> rebuildSchedule());
        daySelector.valueProperty().addListener((o, a, b) -> rebuildSchedule());
        intervalSpinner.valueProperty().addListener((o, a, b) -> rebuildSchedule());
        intervalUnit.valueProperty().addListener((o, a, b) -> rebuildSchedule());
        cronField.textProperty().addListener((o, a, b) -> rebuildSchedule());
        updateEnabledState();
    }

    private void updateEnabledState() {
        Toggle selected = modeGroup.getSelectedToggle();
        boolean daily = selected == dailyMode;
        boolean weekly = selected == weeklyMode;
        boolean interval = selected == intervalMode;
        boolean cron = selected == cronMode;

        hourSpinner.setDisable(!(daily || weekly));
        minuteSpinner.setDisable(!(daily || weekly));
        daySelector.setDisable(!weekly);
        intervalSpinner.setDisable(!interval);
        intervalUnit.setDisable(!interval);
        cronField.setDisable(!cron);
    }

    private void rebuildSchedule() {
        cronError.setText("");
        Toggle selected = modeGroup.getSelectedToggle();

        Schedule result;
        try {
            if (selected == dailyMode) {
                result = Schedule.dailyAt(hourSpinner.getValue(), minuteSpinner.getValue());
            } else if (selected == weeklyMode) {
                result = Schedule.weeklyOn(daySelector.getValue(),
                        hourSpinner.getValue(), minuteSpinner.getValue());
            } else if (selected == intervalMode) {
                result = Schedule.every(intervalDuration());
            } else if (selected == cronMode) {
                String expression = cronField.getText();
                if (!CronExpression.isValid(expression)) {
                    // Keep the previous valid schedule while the user is mid-typing.
                    cronError.setText("Not a valid cron expression yet");
                    preview.getItems().clear();
                    return;
                }
                result = Schedule.cron(expression);
            } else {
                result = Schedule.manual();
            }
        } catch (RuntimeException e) {
            cronError.setText(e.getMessage());
            preview.getItems().clear();
            return;
        }

        schedule.set(result);
        updatePreview(result);
    }

    private Duration intervalDuration() {
        int amount = intervalSpinner.getValue();
        return switch (intervalUnit.getValue()) {
            case "minutes" -> Duration.ofMinutes(amount);
            case "days" -> Duration.ofDays(amount);
            default -> Duration.ofHours(amount);
        };
    }

    private void updatePreview(Schedule value) {
        if (!value.isAutomatic()) {
            preview.getItems().clear();
            return;
        }
        List<String> upcoming = BackupScheduler.preview(value, 5).stream()
                .map(PREVIEW_FORMAT::format)
                .toList();
        preview.getItems().setAll(upcoming);
    }

    /** The schedule currently described by the form. */
    public Schedule getSchedule() {
        return schedule.get();
    }

    public ObjectProperty<Schedule> scheduleProperty() {
        return schedule;
    }
}
