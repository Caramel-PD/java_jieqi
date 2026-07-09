package client;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class RegisterDialog extends AuthDialogBase {
    private final Stage stage;
    private final Main mainApp;
    private final String serverUrl;
    private final ServerEntryDialog entryDialog;

    private final TextField userIdField = new TextField();
    private final TextField nicknameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField confirmPasswordField = new PasswordField();

    public RegisterDialog(Stage owner, Main mainApp, String serverUrl, ServerEntryDialog entryDialog) {
        this.mainApp = mainApp;
        this.serverUrl = serverUrl;
        this.entryDialog = entryDialog;

        stage = new Stage();
        stage.setTitle("注册");
        stage.initOwner(owner);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(24));

        grid.add(new Label("用户ID:"), 0, 0);
        grid.add(userIdField, 1, 0);
        grid.add(new Label("昵称:"), 0, 1);
        grid.add(nicknameField, 1, 1);
        grid.add(new Label("密码:"), 0, 2);
        grid.add(passwordField, 1, 2);
        grid.add(new Label("确认密码:"), 0, 3);
        grid.add(confirmPasswordField, 1, 3);
        grid.add(new Label("服务器:"), 0, 4);
        grid.add(new Label(serverUrl), 1, 4);

        submitBtn = new Button("注册");
        Button backBtn = new Button("返回");
        submitBtn.setOnAction(e -> doRegister());
        backBtn.setOnAction(e -> goBack());

        statusLabel = new Label();
        grid.add(new HBox(10, submitBtn, backBtn), 1, 5);
        grid.add(statusLabel, 1, 6);

        stage.setScene(new Scene(grid, 400, 300));
    }

    private void doRegister() {
        String userId = userIdField.getText().trim();
        String nickname = nicknameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();

        if (userId.isEmpty() || password.isEmpty()) {
            statusLabel.setText("请输入用户ID和密码");
            return;
        }
        if (nickname.isEmpty()) {
            nickname = userId;
        }
        if (!password.equals(confirm)) {
            statusLabel.setText("两次输入的密码不一致");
            return;
        }

        mainApp.connectAndRegister(serverUrl, userId, password, nickname, this);
    }

    private void goBack() {
        mainApp.cancelPendingConnect();
        stage.close();
        entryDialog.showAgain();
    }

    public void show() {
        submitBtn.setDisable(false);
        statusLabel.setText("");
        stage.show();
    }

    @Override
    protected Stage stage() {
        return stage;
    }
}
