/*
 * 文件功能：揭棋服务端进程入口，负责启动 WebSocket 服务并接入协议服务器。
 * 所属模块：jieqi-server。
 * 使用场景：通过命令行或 fat-jar 启动本地/联调服务器，默认监听课程约定端口 8887。
 */
package jieqi.server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

/**
 * 服务端启动入口。
 *
 * <p>该类只处理进程生命周期、端口解析和 WebSocket 传输适配；具体业务协议交给
 * {@link ProtocolServer}，这样测试可以绕开真实网络直接验证协议逻辑。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * java -jar jieqi-server.jar
 * java -jar jieqi-server.jar 9000
 * }</pre>
 */
public final class ServerMain {
    /** 默认端口来自公共接口文档，便于跨组联调时不额外配置。 */
    static final int DEFAULT_PORT = 8887;

    /**
     * 工具型入口类不应被实例化。
     *
     * @throws UnsupportedOperationException 不会抛出；私有构造器仅用于禁止外部创建实例。
     * @apiNote 使用示例：无需创建对象，直接调用 {@link #main(String[])}。
     */
    private ServerMain() {}

    /**
     * 启动揭棋 WebSocket 服务器并阻塞当前线程。
     *
     * @param args 可选命令行参数；第一个参数为监听端口，例如 {@code 9000}。
     * @throws Exception 当 WebSocket 服务启动或停止流程抛出异常时向外传递，方便启动脚本感知失败。
     * @apiNote 使用示例：{@code ServerMain.main(new String[]{"9000"});}
     */
    public static void main(String[] args) throws Exception {
        Core.ServerConfig config = Core.ServerConfig.fromEnv();
        config.port = parsePort(args, config.port);
        JieqiWebSocketServer server = new JieqiWebSocketServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // 关闭钩子给 WebSocket 一小段时间发送 close 帧，避免进程退出时连接被硬切。
                server.stop(1_000);
            } catch (InterruptedException e) {
                // 恢复中断标记，避免 JVM 关闭流程吞掉调用方关心的中断语义。
                Thread.currentThread().interrupt();
            }
        }, "jieqi-server-shutdown"));

        server.start();
        System.out.println("Jieqi server listening on port " + config.port);
        // Java-WebSocket 内部线程是后台服务线程；主线程阻塞可保证命令行进程持续运行。
        Thread.currentThread().join();
    }

    /**
     * 使用课程默认端口解析命令行参数。
     *
     * @param args 命令行参数；空参数表示使用默认端口。
     * @return 最终监听端口。
     * @throws IllegalArgumentException 当端口不是整数或超出 TCP 端口范围时抛出。
     * @apiNote 使用示例：{@code int port = ServerMain.parsePort(new String[]{"8887"});}
     */
    static int parsePort(String[] args) {
        return parsePort(args, DEFAULT_PORT);
    }

    /**
     * 解析命令行端口，并在未提供参数时回退到指定默认值。
     *
     * @param args 命令行参数；只读取第一个参数。
     * @param defaultPort 未传端口时使用的默认值。
     * @return 有效 TCP 端口号，范围为 1 到 65535。
     * @throws IllegalArgumentException 当端口文本非法或端口越界时抛出。
     * @apiNote 使用示例：{@code int port = ServerMain.parsePort(args, 8887);}
     */
    static int parsePort(String[] args, int defaultPort) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(args[0].trim());
            if (port < 1 || port > 65_535) {
                // 端口越界时尽早失败，比启动后由网络库报错更容易定位配置问题。
                throw new IllegalArgumentException("port out of range: " + args[0]);
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid port: " + args[0], ex);
        }
    }

    /**
     * Java-WebSocket 服务器适配器。
     *
     * <p>它把网络库回调转换为 {@link Core.ClientChannel} 调用，使协议层不依赖具体 WebSocket
     * 实现，便于单元测试使用内存通道。</p>
     */
    static final class JieqiWebSocketServer extends WebSocketServer {
        private final ProtocolServer protocol;

        /**
         * 创建绑定到配置端口的 WebSocket 服务器。
         *
         * @param config 服务端配置，至少包含监听端口和协议配置。
         * @throws IllegalArgumentException 当端口无效或地址无法绑定时由底层库抛出。
         * @apiNote 使用示例：{@code new JieqiWebSocketServer(Core.ServerConfig.fromEnv()).start();}
         */
        JieqiWebSocketServer(Core.ServerConfig config) {
            super(new InetSocketAddress(config.port));
            this.protocol = new ProtocolServer(config);
        }

        /**
         * 处理客户端连接建立事件。
         *
         * @param conn WebSocket 连接对象。
         * @param handshake 客户端握手信息；当前协议不依赖握手扩展。
         * @throws RuntimeException 当协议层连接初始化失败时由调用栈抛出。
         * @apiNote 使用示例：Java-WebSocket 在握手成功后自动回调该方法。
         */
        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            protocol.onConnected(new WebSocketChannel(conn));
        }

        /**
         * 处理客户端连接关闭事件。
         *
         * @param conn WebSocket 连接对象。
         * @param code 关闭码。
         * @param reason 关闭原因文本。
         * @param remote 是否由远端主动关闭。
         * @throws RuntimeException 当协议层断线处理失败时由调用栈抛出。
         * @apiNote 使用示例：对局中断线会被协议层转换为 disconnect 终局。
         */
        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            protocol.onClosed(new WebSocketChannel(conn));
            System.out.println("websocket closed: code=" + code + ", reason=" + reason + ", remote=" + remote);
        }

        /**
         * 处理客户端文本消息。
         *
         * @param conn WebSocket 连接对象。
         * @param message UTF-8 JSON 文本帧。
         * @throws RuntimeException 当协议层消息处理发生未捕获异常时由调用栈抛出。
         * @apiNote 使用示例：客户端发送 {@code {"messageType":"ping","timestamp":1}}。
         */
        @Override
        public void onMessage(WebSocket conn, String message) {
            protocol.onMessage(new WebSocketChannel(conn), message);
        }

        /**
         * 记录 WebSocket 传输层异常。
         *
         * @param conn 出错连接；可能为空。
         * @param ex 传输层异常。
         * @throws RuntimeException 当前实现只打印日志，不主动抛出异常。
         * @apiNote 使用示例：网络库在解析帧失败或连接异常时自动回调。
         */
        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("websocket error from " + remote(conn) + ": " + ex.getMessage());
            ex.printStackTrace(System.err);
        }

        /**
         * 记录服务器启动完成事件。
         *
         * @throws RuntimeException 当前实现只打印日志，不主动抛出异常。
         * @apiNote 使用示例：启动后控制台显示绑定地址，便于联调确认端口。
         */
        @Override
        public void onStart() {
            System.out.println("websocket server started: " + getAddress());
        }
    }

    /**
     * WebSocket 到服务端通道接口的轻量适配。
     *
     * @param conn 底层 WebSocket 连接。
     */
    private record WebSocketChannel(WebSocket conn) implements Core.ClientChannel {
        /**
         * 向客户端发送 JSON 文本。
         *
         * @param json 已序列化的协议 JSON。
         * @throws RuntimeException 当底层连接发送失败时由 Java-WebSocket 抛出。
         * @apiNote 使用示例：{@code channel.send(Messages.pong(1));}
         */
        @Override
        public void send(String json) {
            conn.send(json);
        }

        /**
         * 主动关闭客户端连接。
         *
         * @throws RuntimeException 当底层关闭失败时由 Java-WebSocket 抛出。
         * @apiNote 使用示例：入站帧超过 1KB 时协议层会关闭连接。
         */
        @Override
        public void closeConnection() {
            conn.close();
        }

        /**
         * 查询连接是否仍可发送消息。
         *
         * @return 连接打开时返回 {@code true}。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：断线终局只向仍在线的对手发送 gameOver。
         */
        @Override
        public boolean isOpen() {
            return conn.isOpen();
        }

        /**
         * 返回远端地址文本。
         *
         * @return 远端地址；未知时返回 {@code <unknown>}。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：登录日志会输出该地址帮助联调定位连接。
         */
        @Override
        public String remote() {
            return ServerMain.remote(conn);
        }
    }

    /**
     * 安全读取 WebSocket 远端地址。
     *
     * @param conn WebSocket 连接；允许为空。
     * @return 远端地址文本；连接或地址为空时返回 {@code <unknown>}。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code String addr = ServerMain.remote(conn);}
     */
    private static String remote(WebSocket conn) {
        return conn == null || conn.getRemoteSocketAddress() == null
                ? "<unknown>"
                : conn.getRemoteSocketAddress().toString();
    }
}
