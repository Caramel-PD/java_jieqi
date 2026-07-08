package client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class Main extends Application {
    private WsClient wsClient;
    private ChessBoard board;
    private GameStatusBar statusBar;
    private GameController controller;
    private AuthForm pendingAuthForm;
    private ServerEntryDialog entryDialog;

    @Override
    public void start(Stage primaryStage) {
        board = new ChessBoard(null);
        statusBar = new GameStatusBar();

        BorderPane root = new BorderPane();
        root.setTop(statusBar);
        root.setCenter(board);
        root.setStyle("-fx-background-color: #F7F0E3;");

        Scene scene = new Scene(root, 700, 880);
        primaryStage.setTitle("揭棋客户端");
        primaryStage.setScene(scene);
        primaryStage.show();

        entryDialog = new ServerEntryDialog(primaryStage, this);
        entryDialog.show();
    }

    public void connectAndLogin(String serverUrl, String userId, String password, AuthForm form) {
        connect(serverUrl, () -> MessageBuilder.buildLogin(userId, password), false, form);
    }

    public void connectAndRegister(String serverUrl, String userId, String password, String nickname, AuthForm form) {
        connect(serverUrl, () -> MessageBuilder.buildRegister(userId, password, nickname), true, form);
    }

    /** 用户从登录/注册返回时取消尚未完成的连接。 */
    public void cancelPendingConnect() {
        pendingAuthForm = null;
        if (controller != null) {
            controller.clearAuthForm();
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        controller = null;
    }

    private void connect(String serverUrl, java.util.function.Supplier<String> authMessage, boolean register, AuthForm form) {
        cancelPendingConnect();

        pendingAuthForm = form;
        form.onConnecting();

        wsClient = new WsClient(serverUrl);
        controller = new GameController(wsClient, board, statusBar);
        controller.setRegisterMode(register);
        controller.setAuthForm(form, this::onAuthFailed, register ? null : this::onAuthSuccess);
        if (register) {
            controller.setRegisterSuccessHandler(this::onRegisterSuccess);
        }
        board.setController(controller);
        statusBar.reset();

        wsClient.setOnConnectErrorCallback(msg -> onConnectFailed(msg));
        wsClient.setOnOpenCallback(() -> {
            if (pendingAuthForm != null) {
                wsClient.send(authMessage.get());
            }
        });
        wsClient.connect();
    }

    private void onAuthFailed(String message) {
        if (pendingAuthForm != null) {
            AuthForm form = pendingAuthForm;
            pendingAuthForm = null;
            form.onAuthFailed(message);
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        if (controller != null) {
            controller.clearAuthForm();
            controller = null;
        }
    }

    private void onConnectFailed(String message) {
        if (pendingAuthForm != null) {
            onAuthFailed(message);
        }
    }

    void onAuthSuccess() {
        pendingAuthForm = null;
        if (controller != null) {
            controller.clearAuthForm();
        }
    }

    private void onRegisterSuccess() {
        pendingAuthForm = null;
        if (controller != null) {
            controller.clearAuthForm();
            controller = null;
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        statusBar.reset();
        if (entryDialog != null) {
            entryDialog.showWithMessage("注册成功，请点击登录");
        }
    }

    @Override
    public void stop() {
        if (wsClient != null) {
            wsClient.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
