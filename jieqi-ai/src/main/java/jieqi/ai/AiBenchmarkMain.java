package jieqi.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Small command line benchmark runner for smoke-checking one agent against several opponents.
 */
public final class AiBenchmarkMain {

    private static final String DEFAULT_AGENT = "expecti";
    private static final String DEFAULT_OPPONENTS = "random,greedy,tactical";
    private static final int DEFAULT_GAMES = 5;
    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_MAX_PLIES = 120;

    private AiBenchmarkMain() {
    }

    public static void main(String[] args) {
        if (containsHelp(args)) {
            System.out.println(usage());
            return;
        }
        Options options = parseArgs(args);
        System.out.print(run(options));
    }

    static String run(Options options) {
        StringBuilder out = new StringBuilder();
        out.append("agent=").append(options.agent()).append('\n');
        out.append("opponents=").append(String.join(",", options.opponents())).append('\n');
        out.append("gamesPerOpponent=").append(options.games()).append('\n');
        out.append("seed=").append(options.seed()).append('\n');
        out.append("maxPlies=").append(options.maxPlies()).append('\n');
        for (String opponent : options.opponents()) {
            SelfPlayResult result = SelfPlayMain.run(new SelfPlayMain.CliOptions(
                    options.games(),
                    options.agent(),
                    opponent,
                    options.seed(),
                    options.maxPlies()));
            out.append('\n')
                    .append("matchup=").append(options.agent()).append("_vs_").append(opponent).append('\n')
                    .append(result.toReport());
        }
        return out.toString();
    }

    static Options parseArgs(String[] args) {
        String agent = DEFAULT_AGENT;
        String opponents = DEFAULT_OPPONENTS;
        int games = DEFAULT_GAMES;
        long seed = DEFAULT_SEED;
        int maxPlies = DEFAULT_MAX_PLIES;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
            String name = normalize(arg.substring(2));
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException(arg + " requires a value");
            }
            String value = args[++i];
            switch (name) {
                case "agent" -> agent = normalize(value);
                case "opponents" -> opponents = value;
                case "games" -> games = Integer.parseInt(value);
                case "seed" -> seed = Long.parseLong(value);
                case "maxplies" -> maxPlies = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        if (games <= 0) {
            throw new IllegalArgumentException("games must be > 0");
        }
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        List<String> opponentList = Arrays.stream(opponents.split(","))
                .map(AiBenchmarkMain::normalize)
                .filter(value -> !value.isBlank())
                .toList();
        if (opponentList.isEmpty()) {
            throw new IllegalArgumentException("opponents must not be empty");
        }
        long validationSeed = seed;
        SelfPlayMain.createAgent(agent, validationSeed);
        opponentList.forEach(opponent -> SelfPlayMain.createAgent(opponent, validationSeed));
        return new Options(agent, opponentList, games, seed, maxPlies);
    }

    static String usage() {
        return """
                Usage: java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain --agent expecti --opponents random,greedy,tactical --games 5 --seed 1 --maxPlies 120
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

    record Options(String agent, List<String> opponents, int games, long seed, int maxPlies) {
    }
}
