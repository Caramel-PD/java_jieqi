package jieqi.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

/**
 * Minimal B-01 WebSocket entry point: start the server and answer ping/pong.
 */
public final class ServerMain {
    static final int DEFAULT_PORT = 8887;

    private ServerMain() {}

    public static void main(String[] args) throws InterruptedException {
        int port = parsePort(args);
        PingPongServer server = new PingPongServer(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jieqi-server-shutdown"));

        server.start();
        System.out.println("Jieqi server listening on port " + port);
        Thread.currentThread().join();
    }

    static int parsePort(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(args[0].trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + args[0]);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + args[0], e);
        }
    }

    static String handleTextMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("messageType") ? json.get("messageType").getAsString() : "";
            if ("ping".equalsIgnoreCase(type) && json.has("timestamp")) {
                return Messages.pong(json.get("timestamp").getAsLong());
            }
            return unsupportedMessage();
        } catch (Exception e) {
            return unsupportedMessage();
        }
    }

    private static String unsupportedMessage() {
        JsonObject error = new JsonObject();
        error.addProperty("messageType", "error");
        error.addProperty("message", "unsupported message");
        return error.toString();
    }

    static final class PingPongServer extends WebSocketServer {
        PingPongServer(int port) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("client connected: " + remote(conn));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("client closed: " + remote(conn) + ", code=" + code + ", reason=" + reason);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            System.out.println("message from " + remote(conn) + ": " + message);
            conn.send(handleTextMessage(message));
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("websocket error from " + remote(conn) + ": " + ex.getMessage());
            ex.printStackTrace(System.err);
        }

        @Override
        public void onStart() {
            System.out.println("websocket server started: " + getAddress());
        }

        private static String remote(WebSocket conn) {
            if (conn == null || conn.getRemoteSocketAddress() == null) {
                return "<unknown>";
            }
            return conn.getRemoteSocketAddress().toString();
        }
    }
}
