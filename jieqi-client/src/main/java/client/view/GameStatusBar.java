package client.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.function.Consumer;
public class GameStatusBar extends VBox {
    public record HistoryRecord(String recordId, String summary) {
    }

    private final Label turnLabel = new Label("当前回合：等待连接");
    private final Label roomLabel = new Label("暂无房间信息");
    private final Label replayRoomLabel = new Label("");
    private final Label timeoutLabel = new Label("剩余时间：-");
    private final Button detailToggleButton = new Button("▼");
    private final Button drawButton = new Button("和棋");
    private final Button resignButton = new Button("投降");
    private final HBox detailBox = new HBox(12);
    private final Button replayBackButton = new Button("返回");

    private final Button replayPlayButton = new Button("▶");
    private final ComboBox<String> replaySpeedBox = new ComboBox<>();
    private final Label replayStepLabel = new Label("0/0");
    private final Button replayPrevButton = new Button("上一步");
    private final Button replayNextButton = new Button("下一步");
    private final Button replayStartButton = new Button("回到开头");
    private final Button replayEndButton = new Button("跳到结尾");

    private final Button logoutButton = new Button("退出登录");
    private final Button statusSoundButton = new Button("🔊");
    private final Button historyButton = new Button("历史棋谱");
    private final Button historyRefreshButton = new Button("刷新");
    private final VBox settingsBox = new VBox(10);
    private final HBox settingsActions = new HBox(12);
    private final VBox historyListBox = new VBox(8);
    private final ScrollPane historyScrollPane = new ScrollPane(historyListBox);
    private final Label historyMessageLabel = new Label("");

    private boolean detailExpanded = false;
    private boolean lobbySettingsMode = false;
    private boolean soundEnabled = true;
    private boolean historyVisible = false;

    public GameStatusBar() {
        setSpacing(0);
        setStyle("-fx-background-color: #F5EDE0; -fx-border-color: #C9A86C; -fx-border-width: 0 0 2 0;");

        turnLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        timeoutLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
            timeoutLabel.setText("剩余时间：-");
        setTimeoutVisible(false);

        detailToggleButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        detailToggleButton.setFocusTraversable(false);
        detailToggleButton.setMinWidth(34);
        detailToggleButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #5D4037;"
                + " -fx-border-color: #C9A86C; -fx-border-radius: 6; -fx-background-radius: 6;");

        replayBackButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        replayBackButton.setFocusTraversable(false);
        replayBackButton.setVisible(false);
        replayBackButton.setManaged(false);
        replayBackButton.setStyle(actionButtonStyle());

