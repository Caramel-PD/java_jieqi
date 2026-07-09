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

public class LoginDialog extends AuthDialogBase {
    private final Stage stage;
    private final Main mainApp;
    private final String serverUrl;
    private final ServerEntryDialog entryDialog;

    private final TextField userIdField = new TextField();
    private final PasswordField passwordField = new PasswordField();

    public LoginDialog(Stage owner, Main mainApp, String serverUrl, ServerEntryDialog entryDialog) {
        this.mainApp = mainApp;
        this.serverUrl = serverUrl;
        this.entryDialog = entryDialog;

        stage = new Stage();
        stage.setTitle("登录");
        stage.initOwner(owner);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(24));

        grid.add(new Label("用户ID:"), 0, 0);
        grid.add(userIdField, 1, 0);
        grid.add(new Label("密码:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(new Label("服务器:"), 0, 2);
        grid.add(new Label(serverUrl), 1, 2);

        submitBtn = new Button("登录");
        Button backBtn = new Button("返回");
        submitBtn.setOnAction(e -> doLogin());
        backBtn.setOnAction(e -> goBack());

        statusLabel = new Label();
        grid.add(new HBox(10, submitBtn, backBtn), 1, 3);
        grid.add(statusLabel, 1, 4);

        stage.setScene(new Scene(grid, 380, 220));
    }

    private void doLogin() {
        String userId = userIdField.getText().trim();
        String password = passwordField.getText();
        if (userId.isEmpty() || password.isEmpty()) {
            statusLabel.setText("请输入用户ID和密码");
            return;
        }
        mainApp.connectAndLogin(serverUrl, userId, password, this);
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
