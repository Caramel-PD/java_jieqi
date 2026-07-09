package client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/** 登录/注册对话框共用的连接与认证 UI 状态。 */
abstract class AuthDialogBase implements AuthForm {
    protected Button submitBtn;
    protected Label statusLabel;

    @Override
    public void onConnecting() {
        Platform.runLater(() -> {
            submitBtn.setDisable(true);
            statusLabel.setText("正在连接服务器…");
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
