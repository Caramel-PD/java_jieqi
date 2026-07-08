package client;

import java.util.Scanner;

public class ClientMain {

    private static WsClient wsClient;
    private static final String USER_ID = "e_test_1";
    private static final String PASSWORD = "123456";
    private static final String NICKNAME = "E测试用户";

    public static void main(String[] args) {
        System.out.println("===== 揭棋客户端 E-01 命令行工具 =====");
        System.out.println("命令列表:");
        System.out.println("  connect          - 连接服务器 (ws://localhost:8887)");
        System.out.println("  login            - 登录 (固定账号)");
        System.out.println("  register         - 注册 (固定账号)");
        System.out.println("  startMatch       - 开始匹配");
        System.out.println("  ready            - 准备就绪");
        System.out.println("  move <from> <to> - 走子，如 move a0 a1");
        System.out.println("  flip <x><y>      - 原地翻子，如 flip b3");
        System.out.println("  ping             - 发送心跳");
        System.out.println("  resign           - 认输");
        System.out.println("  quit             - 退出");
        System.out.println("---------------------------------------------");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "connect" -> doConnect();
                    case "login" -> doLogin();
                    case "register" -> doRegister();
                    case "startmatch" -> doStartMatch();
                    case "ready" -> doReady();
                    case "move" -> {
                        if (parts.length < 3) System.err.println("用法: move <from> <to>");
                        else doMove(parts[1], parts[2]);
                    }
                    case "flip" -> {
                        if (parts.length < 2) System.err.println("用法: flip <coord>");
                        else doFlip(parts[1]);
                    }
                    case "ping" -> doPing();
                    case "resign" -> doResign();
                    case "quit" -> {
                        System.out.println("退出程序");
                        if (wsClient != null) wsClient.close();
                        scanner.close();
                        return;
                    }
                    default -> System.err.println("未知命令: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("执行命令出错: " + e.getMessage());
            }
        }
    }

    private static void doConnect() {
        if (wsClient != null && wsClient.isOpen()) {
            System.out.println("已连接，无需重复操作");
            return;
        }
        wsClient = new WsClient("ws://localhost:8887");
        wsClient.connect();
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        System.out.println("连接请求已发送，等待 onOpen 确认...");
    }

    private static void doLogin() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildLogin(USER_ID, PASSWORD));
    }

    private static void doRegister() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildRegister(USER_ID, PASSWORD, NICKNAME));
    }

    private static void doStartMatch() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildStartMatch());
    }

    private static void doReady() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildReady());
    }

    private static void doMove(String from, String to) {
        ensureConnected();
        int[] f = parseCoord(from);
        int[] t = parseCoord(to);
        boolean flip = (f[0] == t[0] && f[1] == t[1]);
        wsClient.send(MessageBuilder.buildMove(
                String.valueOf((char)('a' + f[0])), f[1],
                String.valueOf((char)('a' + t[0])), t[1],
                flip
        ));
    }

    private static void doFlip(String coord) {
        ensureConnected();
        int[] p = parseCoord(coord);
        wsClient.send(MessageBuilder.buildFlipOnly(
                String.valueOf((char)('a' + p[0])), p[1]
        ));
    }

    private static void doPing() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildPing());
    }

    private static void doResign() {
        ensureConnected();
        wsClient.send(MessageBuilder.buildResign());
    }

    private static void ensureConnected() {
        if (wsClient == null || !wsClient.isOpen()) {
            throw new IllegalStateException("未连接，请先执行 connect");
        }
    }

    private static int[] parseCoord(String coord) {
        if (coord == null || coord.length() < 2)
            throw new IllegalArgumentException("坐标格式错误: " + coord);
        char col = coord.charAt(0);
        if (col < 'a' || col > 'i')
            throw new IllegalArgumentException("列必须 a-i: " + coord);
        int row = Integer.parseInt(coord.substring(1));
        if (row < 0 || row > 9)
            throw new IllegalArgumentException("行必须 0-9: " + coord);
        return new int[]{col - 'a', row};
    }
}
