package client.view;

import client.app.Main;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class LoginView {
    private final Stage owner;
    private final Stage stage;
    private final TextField serverField = new TextField("ws://10.122.248.221:8887");
    private final Label statusLabel = new Label();

    public LoginView(Stage owner, Main mainApp) {
        this.owner = owner;
        stage = new Stage();
        stage.setTitle("揭棋客户端 - 连接");
        stage.initOwner(owner);
        stage.setUserData(mainApp);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(24));

        grid.add(new Label("服务器:"), 0, 0);
        grid.add(serverField, 1, 0);

        Button loginBtn = new Button("登录");
        Button registerBtn = new Button("注册");
        loginBtn.setPrefWidth(100);
        registerBtn.setPrefWidth(100);
        loginBtn.setOnAction(e -> openLogin());
        registerBtn.setOnAction(e -> openRegister());

        VBox actions = new VBox(10, loginBtn, registerBtn);
        grid.add(actions, 1, 1);
        grid.add(statusLabel, 1, 2);

        stage.setScene(new Scene(grid, 400, 200));
        stage.setOnCloseRequest(e -> owner.close());
    }

    public void show() {
        statusLabel.setText("");
        stage.show();
    }

    public void showWithMessage(String message) {
        statusLabel.setText(message == null ? "" : message);
        stage.show();
    }

    private String serverUrl() {
        return serverField.getText().trim();
    }

    private void openLogin() {
        if (serverUrl().isEmpty()) {
            statusLabel.setText("请输入服务器地址");
            return;
        }
        stage.hide();
        LoginDialog dialog = new LoginDialog(owner, getMain(), serverUrl(), this);
        dialog.show();
    }

    private void openRegister() {
        if (serverUrl().isEmpty()) {
            statusLabel.setText("请输入服务器地址");
            return;
        }
        stage.hide();
        RegisterDialog dialog = new RegisterDialog(owner, getMain(), serverUrl(), this);
        dialog.show();
    }

    void showAgain() {
        stage.show();
    }

    private Main getMain() {
        return (Main) stage.getUserData();
    }

/** 登录/注册表单�?Main 之间的�?证状态回调�??*/
public interface AuthForm {
    void onConnecting();

    void onAuthSuccess();

    void onAuthFailed(String message);
}

/** 登录/注册对话框共用的连接与�?�?UI 状�?��??*/
private static abstract class AuthDialogBase implements AuthForm {
    protected Button submitBtn;
    protected Label statusLabel;

    @Override
    public void onConnecting() {
        Platform.runLater(() -> {
            submitBtn.setDisable(true);
            statusLabel.setText("正在连接服务器...");
        });
    }

    @Override
    public void onAuthSuccess() {
        Platform.runLater(() -> stage().close());
    }

    @Override
    public void onAuthFailed(String message) {
        Platform.runLater(() -> {
            submitBtn.setDisable(false);
            statusLabel.setText(message == null || message.isBlank() ? "操作失败" : message);
        });
    }

    protected abstract javafx.stage.Stage stage();
}

private static class LoginDialog extends AuthDialogBase {
    private final Stage stage;
    private final Main mainApp;
    private final String serverUrl;
    private final LoginView entryDialog;

    private final TextField userIdField = new TextField();
    private final PasswordField passwordField = new PasswordField();

    public LoginDialog(Stage owner, Main mainApp, String serverUrl, LoginView entryDialog) {
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

private static class RegisterDialog extends AuthDialogBase {
    private final Stage stage;
    private final Main mainApp;
    private final String serverUrl;
    private final LoginView entryDialog;

    private final TextField userIdField = new TextField();
    private final TextField nicknameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField confirmPasswordField = new PasswordField();

    public RegisterDialog(Stage owner, Main mainApp, String serverUrl, LoginView entryDialog) {
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

}
