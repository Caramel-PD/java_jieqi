package jieqi.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-03 真实 WebSocket 联调测试。
 * <p>
 * 烟测覆盖：ping/pong → Login → match → Ready → gameStart → move → Resign → gameOver。
 * 使用随机可用端口，无需 E 的 GUI 客户端，用 CountDownLatch / BlockingQueue 等待消息。
 */
class WebSocketIntegrationTest {

    private static final int AVOID_PORT = 8887;

    @TempDir
    Path recordsDir;

    private ServerMain.JieqiWebSocketServer server;
    private int port;
    private TestClient clientA;
    private TestClient clientB;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 使用随机可用端口，避免和 8887 冲突
        port = findRandomPortNot(AVOID_PORT);

        Core.ServerConfig config = new Core.ServerConfig();
        config.port = port;
        config.usersFile = null;
        config.autoRegisterOnLogin = true;
        config.turnTimeoutMs = 65_000;
        // 该集成烟测验证旧的 Ready 后立即开局链路，显式关闭先手窗口避免测试等待真实 10 秒。
        config.firstHandWindowMs = 0;
        config.recordsDir = recordsDir;  // 临时目录，避免写棋谱时报错

        // 2. 启动真实 WebSocketServer
        server = new ServerMain.JieqiWebSocketServer(config);
        server.start();

        // 3. 等服务器端口真正就绪再创建客户端（Socket 直连探测，最多 3s）
        waitForServerReady();

