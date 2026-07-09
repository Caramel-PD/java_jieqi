package client;

/** 登录/注册表单与 Main 之间的认证状态回调。 */
public interface AuthForm {
    void onConnecting();

    void onAuthSuccess();

    void onAuthFailed(String message);
}
