package jieqi.ai;

import java.net.URI;
import java.util.Locale;

/**
 * Command line entry point for running an AI player against the WebSocket server.
 */
public final class AiMain {

    static final String DEFAULT_AGENT_TYPE = "greedy";

    private AiMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (containsHelp(args)) {
            System.out.println(usage());
            return;
        }
        CliOptions options = parseArgs(args);
        AiClient client = new AiClient(options.config(), createAgent(options.agentType()));
        client.connect().join();
        client.awaitStopped();
    }

    static CliOptions parseArgs(String[] args) {
        URI serverUrl = AiClientConfig.DEFAULT_SERVER_URL;
        String userId = AiClientConfig.DEFAULT_USER_ID;
        String password = AiClientConfig.DEFAULT_PASSWORD;
        String nickname = AiClientConfig.DEFAULT_NICKNAME;
        long thinkTimeMillis = AiClientConfig.DEFAULT_THINK_TIME_MILLIS;
        boolean registerOnConnect = false;
        String agentType = DEFAULT_AGENT_TYPE;

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (!raw.startsWith("--")) {
                throw new IllegalArgumentException("unknown argument: " + raw);
            }

            Argument argument = Argument.read(raw, i + 1 < args.length ? args[i + 1] : null);
            if (argument.consumesNext()) {
                i++;
            }

            switch (argument.name()) {
                case "server", "serverurl" -> serverUrl = URI.create(argument.requireValue());
                case "userid", "user" -> userId = argument.requireValue();
                case "password", "pass" -> password = argument.requireValue();
                case "nickname", "nick" -> nickname = argument.requireValue();
                case "thinktimemillis", "think" -> thinkTimeMillis = Long.parseLong(argument.requireValue());
                case "agent" -> agentType = argument.requireValue();
                case "register" -> registerOnConnect = argument.optionalBoolean(true);
                case "login" -> registerOnConnect = !argument.optionalBoolean(true);
                default -> throw new IllegalArgumentException("unknown option: --" + argument.name());
            }
        }

        return new CliOptions(
                new AiClientConfig(serverUrl, userId, password, nickname, thinkTimeMillis, registerOnConnect),
                agentType);
    }

    static Agent createAgent(String agentType) {
        return switch (normalize(agentType)) {
            case "random" -> new RandomAgent();
            case "greedy" -> new GreedyAgent();
            default -> throw new IllegalArgumentException("unsupported agent: " + agentType);
        };
    }

    static String usage() {
        return """
                Usage: java jieqi.ai.AiMain [options]
                  --serverUrl ws://localhost:8887
                  --userId ai
                  --password ai
                  --nickname AI
                  --agent random|greedy
                  --thinkTimeMillis 10000
                  --register
                """;
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    record CliOptions(AiClientConfig config, String agentType) {
    }

    private record Argument(String name, String value, boolean consumesNext) {

        private static Argument read(String raw, String next) {
            String body = raw.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                return new Argument(normalize(body.substring(0, equals)), body.substring(equals + 1), false);
            }
            String name = normalize(body);
            if (isFlag(name) || next == null || next.startsWith("--")) {
                return new Argument(name, null, false);
            }
            return new Argument(name, next, true);
        }

        private String requireValue() {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("--" + name + " requires a value");
            }
            return value;
        }

        private boolean optionalBoolean(boolean defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Boolean.parseBoolean(value);
        }

        private static boolean isFlag(String name) {
            return "register".equals(name) || "login".equals(name);
        }
    }
}