        // 4. 创建两个脚本客户端（不依赖 E 的 GUI 客户端）
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
    void completePingLoginMatchReadyMoveResignFlow() throws Exception {
        // ============================================================
        // === 连接（connectBlocking 重试等待 WebSocket 握手就绪）=====
        // ============================================================
        connectWithRetry(clientA);
        connectWithRetry(clientB);
        assertTrue(clientA.connected.await(3, TimeUnit.SECONDS), "clientA should connect");
        assertTrue(clientB.connected.await(3, TimeUnit.SECONDS), "clientB should connect");

        // ============================================================
        // === 1. ping/pong — 在 Login 之前验证连接可通信 ==============
        // ============================================================
        clientA.send("{\"messageType\":\"ping\",\"timestamp\":123456}");
        JsonObject pong = clientA.awaitMessageOfType("pong", 3, TimeUnit.SECONDS);
        assertNotNull(pong, "A should receive pong");
        assertEquals(123456, pong.get("timestamp").getAsLong(),
                "pong timestamp must match ping timestamp");

        // ============================================================
        // === 2. A Login =============================================
        // ============================================================
        clientA.send("{\"messageType\":\"Login\",\"userId\":\"playerA\",\"password\":\"passA\"}");
        JsonObject loginResultA = clientA.awaitMessageOfType("loginResult", 3, TimeUnit.SECONDS);
        assertNotNull(loginResultA, "A should receive loginResult");
        assertTrue(loginResultA.get("success").getAsBoolean(), "A Login should succeed");

        // ============================================================
        // === 3. B Login =============================================
        // ============================================================
        clientB.send("{\"messageType\":\"Login\",\"userId\":\"playerB\",\"password\":\"passB\"}");
        JsonObject loginResultB = clientB.awaitMessageOfType("loginResult", 3, TimeUnit.SECONDS);
        assertNotNull(loginResultB, "B should receive loginResult");
        assertTrue(loginResultB.get("success").getAsBoolean(), "B Login should succeed");

        // ============================================================
        // === 4. A startMatch（先发，确保 A 为红方/先手）=============
        // ============================================================
        clientA.send("{\"messageType\":\"startMatch\"}");
        // 用 ping/pong 同步，确认服务器已处理 A 的 startMatch，再让 B 匹配
        clientA.send("{\"messageType\":\"ping\",\"timestamp\":999}");
        JsonObject syncPong = clientA.awaitMessageOfType("pong", 3, TimeUnit.SECONDS);
        assertNotNull(syncPong, "pong confirms server processed A's startMatch");

        // ============================================================
        // === 5. B startMatch ========================================
        // ============================================================
        clientB.send("{\"messageType\":\"startMatch\"}");

        // ============================================================
        // === 6. 双方收到 matchSuccess ===============================
        // ============================================================
        JsonObject matchA = clientA.awaitMessageOfType("matchSuccess", 3, TimeUnit.SECONDS);
        JsonObject matchB = clientB.awaitMessageOfType("matchSuccess", 3, TimeUnit.SECONDS);
        assertNotNull(matchA, "A should receive matchSuccess");
        assertNotNull(matchB, "B should receive matchSuccess");
        assertEquals(matchA.get("roomId").getAsString(), matchB.get("roomId").getAsString(),
                "both players should be in the same room");
        assertEquals("playerB", matchA.get("opponentId").getAsString());
        assertEquals("playerA", matchB.get("opponentId").getAsString());

        // ============================================================
        // === 7. A Ready =============================================
        // ============================================================
        clientA.send("{\"messageType\":\"Ready\"}");

        // ============================================================
        // === 8. B Ready =============================================
        // ============================================================
        clientB.send("{\"messageType\":\"Ready\"}");

        // ============================================================
        // === 9. 双方收到 gameStart（中间有 roomInfo，按类型匹配跳过）
        // ============================================================
        JsonObject gameStartA = clientA.awaitMessageOfType("gameStart", 3, TimeUnit.SECONDS);
        JsonObject gameStartB = clientB.awaitMessageOfType("gameStart", 3, TimeUnit.SECONDS);
        assertNotNull(gameStartA, "A should receive gameStart");
        assertNotNull(gameStartB, "B should receive gameStart");
        assertTrue(gameStartA.has("initialBoard"), "gameStart should contain initialBoard");

        // 断言颜色与先手
        assertEquals("red", gameStartA.get("yourColor").getAsString(),
                "A called startMatch first → A must be red");
        assertTrue(gameStartA.get("firstHand").getAsBoolean(),
                "red must have firstHand=true");
        assertEquals("black", gameStartB.get("yourColor").getAsString(),
                "B must be black");
        assertFalse(gameStartB.get("firstHand").getAsBoolean(),
                "black must have firstHand=false");

        // ============================================================
        // === 10. A（红方）走一步合法着法 b2→e2（翻子）==============
        // ============================================================
        clientA.send("{\"messageType\":\"move\",\"fromX\":\"b\",\"fromY\":2,\"toX\":\"e\",\"toY\":2,\"isFlip\":true}");

        // ============================================================
        // === 11. 双方收到 moveResult（valid=true）====================
        // ============================================================
        JsonObject moveA = clientA.awaitMessageOfType("moveResult", 3, TimeUnit.SECONDS);
        JsonObject moveB = clientB.awaitMessageOfType("moveResult", 3, TimeUnit.SECONDS);
        assertNotNull(moveA, "A should receive moveResult");
        assertNotNull(moveB, "B should receive moveResult");
        assertTrue(moveA.get("valid").getAsBoolean(), "move should be valid");
        assertTrue(moveB.get("valid").getAsBoolean(), "move should be valid");
        assertTrue(moveA.getAsJsonObject("move").get("isFlip").getAsBoolean(),
                "first move of a hidden piece must be a flip");
        assertTrue(moveA.has("flipResult"), "moveResult should contain flipResult");

        // ============================================================
        // === 12. 认输终局：B 发送 Resign ============================
        // ============================================================
        clientB.send("{\"messageType\":\"Resign\"}");

        // ============================================================
        // === 13. 双方收到 gameOver（reason=resign，winner≠认输方）====
        // ============================================================
        JsonObject gameOverA = clientA.awaitMessageOfType("gameOver", 3, TimeUnit.SECONDS);
        JsonObject gameOverB = clientB.awaitMessageOfType("gameOver", 3, TimeUnit.SECONDS);
        assertNotNull(gameOverA, "A should receive gameOver");
        assertNotNull(gameOverB, "B should receive gameOver");
        assertEquals("resign", gameOverA.get("reason").getAsString(),
                "gameOver reason must be resign");
        assertEquals("resign", gameOverB.get("reason").getAsString(),
                "gameOver reason must be resign");
        assertEquals("playerA", gameOverA.get("winnerId").getAsString(),
                "winner must be A since B resigned");
        assertEquals("playerA", gameOverB.get("winnerId").getAsString(),
                "winner must be A since B resigned");

        // ============================================================
        // === 14. 连接正常关闭 ========================================
        // ============================================================
        closeQuietly(clientA);
        closeQuietly(clientB);
        assertTrue(clientA.closed.await(3, TimeUnit.SECONDS), "clientA should close cleanly");
        assertTrue(clientB.closed.await(3, TimeUnit.SECONDS), "clientB should close cleanly");
    }

    // ---- helpers ----

    /**
     * 从 OS 临时端口范围取随机可用端口，并显式排除 avoidPort。
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

    /**
     * 用 Socket 直连探测服务器端口是否就绪，最多等 3 秒。
     * 替代 server.start() 后的盲等，避免 CI 环境因启动慢而失败。
     */
    private void waitForServerReady() throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                // 端口已监听，服务器就绪
                return;
            } catch (Exception e) {
                Thread.sleep(50);
            }
        }
        fail("server did not start listening on port " + port + " within 3 seconds");
    }

    /**
     * connectBlocking 重试等待 WebSocket 握手就绪。
     * true  → 握手成功
     * false → WebSocket 升级被拒（永久错误），立刻 fail
     * 异常 → TCP 连接失败（瞬态），重试
     */
    private void connectWithRetry(TestClient client) throws Exception {
        for (int i = 0; i < 10; i++) {
            try {
                if (client.connectBlocking()) {
                    return;
                }
                // 返回 false = 服务器拒绝了 WebSocket 升级，重试无意义
                fail("WebSocket handshake rejected by server");
            } catch (Exception e) {
                // TCP 连接失败，可能是服务器还没就绪，重试
                if (i == 9) {
                    fail("failed to connect after 10 retries: " + e.getMessage());
                }
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
            super(new URI("ws://127.0.0.1:" + port));
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
