package client.app;

import client.controller.ApplicationController;
import client.audio.BackgroundMusicPlayer;
import client.network.MessageBuilder;
import client.network.WsClient;
import client.view.LoginView.AuthForm;
import client.view.GameView;
import client.view.GameStatusBar;
import client.view.LoginView;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class Main extends Application {
    private WsClient wsClient;
    private GameView board;
    private GameStatusBar statusBar;
    private ApplicationController controller;
    private final BackgroundMusicPlayer musicPlayer = new BackgroundMusicPlayer();
    private AuthForm pendingAuthForm;
    private LoginView entryDialog;
    private String lastServerUrl;
    private String lastUserId;
    private String lastPassword;
    private boolean autoStartMatchAfterLogin;

    @Override
    public void start(Stage primaryStage) {
        board = new GameView(null);
        statusBar = new GameStatusBar();
        statusBar.setSoundToggleHandler(musicPlayer::setEnabled);

        BorderPane root = new BorderPane();
        root.setTop(statusBar);
        root.setCenter(board);
        root.setStyle("-fx-background-color: #F7F0E3;");

        Scene scene = new Scene(root, 700, 880);
        primaryStage.setTitle("揭棋客户端");
        primaryStage.setScene(scene);
        primaryStage.show();

        entryDialog = new LoginView(primaryStage, this);
        entryDialog.show();
    }

    public void connectAndLogin(String serverUrl, String userId, String password, AuthForm form) {
        lastServerUrl = serverUrl;
        lastUserId = userId;
        lastPassword = password;
        connect(serverUrl, () -> MessageBuilder.buildLogin(userId, password), false, form);
    }

    public void connectAndRegister(String serverUrl, String userId, String password, String nickname, AuthForm form) {
        connect(serverUrl, () -> MessageBuilder.buildRegister(userId, password, nickname), true, form);
    }

    /** 用户从登�?注册返回时取消尚�?��成的连接�?*/
    public void cancelPendingConnect() {
        pendingAuthForm = null;
        if (controller != null) {
            controller.loginController().clearAuthForm();
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
        if (form != null) {
            form.onConnecting();
        }

        wsClient = new WsClient(serverUrl);
        controller = new ApplicationController(wsClient, board, statusBar);
        controller.loginController().setRegisterMode(register);
        controller.loginController().setAuthForm(form, this::onAuthFailed, register ? null : this::onAuthSuccess);
        controller.setPostGameNavigationHandlers(this::restartAndMatch, this::restartToLobby);
        controller.setLogoutHandler(this::logoutToEntry);
        if (register) {
            controller.loginController().setRegisterSuccessHandler(this::onRegisterSuccess);
        }
        board.setController(controller.gameController());
        statusBar.reset();

        wsClient.setOnConnectErrorCallback(msg -> onConnectFailed(msg));
        wsClient.setOnOpenCallback(() -> {
            wsClient.send(authMessage.get());
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
            controller.loginController().clearAuthForm();
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
        musicPlayer.play();
        if (controller != null) {
            controller.loginController().clearAuthForm();
            if (autoStartMatchAfterLogin) {
                autoStartMatchAfterLogin = false;
                controller.matchController().startMatch();
            }
        }
    }

    private void restartAndMatch() {
        reconnectAfterGame(true);
    }

    private void restartToLobby() {
        reconnectAfterGame(false);
    }

    private void logoutToEntry() {
        autoStartMatchAfterLogin = false;
        musicPlayer.stop();
        pendingAuthForm = null;
        if (controller != null) {
            controller.loginController().clearAuthForm();
            controller = null;
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        board.setController(null);
        board.setBoardVisible(false);
        board.setLobbyVisible(false);
        board.setMatchingVisible(false);
        board.setAiRecordsVisible(false);
        board.setRoomWaitingVisible(false);
        board.setPostGameActionsVisible(false);
        board.updatePregameControls(false, false, false, false, false);
        statusBar.reset();
        if (entryDialog != null) {
            entryDialog.showWithMessage("已退出登录");
        }
    }

    private void reconnectAfterGame(boolean startMatch) {
        if (lastServerUrl == null || lastUserId == null || lastPassword == null) {
            return;
        }
        autoStartMatchAfterLogin = startMatch;
        connect(lastServerUrl, () -> MessageBuilder.buildLogin(lastUserId, lastPassword), false, null);
    }

    private void onRegisterSuccess() {
        pendingAuthForm = null;
        if (controller != null) {
            controller.loginController().clearAuthForm();
            controller = null;
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        statusBar.reset();
        if (entryDialog != null) {
            entryDialog.showWithMessage("注册成功，请登录");
        }
    }

    @Override
    public void stop() {
        musicPlayer.dispose();
        if (wsClient != null) {
            wsClient.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

