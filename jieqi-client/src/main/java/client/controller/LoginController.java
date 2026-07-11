package client.controller;

import client.view.LoginView.AuthForm;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.function.Consumer;

public class LoginController {
    private final ApplicationController app;

    LoginController(ApplicationController app) {
        this.app = app;
    }

    public void setRegisterMode(boolean registerMode) {
        app.registerMode = registerMode;
    }

    public void setAuthForm(AuthForm form, Consumer<String> onFailed, Runnable onSuccess) {
        app.authForm = form;
        app.authFailedHandler = onFailed;
        app.authSuccessHandler = onSuccess;
    }

    public void setRegisterSuccessHandler(Runnable handler) {
        app.registerSuccessHandler = handler;
    }

    public void clearAuthForm() {
        app.authForm = null;
        app.authFailedHandler = null;
        app.authSuccessHandler = null;
        app.registerSuccessHandler = null;
    }

    void handleLoginResult(JsonNode root) {
        boolean success = root.path("success").asBoolean();
        String message = root.path("message").asText();
        Platform.runLater(() -> {
            if (success) {
                AuthForm form = app.authForm;
                if (app.registerMode) {
                    Runnable onRegisterSuccess = app.registerSuccessHandler;
                    clearAuthForm();
                    app.registerMode = false;
                    app.phase = ApplicationController.Phase.IDLE;
                    app.refreshStatusBar();
                    if (form != null) {
                        form.onAuthSuccess();
                    }
                    if (onRegisterSuccess != null) {
                        onRegisterSuccess.run();
                    }
                } else {
                    Runnable onSuccess = app.authSuccessHandler;
                    clearAuthForm();
                    app.currentUserId = root.path("userId").asText(app.currentUserId);
                    app.loggedIn = true;
                    app.phase = ApplicationController.Phase.IDLE;
                    app.refreshStatusBar();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    if (form != null) {
                        form.onAuthSuccess();
                    }
                }
            } else {
                boolean wasRegister = app.registerMode;
                app.registerMode = false;
                app.loggedIn = false;
                app.currentUserId = null;
                app.phase = ApplicationController.Phase.IDLE;
                app.refreshStatusBar();
                notifyAuthFailed(formatAuthError(wasRegister, message));
            }
        });
    }

    public void requestLogout() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("退出登录");
        confirm.setHeaderText(null);
        confirm.setContentText("确定退出登录吗");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                app.stopReplayPlayback();
                app.stopTurnTimer();
                if (app.logoutHandler != null) {
                    app.logoutHandler.run();
                }
            }
        });
    }

    private String formatAuthError(boolean register, String serverMessage) {
        if (register) {
            if (serverMessage == null || serverMessage.isBlank()
                    || "register failed".equalsIgnoreCase(serverMessage.trim())) {
                return "注册失败：用户已存在";
            }
            return "注册失败：" + serverMessage;
        }
        if (serverMessage == null || serverMessage.isBlank()
                || "invalid userId or password".equalsIgnoreCase(serverMessage.trim())) {
            return "登录失败：用户ID或密码错误";
        }
        return "登录失败：" + serverMessage;
    }

    private void notifyAuthFailed(String message) {
        Consumer<String> handler = app.authFailedHandler;
        clearAuthForm();
        if (handler != null) {
            handler.accept(message);
        }
    }
}
