package jieqi.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-03 真实 WebSocket 联调测试。
 * <p>
 * 验证：服务器真的能被 WebSocket 客户端连上，且 login → match → ready → gameStart → move → moveResult
 * 全链路消息正确收发。使用随机可用端口，无需 E 的 GUI 客户端，用 CountDownLatch / 阻塞队列等待消息而非
 * 长时间 sleep。
 */
class WebSocketIntegrationTest {

    private ServerMain.JieqiWebSocketServer server;
    private static final int AVOID_PORT = 8887;

    private int port;
    private TestClient clientA;
    private TestClient clientB;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 使用随机可用端口，避免和 8887 冲突
        //    ServerSocket(0) 由 OS 从临时端口范围分配（Win 默认 49152+），
        //    不会分配到 8887；此处显式校验作为双保险。
        port = findRandomPortNot(AVOID_PORT);

        Core.ServerConfig config = new Core.ServerConfig();
        config.port = port;
        config.usersFile = null;            // 纯内存账户
        config.autoRegisterOnLogin = true;  // 未注册即登录视为注册
        config.turnTimeoutMs = 65_000;      // 长超时，避免干扰流程
        config.recordsDir = null;

        // 2. 启动真实 WebSocketServer
        server = new ServerMain.JieqiWebSocketServer(config);
        server.start();
        // 短暂等待服务器 socket 就绪（WebSocketServer.start() 异步）
        Thread.sleep(150);

