package client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;

public class WsClient {
    private static final Logger LOG = LoggerFactory.getLogger(WsClient.class);
    private WebSocketClient client;
    private final String serverUrl;
    private Consumer<String> messageHandler;
    private Runnable onOpenCallback;
    private Consumer<String> onConnectErrorCallback;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean opened;

    public WsClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void setMessageHandler(Consumer<String> handler) {
        this.messageHandler = handler;
    }

    public void setOnOpenCallback(Runnable callback) {
        this.onOpenCallback = callback;
    }

    public void setOnConnectErrorCallback(Consumer<String> callback) {
        this.onConnectErrorCallback = callback;
    }

    public void connect() {
        opened = false;
        try {
            client = new WebSocketClient(new URI(serverUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    LOG.info("WebSocket 连接成功");
                    opened = true;
                    startPing();
                    if (onOpenCallback != null) {
                        onOpenCallback.run();
                    }
                }

                @Override
                public void onMessage(String message) {
                    LOG.info("收到: {}", message);
                    if (messageHandler != null) {
                        messageHandler.accept(message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    LOG.info("连接关闭: code={}, reason={}", code, reason);
                    stopPing();
                    if (!opened && onConnectErrorCallback != null) {
                        String msg = reason == null || reason.isBlank()
                                ? "无法连接服务器 (code " + code + ")"
                                : reason;
                        Platform.runLater(() -> onConnectErrorCallback.accept(msg));
                    }
                }

                @Override
                public void onError(Exception ex) {
                    LOG.error("WebSocket错误", ex);
                    if (onConnectErrorCallback != null) {
                        String msg = ex.getMessage() == null ? "连接服务器失败" : ex.getMessage();
                        Platform.runLater(() -> onConnectErrorCallback.accept(msg));
                    }
                }
            };
            client.connect();
        } catch (Exception e) {
            LOG.error("连接失败", e);
            if (onConnectErrorCallback != null) {
                Platform.runLater(() -> onConnectErrorCallback.accept("连接地址无效: " + e.getMessage()));
            }
        }
    }

    private void startPing() {
        if (pingScheduler == null || pingScheduler.isShutdown()) {
            pingScheduler = Executors.newSingleThreadScheduledExecutor();
            pingScheduler.scheduleAtFixedRate(() -> {
                if (client != null && client.isOpen()) {
                    send(MessageBuilder.buildPing());
                }
            }, 5, 10, TimeUnit.SECONDS); // 每10秒发送一次ping
        }
    }

    private void stopPing() {
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
        }
    }

    public void send(String json) {
        if (client != null && client.isOpen()) {
            client.send(json);
            LOG.debug("发送: {}", json);
        } else {
            LOG.warn("连接未打开，无法发送");
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }

    public void close() {
        stopPing();
        if (client != null) client.close();
    }
}