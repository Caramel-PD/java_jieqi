package jieqi.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * AI 对战评测 CSV 导出工具（D-04）。
 * <p>
 * 用法示例：
 * <pre>
 * java -cp target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
 *   --agent expecti \
 *   --opponents random,greedy,tactical \
 *   --games 20 \
 *   --seed 1 \
 *   --maxPlies 200 \
 *   --csv target/ai-benchmark.csv
 * </pre>
 */
public final class AiBenchmarkMain {

    static final String CSV_HEADER =
            "agent,opponent,games,wins,losses,draws,winRate,averagePlies,seed,maxPlies";

    private static final int DEFAULT_GAMES = 20;
    private static final String DEFAULT_AGENT = "expecti";
    private static final String DEFAULT_OPPONENTS = "random,greedy,tactical";
    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_MAX_PLIES = 200;

    private AiBenchmarkMain() {
    }

    public static void main(String[] args) throws IOException {
        if (containsHelp(args)) {
            System.out.println(usage());
            return;
        }
        CliOptions opts = parseArgs(args);
        List<BenchmarkRow> rows = run(opts);
        String csv = toCsv(rows);
        if (opts.csvPath != null) {
            Path csvFile = Path.of(opts.csvPath);
            writeCsv(csvFile, csv);
            System.out.println("wrote CSV: " + csvFile);
        } else {
            System.out.print(csv);
        }
    }

    /**
     * 对每个 opponent 运行 agent vs opponent 评测，返回一行 BenchmarkRow。
     * agent 始终执红（先手）。
     */
    static List<BenchmarkRow> run(CliOptions opts) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (String opponent : opts.opponents) {
            SelfPlayResult result = SelfPlayMain.run(
                    new SelfPlayMain.CliOptions(opts.games, opts.agent, opponent, opts.seed, opts.maxPlies));
            rows.add(BenchmarkRow.from(opts.agent, opponent, result, opts.seed, opts.maxPlies));
        }
        return rows;
    }

    static String toCsv(List<BenchmarkRow> rows) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (BenchmarkRow row : rows) {
            sb.append(row.toCsv()).append('\n');
        }
        return sb.toString();
    }

    static void writeCsv(Path path, String csv) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    // ---- CLI ----

    static CliOptions parseArgs(String[] args) {
        String agent = DEFAULT_AGENT;
        String[] opponents = DEFAULT_OPPONENTS.split(",");
        int games = DEFAULT_GAMES;
        long seed = DEFAULT_SEED;
        int maxPlies = DEFAULT_MAX_PLIES;
        String csvPath = null;

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (!raw.startsWith("--")) {
                throw new IllegalArgumentException("unknown argument: " + raw);
            }
            String name = raw.substring(2);
            String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : null;

            switch (name) {
                case "agent" -> agent = normalize(requireValue(name, value));
                case "opponents" -> opponents = requireValue(name, value).split(",");
                case "games" -> games = parseInt(requireValue(name, value), name);
                case "seed" -> seed = Long.parseLong(requireValue(name, value));
                case "maxPlies" -> maxPlies = parseInt(requireValue(name, value), name);
                case "csv" -> csvPath = requireValue(name, value);
                default -> throw new IllegalArgumentException("unknown option: --" + name);
            }
        }

        validateAgent(agent);
        for (String opp : opponents) {
            String normalized = normalize(opp.trim());
            validateAgent(normalized);
        }
        if (games <= 0) {
            throw new IllegalArgumentException("games must be > 0");
        }
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        // normalize all opponents
        String[] normalizedOpponents = new String[opponents.length];
        for (int i = 0; i < opponents.length; i++) {
            normalizedOpponents[i] = normalize(opponents[i].trim());
        }
        return new CliOptions(agent, normalizedOpponents, games, seed, maxPlies, csvPath);
    }

    private static String requireValue(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " requires a value");
        }
        return value;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer: " + value);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").trim();
    }

    private static void validateAgent(String agentType) {
        if (!"random".equals(agentType)
                && !"greedy".equals(agentType)
                && !"tactical".equals(agentType)
                && !"expecti".equals(agentType)) {
            throw new IllegalArgumentException("unsupported agent: " + agentType);
        }
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static String usage() {
        return """
                Usage: java -cp target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain [options]

                Options:
                  --agent expecti
                  --opponents random,greedy,tactical
                  --games 20
                  --seed 1
                  --maxPlies 200
                  --csv target/ai-benchmark.csv

                Example:
                  java -cp target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain --agent expecti --opponents random,greedy,tactical --games 20 --seed 1 --maxPlies 200 --csv target/ai-benchmark.csv
                """;
    }

    // ---- types ----

    record CliOptions(
            String agent,
            String[] opponents,
            int games,
            long seed,
            int maxPlies,
            String csvPath) {
    }

    /**
     * 单行评测结果：某个 agent 对某个 opponent 的胜率统计。
     */
    public record BenchmarkRow(
            String agent,
            String opponent,
            int games,
            int wins,
            int losses,
            int draws,
            double winRate,
            double averagePlies,
            long seed,
            int maxPlies) {

        public BenchmarkRow {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(opponent, "opponent");
            if (games < 0) {
                throw new IllegalArgumentException("games must be >= 0");
            }
            if (wins < 0 || losses < 0 || draws < 0) {
                throw new IllegalArgumentException("wins/losses/draws must be >= 0");
            }
            if (wins + losses + draws != games) {
                throw new IllegalArgumentException("wins+losses+draws must equal games");
            }
            if (maxPlies < 0) {
                throw new IllegalArgumentException("maxPlies must be >= 0");
            }
        }

        /**
         * 从 SelfPlayResult 构造：agent 始终执红（先手）。
         */
        static BenchmarkRow from(String agent, String opponent, SelfPlayResult result, long seed, int maxPlies) {
            double winRate = result.games() == 0 ? 0.0 : (double) result.redWins() / result.games();
            return new BenchmarkRow(
                    agent,
                    opponent,
                    result.games(),
                    result.redWins(),     // agent 执红 → redWins = agent wins
                    result.blackWins(),   // blackWins = agent losses
                    result.draws(),
                    winRate,
                    result.averagePlies(),
                    seed,
                    maxPlies);
        }

        /** 格式化为带 4 位小数的 winRate（如 0.7500）。 */
        public String winRateText() {
            return String.format(Locale.ROOT, "%.4f", winRate);
        }

        public String averagePliesText() {
            return String.format(Locale.ROOT, "%.2f", averagePlies);
        }

        public String toCsv() {
            return String.join(",",
                    agent,
                    opponent,
                    Integer.toString(games),
                    Integer.toString(wins),
                    Integer.toString(losses),
                    Integer.toString(draws),
                    winRateText(),
                    averagePliesText(),
                    Long.toString(seed),
                    Integer.toString(maxPlies));
        }
    }
}