        roomLabel.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 14));
        roomLabel.setTextFill(Color.web("#5D4037"));
        roomLabel.setMaxWidth(Double.MAX_VALUE);

        replayRoomLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        replayRoomLabel.setTextFill(Color.web("#5D4037"));
        replayRoomLabel.setMaxWidth(Double.MAX_VALUE);
        replayRoomLabel.setVisible(false);
        replayRoomLabel.setManaged(false);

        drawButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        drawButton.setDisable(true);
        resignButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        resignButton.setDisable(true);
        setupReplayControls();
        setupSettingsControls();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(replayRoomLabel, Priority.ALWAYS);

        HBox mainRow = new HBox(20, turnLabel, timeoutLabel, replayRoomLabel, spacer,
                replayBackButton, statusSoundButton, detailToggleButton);
        mainRow.setPadding(new Insets(12, 20, 12, 20));
        mainRow.setAlignment(Pos.CENTER_LEFT);

        HBox.setHgrow(roomLabel, Priority.ALWAYS);
        detailBox.setPadding(new Insets(0, 20, 12, 20));
        detailBox.setAlignment(Pos.CENTER_LEFT);
        detailBox.setStyle("-fx-background-color: rgba(255,250,240,0.92);"
                + " -fx-border-color: #DDC28E; -fx-border-width: 1 0 0 0;");
        showNormalDetailControls();

        detailToggleButton.setOnAction(e -> setDetailExpanded(!detailExpanded));
        setDetailExpanded(false);

        getChildren().addAll(mainRow, detailBox);
    }

    public void updateRoom(String text) {
        if (text == null || text.isBlank()) {
            roomLabel.setText("暂无房间信息");
            replayRoomLabel.setText("");
        } else {
            roomLabel.setText(text);
            replayRoomLabel.setText(text);
        }
    }

    public void setResignHandler(Runnable handler) {
        resignButton.setOnAction(e -> handler.run());
    }

    public void setDrawHandler(Runnable handler) {
        drawButton.setOnAction(e -> handler.run());
    }

    public void setReplayPlayHandler(Runnable handler) {
        replayPlayButton.setOnAction(e -> handler.run());
    }

    public void setReplayPrevHandler(Runnable handler) {
        replayPrevButton.setOnAction(e -> handler.run());
    }

    public void setReplayNextHandler(Runnable handler) {
        replayNextButton.setOnAction(e -> handler.run());
    }

    public void setReplayStartHandler(Runnable handler) {
        replayStartButton.setOnAction(e -> handler.run());
    }

    public void setReplayEndHandler(Runnable handler) {
        replayEndButton.setOnAction(e -> handler.run());
    }

    public void setReplayBackHandler(Runnable handler) {
        replayBackButton.setOnAction(e -> {
            if (handler != null) {
                handler.run();
            }
        });
    }

    public void setReplaySpeedHandler(Consumer<Double> handler) {
        replaySpeedBox.setOnAction(e -> {
            if (handler != null) {
                handler.accept(parseSpeed(replaySpeedBox.getValue()));
            }
        });
    }

    public void setLogoutHandler(Runnable handler) {
        logoutButton.setOnAction(e -> {
            if (handler != null) {
                handler.run();
            }
        });
    }

    public void setHistoryHandler(Runnable handler) {
        historyButton.setOnAction(e -> {
            if (historyVisible) {
                hideHistoryRecords();
                return;
            }
            refreshHistoryRecords(handler);
        });
        historyRefreshButton.setOnAction(e -> refreshHistoryRecords(handler));
    }

    public void setSoundToggleHandler(Consumer<Boolean> handler) {
        statusSoundButton.setOnAction(e -> toggleSound(handler));
    }

    public void showHistoryRecords(List<HistoryRecord> records, Consumer<String> openHandler) {
        setHistoryVisible(true);
        historyListBox.getChildren().clear();
        if (records == null || records.isEmpty()) {
            historyMessageLabel.setText("暂无历史棋局");
            return;
        }
        historyMessageLabel.setText("");
        for (HistoryRecord record : records) {
            Button button = new Button(record.summary());
            button.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 13));
            button.setFocusTraversable(false);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setWrapText(true);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setStyle("-fx-background-color: rgba(250,240,220,0.92); -fx-text-fill: #2A1D14;"
                    + " -fx-background-radius: 6; -fx-border-color: rgba(201,168,108,0.82);"
                    + " -fx-border-radius: 6; -fx-border-width: 1; -fx-padding: 6 10 6 10;");
            button.setOnAction(e -> {
                if (openHandler != null) {
                    openHandler.accept(record.recordId());
                }
            });
            historyListBox.getChildren().add(button);
        }
    }

    public void showHistoryMessage(String message) {
        historyListBox.getChildren().clear();
        historyMessageLabel.setText(message == null ? "" : message);
        setHistoryVisible(message != null && !message.isBlank());
    }

    public void setDrawEnabled(boolean enabled) {
        drawButton.setDisable(!enabled);
    }

    public void setResignEnabled(boolean enabled) {
        resignButton.setDisable(!enabled);
    }

    public void updateTurn(String text) {
        turnLabel.setText(text);
    }

    public void setTimeoutVisible(boolean visible) {
        timeoutLabel.setVisible(visible);
        timeoutLabel.setManaged(visible);
    }

    public void setLobbySettingsMode(boolean enabled) {
        lobbySettingsMode = enabled;
        if (enabled) {
            showSettingsControls();
        } else if (!detailBox.getChildren().contains(roomLabel)) {
            showNormalDetailControls();
        }
        updateDetailIcon();
    }

    public void setReplayMode(boolean enabled) {
        replayBackButton.setVisible(enabled);
        replayBackButton.setManaged(enabled);
        replayRoomLabel.setVisible(false);
        replayRoomLabel.setManaged(false);
        if (enabled) {
            setTimeoutVisible(false);
        }
        if (enabled) {
            lobbySettingsMode = false;
            showReplayDetailControls();
            setDetailExpanded(true);
        } else {
            showNormalDetailControls();
            setReplayPlaying(false);
        }
        updateDetailIcon();
    }

    public void setReplayPlaying(boolean playing) {
        replayPlayButton.setText(playing ? "⏸" : "▶");
    }

    public void updateReplayStep(int current, int total) {
        replayStepLabel.setText(current + "/" + total);
    }

    public void updateTimeout(int seconds) {
        if (seconds < 0) {
            timeoutLabel.setText("剩余时间：-");
            timeoutLabel.setTextFill(Color.DARKRED);
            return;
        }
        timeoutLabel.setText("剩余时间：" + seconds + " 秒");
        if (seconds <= 10) {
            timeoutLabel.setTextFill(Color.RED);
        } else if (seconds <= 20) {
            timeoutLabel.setTextFill(Color.ORANGERED);
        } else {
            timeoutLabel.setTextFill(Color.DARKRED);
        }
    }

    public void reset() {
        turnLabel.setText("当前回合：等待连接");
        updateRoom("");
        updateTimeout(-1);
        setDrawEnabled(false);
        setResignEnabled(false);
        setLobbySettingsMode(false);
        setReplayMode(false);
        updateReplayStep(0, 0);
        setDetailExpanded(false);
        showHistoryMessage("");
    }

    private void setupReplayControls() {
        for (Button button : new Button[]{replayPlayButton, replayPrevButton, replayNextButton,
                replayStartButton, replayEndButton}) {
            button.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
            button.setFocusTraversable(false);
        }
        replaySpeedBox.getItems().addAll("0.5x", "1x", "2x", "4x");
        replaySpeedBox.setValue("1x");
        replaySpeedBox.setPrefWidth(82);
        replaySpeedBox.setFocusTraversable(false);
        replayStepLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        replayStepLabel.setTextFill(Color.web("#5D4037"));
    }

    private void setupSettingsControls() {
        for (Button button : new Button[]{logoutButton, statusSoundButton, historyButton, historyRefreshButton}) {
            button.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
            button.setFocusTraversable(false);
            button.setPrefHeight(38);
            button.setStyle(actionButtonStyle());
        }
        logoutButton.setPrefWidth(120);
        statusSoundButton.setPrefWidth(48);
        historyButton.setPrefWidth(120);
        historyRefreshButton.setPrefWidth(90);
        settingsActions.setAlignment(Pos.CENTER_LEFT);
        settingsActions.getChildren().addAll(logoutButton, historyButton, historyRefreshButton);

        historyMessageLabel.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 13));
        historyMessageLabel.setTextFill(Color.web("#5D4037"));
        historyScrollPane.setFitToWidth(true);
        historyScrollPane.setPrefViewportWidth(740);
        historyScrollPane.setMaxWidth(760);
        historyScrollPane.setPrefViewportHeight(170);
        historyScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        historyScrollPane.setVisible(false);
        historyScrollPane.setManaged(false);
        historyListBox.setPadding(new Insets(4, 0, 4, 0));

        settingsBox.setAlignment(Pos.CENTER_LEFT);
        settingsBox.setFillWidth(true);
        settingsBox.setMaxWidth(780);
        settingsBox.getChildren().addAll(settingsActions, historyMessageLabel, historyScrollPane);
    }

    private void showNormalDetailControls() {
        detailBox.setMaxWidth(Double.MAX_VALUE);
        detailBox.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(detailBox, null);
        detailBox.getChildren().setAll(roomLabel, drawButton, resignButton);
    }

    private void showReplayDetailControls() {
        detailBox.setMaxWidth(Double.MAX_VALUE);
        detailBox.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(detailBox, null);
        detailBox.getChildren().setAll(replayPlayButton, replaySpeedBox, replayStepLabel,
                replayPrevButton, replayNextButton, replayStartButton, replayEndButton);
    }

    private void showSettingsControls() {
        detailBox.setMaxWidth(820);
        detailBox.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(detailBox, new Insets(0, 0, 0, 20));
        detailBox.getChildren().setAll(settingsBox);
    }

    private void setHistoryVisible(boolean visible) {
        historyVisible = visible;
        historyScrollPane.setVisible(visible);
        historyScrollPane.setManaged(visible);
    }

    private void hideHistoryRecords() {
        historyListBox.getChildren().clear();
        historyMessageLabel.setText("");
        setHistoryVisible(false);
    }

    private void refreshHistoryRecords(Runnable handler) {
        historyMessageLabel.setText("正在加载历史棋谱……");
        setHistoryVisible(true);
        historyListBox.getChildren().clear();
        if (handler != null) {
            handler.run();
        }
    }

    private double parseSpeed(String value) {
        if (value == null || value.isBlank()) {
            return 1.0;
        }
        try {
            return Double.parseDouble(value.replace("x", ""));
        } catch (NumberFormatException ex) {
            return 1.0;
        }
    }

    private void setDetailExpanded(boolean expanded) {
        detailExpanded = expanded;
        detailBox.setVisible(expanded);
        detailBox.setManaged(expanded);
        updateDetailIcon();
    }

    private void updateDetailIcon() {
        if (lobbySettingsMode) {
            detailToggleButton.setText("⚙");
        } else {
            detailToggleButton.setText(detailExpanded ? "▲" : "▼");
        }
    }

    private void toggleSound(Consumer<Boolean> handler) {
        soundEnabled = !soundEnabled;
        String icon = soundEnabled ? "🔊" : "🔇";
        statusSoundButton.setText(icon);
        if (handler != null) {
            handler.accept(soundEnabled);
        }
    }

    private String actionButtonStyle() {
        return "-fx-background-color: rgba(67,45,30,0.90); -fx-text-fill: #F4E5C5;"
                + " -fx-background-radius: 6; -fx-border-color: #C9A86C;"
                + " -fx-border-radius: 6; -fx-border-width: 1; -fx-padding: 6 14 6 14;";
    }
}
