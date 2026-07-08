package client;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

public class LoginDialog {
    private final Stage stage;
    private final TextField userIdField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField serverField = new TextField("ws://localhost:8887");
    private final Button connectBtn = new Button("连接");
    private final Label statusLabel = new Label();

    public LoginDialog(Stage owner) {
        stage = new Stage();
        stage.setTitle("登录 - 揭棋客户端");
        stage.initOwner(owner);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        grid.add(new Label("用户ID:"), 0, 0);
        grid.add(userIdField, 1, 0);
        grid.add(new Label("密码:"), 0, 1);
        grid.add(passwordField, 1, 1);
        grid.add(new Label("服务器:"), 0, 2);
        grid.add(serverField, 1, 2);
        grid.add(connectBtn, 1, 3);
        grid.add(statusLabel, 1, 4);

        connectBtn.setOnAction(e -> doLogin());

        Scene scene = new Scene(grid, 350, 200);
        stage.setScene(scene);
    }

    private void doLogin() {
        String userId = userIdField.getText().trim();
        String password = passwordField.getText().trim();
        String serverUrl = serverField.getText().trim();
        if (userId.isEmpty() || password.isEmpty()) {
            statusLabel.setText("请输入账号和密码");
            return;
        }

        // 传递到主控制器，开始连接
        Main mainApp = (Main) stage.getUserData(); // 可设全局
        if (mainApp != null) {
            mainApp.connectAndLogin(serverUrl, userId, password);
            stage.close();
        }
    }

    public void show() {
        stage.show();
    }

    // 添加 getStage 方法
    public Stage getStage() {
        return stage;
    }
}