package jieqi.server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public final class ServerMain {
    static final int DEFAULT_PORT = 8887;

    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Core.ServerConfig config = Core.ServerConfig.fromEnv();
        config.port = parsePort(args, config.port);
        JieqiWebSocketServer server = new JieqiWebSocketServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jieqi-server-shutdown"));

        server.start();
        System.out.println("Jieqi server listening on port " + config.port);
        Thread.currentThread().join();
    }

    static int parsePort(String[] args) {
        return parsePort(args, DEFAULT_PORT);
    }

    static int parsePort(String[] args, int defaultPort) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(args[0].trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + args[0]);
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid port: " + args[0], ex);
        }
    }

    static final class JieqiWebSocketServer extends WebSocketServer {
        private final ProtocolServer protocol;

        JieqiWebSocketServer(Core.ServerConfig config) {
            super(new InetSocketAddress(config.port));
            this.protocol = new ProtocolServer(config);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            protocol.onConnected(new WebSocketChannel(conn));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            protocol.onClosed(new WebSocketChannel(conn));
            System.out.println("websocket closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            protocol.onMessage(new WebSocketChannel(conn), message);
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
    }

    private record WebSocketChannel(WebSocket conn) implements Core.ClientChannel {
        @Override
        public void send(String json) {
            conn.send(json);
        }

        @Override
        public void closeConnection() {
            conn.close();
        }

        @Override
        public boolean isOpen() {
            return conn.isOpen();
        }

        @Override
        public String remote() {
            return ServerMain.remote(conn);
        }
    }

    private static String remote(WebSocket conn) {
        return conn == null || conn.getRemoteSocketAddress() == null
                ? "<unknown>"
                : conn.getRemoteSocketAddress().toString();
    }
}