        // 3. 创建两个脚本客户端（不依赖 E 的 GUI 客户端）
        clientA = new TestClient("A");
        clientB = new TestClient("B");
    }

    @AfterEach
    void tearDown() {
        closeQuietly(clientA);
        closeQuietly(clientB);
        if (server != null) {
            try {
                server.stop(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void completeLoginMatchReadyMoveFlow() throws Exception {
        // === 连接 ===
        connectWithRetry(clientA);
        connectWithRetry(clientB);
        assertTrue(clientA.connected.await(3, TimeUnit.SECONDS), "clientA should connect");
        assertTrue(clientB.connected.await(3, TimeUnit.SECONDS), "clientB should connect");

        // === A Login ===
        clientA.send("{\"messageType\":\"login\",\"userId\":\"playerA\",\"password\":\"passA\"}");
        JsonObject loginResultA = clientA.awaitMessageOfType("loginResult", 3, TimeUnit.SECONDS);
        assertNotNull(loginResultA, "A should receive loginResult");
        assertTrue(loginResultA.get("success").getAsBoolean(), "A login should succeed");

        // === B Login ===
        clientB.send("{\"messageType\":\"login\",\"userId\":\"playerB\",\"password\":\"passB\"}");
        JsonObject loginResultB = clientB.awaitMessageOfType("loginResult", 3, TimeUnit.SECONDS);
        assertNotNull(loginResultB, "B should receive loginResult");
        assertTrue(loginResultB.get("success").getAsBoolean(), "B login should succeed");

        // === A startMatch（先发先入等待队列，确保 A 为红方/先手） ===
        clientA.send("{\"messageType\":\"startMatch\"}");
        // 给服务器处理时间，确保 A 先进入匹配队列再让 B 匹配
        Thread.sleep(80);

        // === B startMatch ===
        clientB.send("{\"messageType\":\"startMatch\"}");

        // === 双方收到 matchSuccess ===
        JsonObject matchA = clientA.awaitMessageOfType("matchSuccess", 3, TimeUnit.SECONDS);
        JsonObject matchB = clientB.awaitMessageOfType("matchSuccess", 3, TimeUnit.SECONDS);
        assertNotNull(matchA, "A should receive matchSuccess");
        assertNotNull(matchB, "B should receive matchSuccess");
        assertEquals(matchA.get("roomId").getAsString(), matchB.get("roomId").getAsString(),
                "both players should be in the same room");
        assertEquals("playerB", matchA.get("opponentId").getAsString());
        assertEquals("playerA", matchB.get("opponentId").getAsString());

        // === A Ready ===
        clientA.send("{\"messageType\":\"ready\"}");

        // === B Ready ===
        clientB.send("{\"messageType\":\"ready\"}");

        // === 双方收到 gameStart（中间可能夹着 roomInfo，用类型匹配跳过） ===
        JsonObject gameStartA = clientA.awaitMessageOfType("gameStart", 3, TimeUnit.SECONDS);
        JsonObject gameStartB = clientB.awaitMessageOfType("gameStart", 3, TimeUnit.SECONDS);
        assertNotNull(gameStartA, "A should receive gameStart");
        assertNotNull(gameStartB, "B should receive gameStart");
        assertNotEquals(gameStartA.get("yourColor").getAsString(),
                gameStartB.get("yourColor").getAsString(),
                "two players must have different colors");
        assertTrue(gameStartA.has("initialBoard"), "gameStart should contain initialBoard");

        // === 确定先手方：A 先调 startMatch，必为红方（先手） ===
        boolean aIsRed = "red".equals(gameStartA.get("yourColor").getAsString());
        assertTrue(aIsRed, "A called startMatch first, so A must be red (first hand)");

        // === A（红方）走一步合法着法 b2→e2（翻子） ===
        clientA.send("{\"messageType\":\"move\",\"fromX\":\"b\",\"fromY\":2,\"toX\":\"e\",\"toY\":2,\"isFlip\":true}");

        // === 双方收到 moveResult ===
        JsonObject moveA = clientA.awaitMessageOfType("moveResult", 3, TimeUnit.SECONDS);
        JsonObject moveB = clientB.awaitMessageOfType("moveResult", 3, TimeUnit.SECONDS);
        assertNotNull(moveA, "A should receive moveResult");
        assertNotNull(moveB, "B should receive moveResult");
        assertTrue(moveA.get("valid").getAsBoolean(), "move should be valid");
        assertTrue(moveB.get("valid").getAsBoolean(), "move should be valid");
        assertTrue(moveA.getAsJsonObject("move").get("isFlip").getAsBoolean(),
                "first move of a hidden piece must be a flip");
        assertTrue(moveA.has("flipResult"), "moveResult should contain flipResult for a flip move");

        // === 连接正常关闭（无异常） ===
        closeQuietly(clientA);
        closeQuietly(clientB);
        assertTrue(clientA.closed.await(3, TimeUnit.SECONDS), "clientA should close cleanly");
        assertTrue(clientB.closed.await(3, TimeUnit.SECONDS), "clientB should close cleanly");
    }

    // ---- helpers ----

    /**
     * 从 OS 临时端口范围取随机可用端口，并显式排除 avoidPort。
     * ServerSocket(0) 本身不会分配到 8887（Win 临时端口从 49152 起），
     * 此方法作为双保险。
     */
    private static int findRandomPortNot(int avoidPort) throws Exception {
        for (int i = 0; i < 10; i++) {
            try (ServerSocket ss = new ServerSocket(0)) {
                int p = ss.getLocalPort();
                if (p != avoidPort) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("failed to find a port other than " + avoidPort);
    }

    private void connectWithRetry(TestClient client) throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
                client.connectBlocking();
                return;
            } catch (Exception e) {
                if (i == 4) throw e;
                Thread.sleep(50);
            }
        }
    }

    private static void closeQuietly(TestClient client) {
        if (client == null) return;
        try {
            if (client.isOpen()) {
                client.closeBlocking();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // ---- 脚本客户端（不依赖 E 的 GUI 客户端） ----

    private class TestClient extends WebSocketClient {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        TestClient(String name) throws Exception {
            super(new URI("ws://localhost:" + port));
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            connected.countDown();
        }

        @Override
        public void onMessage(String message) {
            messages.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("[TestClient] error: " + ex.getMessage());
        }

        /**
         * 用阻塞队列轮询等待特定 messageType 的消息。
         * 用 poll(100ms) 而非长时间 sleep 实现等待，超时后返回 null。
         */
        JsonObject awaitMessageOfType(String type, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (System.currentTimeMillis() < deadline) {
                String msg = messages.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    JsonObject json = JsonParser.parseString(msg).getAsJsonObject();
                    String msgType = json.get("messageType").getAsString();
                    if (type.equals(msgType)) {
                        return json;
                    }
                    // 跳过非目标类型消息（如 roomInfo），继续等待
                }
            }
            return null;
        }
    }
}
