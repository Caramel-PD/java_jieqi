package client.network;

import javafx.application.Platform;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class WsClient {
    private static final Logger LOG = LoggerFactory.getLogger(WsClient.class);
    private WebSocketClient client;
    private final String serverUrl;
    private Consumer<String> messageHandler;
    private Runnable onOpenCallback;
    private Consumer<String> onConnectErrorCallback;
    private Consumer<String> onDisconnectedCallback;
    private ScheduledExecutorService pingScheduler;
    private volatile boolean opened;
    private volatile boolean intentionalClose;
    private volatile boolean terminalEventDelivered;

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

    public void setOnDisconnectedCallback(Consumer<String> callback) {
        this.onDisconnectedCallback = callback;
    }

    public void connect() {
        opened = false;
        intentionalClose = false;
        terminalEventDelivered = false;
        try {
            client = new WebSocketClient(new URI(serverUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    LOG.info("WebSocket connected");
                    opened = true;
                    startPing();
                    if (onOpenCallback != null) {
                        onOpenCallback.run();
                    }
                }

                @Override
                public void onMessage(String message) {
                    LOG.info("Received: {}", message);
                    if (messageHandler != null) {
                        messageHandler.accept(message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    LOG.info("Connection closed: code={}, reason={}", code, reason);
                    stopPing();
                    boolean wasOpened = opened;
                    opened = false;
                    String message = reason == null || reason.isBlank()
                            ? "Connection closed (code " + code + ")"
                            : reason;
                    deliverTerminalEvent(wasOpened, message);
                }

                @Override
                public void onError(Exception ex) {
                    LOG.error("WebSocket error", ex);
                    String message = ex.getMessage() == null ? "Unable to connect to server" : ex.getMessage();
                    deliverTerminalEvent(opened, message);
                }
            };
            client.connect();
        } catch (Exception e) {
            LOG.error("Connection failed", e);
            deliverTerminalEvent(false, "Invalid server address: " + e.getMessage());
        }
    }

    private synchronized void deliverTerminalEvent(boolean wasOpened, String message) {
        if (intentionalClose || terminalEventDelivered) {
            return;
        }
        terminalEventDelivered = true;
        Consumer<String> callback = wasOpened ? onDisconnectedCallback : onConnectErrorCallback;
        if (callback != null) {
            Platform.runLater(() -> callback.accept(message));
        }
    }

    private void startPing() {
        if (pingScheduler == null || pingScheduler.isShutdown()) {
            pingScheduler = Executors.newSingleThreadScheduledExecutor();
            pingScheduler.scheduleAtFixedRate(() -> {
                if (client != null && client.isOpen()) {
                    send(MessageBuilder.buildPing());
                }
            }, 5, 10, TimeUnit.SECONDS);
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
            LOG.debug("Sent: {}", json);
        } else {
            LOG.warn("Connection is not open; message was not sent");
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }

    public void close() {
        intentionalClose = true;
        opened = false;
        stopPing();
        if (client != null) {
            client.close();
        }
    }
}
