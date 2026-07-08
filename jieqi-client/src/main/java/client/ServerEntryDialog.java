package client;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class ServerEntryDialog {
    private final Stage owner;
    private final Stage stage;
    private final TextField serverField = new TextField("ws://10.122.248.221:8887");
    private final Label statusLabel = new Label();

    public ServerEntryDialog(Stage owner, Main mainApp) {
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

    void showWithMessage(String message) {
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
}
