package client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class WsClient {

    private WebSocketClient client;
    private String serverUrl;

    public WsClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void connect() {
        try {
            client = new WebSocketClient(new URI(serverUrl)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    System.out.println("✅ WebSocket连接成功");
                }

                @Override
                public void onMessage(String message) {
                    System.out.println("📩 服务器: " + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("🔌 连接关闭: " + reason);
                }

                @Override
                public void onError(Exception ex) {
                    ex.printStackTrace();
                }
            };
            client.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void send(String json) {
        if (client != null && client.isOpen()) {
            client.send(json);
            System.out.println("📤 发送: " + json);
        } else {
            System.err.println("⚠️ 连接未打开，无法发送消息");
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }

    public void close() {
        if (client != null) {
            client.close();
        }
    }
}