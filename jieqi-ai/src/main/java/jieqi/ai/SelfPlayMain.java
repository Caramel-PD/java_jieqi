package jieqi.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Command line entry point for local AI self-play experiments.
 */
public final class SelfPlayMain {

    static final int DEFAULT_GAMES = 1;
    static final String DEFAULT_RED_AGENT = "greedy";
    static final String DEFAULT_BLACK_AGENT = "random";
    static final long DEFAULT_SEED = 1L;
    static final int DEFAULT_MAX_PLIES = 200;

    private SelfPlayMain() {
    }

    public static void main(String[] args) {
        if (containsHelp(args)) {
            System.out.println(usage());
            return;
        }
        CliOptions options = parseArgs(args);
        if (options.matrix()) {
            runMatrix(options);
            return;
        }
        System.out.print(run(options).toReport());
    }

    static SelfPlayResult run(CliOptions options) {
        Random seeds = new Random(options.seed());
        SelfPlayResult.Builder builder = SelfPlayResult.builder(options.redAgent(), options.blackAgent());
        for (int i = 0; i < options.games(); i++) {
            Agent red = createAgent(options.redAgent(), seeds.nextLong());
            Agent black = createAgent(options.blackAgent(), seeds.nextLong());
            SelfPlayGame game = new SelfPlayGame(red, black, seeds.nextLong(), options.maxPlies());
            builder.add(game.play());
        }
        return builder.build();
    }

    static CliOptions parseArgs(String[] args) {
        int games = DEFAULT_GAMES;
        String redAgent = DEFAULT_RED_AGENT;
        String blackAgent = DEFAULT_BLACK_AGENT;
        long seed = DEFAULT_SEED;
        int maxPlies = DEFAULT_MAX_PLIES;
        boolean matrix = false;
        String csvPath = null;

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
                case "games" -> games = Integer.parseInt(argument.requireValue());
                case "red" -> redAgent = normalizeAgent(argument.requireValue());
                case "black" -> blackAgent = normalizeAgent(argument.requireValue());
                case "seed" -> seed = Long.parseLong(argument.requireValue());
                case "maxplies" -> maxPlies = Integer.parseInt(argument.requireValue());
                case "matrix" -> matrix = argument.optionalBoolean(true);
                case "csv" -> csvPath = argument.requireValue();
                default -> throw new IllegalArgumentException("unknown option: --" + argument.name());
            }
        }

        validateAgent(redAgent);
        validateAgent(blackAgent);
        if (games <= 0) {
            throw new IllegalArgumentException("games must be > 0");
        }
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        return new CliOptions(games, redAgent, blackAgent, seed, maxPlies, matrix, csvPath);
    }

    static Agent createAgent(String agentType, long seed) {
        return switch (normalizeAgent(agentType)) {
            case "random" -> new RandomAgent(seed);
            case "greedy" -> new GreedyAgent();
            case "tactical" -> new TacticalAgent();
            default -> throw new IllegalArgumentException("unsupported agent: " + agentType);
        };
    }

    static String usage() {
        return """
                Usage: java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.SelfPlayMain [options]

                Options:
                  --games 20
                  --red random|greedy|tactical
                  --black random|greedy|tactical
                  --seed 1
                  --maxPlies 200
                  --matrix
                  --csv target/self-play.csv

                Single matchup example:
                  java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.SelfPlayMain --games 20 --red greedy --black random --seed 1 --maxPlies 200

                Matrix CSV example:
                  java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.SelfPlayMain --matrix --games 20 --seed 1 --maxPlies 200 --csv target/self-play.csv
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

    private static void validateAgent(String agentType) {
        if (!"random".equals(agentType) && !"greedy".equals(agentType) && !"tactical".equals(agentType)) {
            throw new IllegalArgumentException("unsupported agent: " + agentType);
        }
    }

    private static String normalizeAgent(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    record CliOptions(
            int games,
            String redAgent,
            String blackAgent,
            long seed,
            int maxPlies,
            boolean matrix,
            String csvPath) {

        CliOptions(int games, String redAgent, String blackAgent, long seed, int maxPlies) {
            this(games, redAgent, blackAgent, seed, maxPlies, false, null);
        }
    }

    private record Argument(String name, String value, boolean consumesNext) {

        private static Argument read(String raw, String next) {
            String body = raw.substring(2);
            int equals = body.indexOf('=');
            if (equals >= 0) {
                return new Argument(normalizeAgent(body.substring(0, equals)), body.substring(equals + 1), false);
            }
            String name = normalizeAgent(body);
            if (next == null || next.startsWith("--")) {
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
    }

    private static void runMatrix(CliOptions options) {
        SelfPlayExperiment experiment = SelfPlayExperiment.matrix(options.games(), options.seed(), options.maxPlies());
        if (options.csvPath() == null) {
            System.out.print(experiment.toCsv());
            return;
        }
        Path path = Path.of(options.csvPath());
        try {
            experiment.writeCsv(path);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write CSV: " + path, e);
        }
        System.out.println("wrote CSV: " + path);
    }
}
