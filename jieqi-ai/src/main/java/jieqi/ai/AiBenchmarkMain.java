package jieqi.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 对战评测 CSV 导出工具（D-04 / D-05）。
 * <p>
 * 支持单边/双向、单种子/多种子、汇总 CSV。
 */
public final class AiBenchmarkMain {

    static final String CSV_HEADER =
            "agent,opponent,side,games,wins,losses,draws,winRate,averagePlies,seed,maxPlies";

    static final String SUMMARY_HEADER =
            "agent,opponent,totalGames,wins,losses,draws,winRate,averagePlies,seedCount";

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
            writeFile(csvFile, csv);
            System.out.println("wrote CSV: " + csvFile);
        } else {
            System.out.print(csv);
        }
        if (opts.summaryCsvPath != null) {
            String summaryCsv = toSummaryCsv(rows);
            Path summaryFile = Path.of(opts.summaryCsvPath);
            writeFile(summaryFile, summaryCsv);
            System.out.println("wrote summary: " + summaryFile);
        }
    }

    // ================================================================
    // === 评测执行
    // ================================================================

    /**
     * 按配置跑完所有 matchup × seed × side，返回逐行结果。
     */
    static List<BenchmarkRow> run(CliOptions opts) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (long seed : opts.seeds) {
            for (String opponent : opts.opponents) {
                // agent 执红 vs opponent 执黑
                SelfPlayResult redSide = SelfPlayMain.run(
                        new SelfPlayMain.CliOptions(opts.games, opts.agent, opponent, seed, opts.maxPlies));
                rows.add(BenchmarkRow.from(opts.agent, opponent, "red", redSide, seed, opts.maxPlies));

                if (opts.bothSides) {
                    // opponent 执红 vs agent 执黑
                    SelfPlayResult blackSide = SelfPlayMain.run(
                            new SelfPlayMain.CliOptions(opts.games, opponent, opts.agent, seed, opts.maxPlies));
                    rows.add(BenchmarkRow.from(opts.agent, opponent, "black", blackSide, seed, opts.maxPlies));
                }
            }
        }
        return rows;
    }

    // ================================================================
    // === CSV 输出
    // ================================================================

    static String toCsv(List<BenchmarkRow> rows) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (BenchmarkRow row : rows) {
            sb.append(row.toCsv()).append('\n');
        }
        return sb.toString();
    }

    static String toSummaryCsv(List<BenchmarkRow> rows) {
        Map<String, SummaryAcc> groups = new LinkedHashMap<>();
        for (BenchmarkRow row : rows) {
            String key = row.agent() + "|" + row.opponent();
            groups.computeIfAbsent(key, k -> new SummaryAcc(row.agent(), row.opponent())).add(row);
        }
        StringBuilder sb = new StringBuilder(SUMMARY_HEADER).append('\n');
        for (SummaryAcc acc : groups.values()) {
            sb.append(acc.toCsv()).append('\n');
        }
        return sb.toString();
    }

    static void writeFile(Path path, String content) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ================================================================
    // === CLI 解析
    // ================================================================

    static CliOptions parseArgs(String[] args) {
        String agent = DEFAULT_AGENT;
        String[] opponents = DEFAULT_OPPONENTS.split(",");
        int games = DEFAULT_GAMES;
        Long seedExplicit = null;          // --seed 显式值
        String seedsRaw = null;            // --seeds 原始字符串
        int maxPlies = DEFAULT_MAX_PLIES;
        String csvPath = null;
        String summaryCsvPath = null;
        boolean bothSides = false;

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
                case "seed" -> seedExplicit = Long.parseLong(requireValue(name, value));
                case "seeds" -> seedsRaw = requireValue(name, value);
                case "maxPlies" -> maxPlies = parseInt(requireValue(name, value), name);
                case "csv" -> csvPath = requireValue(name, value);
                case "summaryCsv" -> summaryCsvPath = requireValue(name, value);
                case "bothSides" -> bothSides = true;
                default -> throw new IllegalArgumentException("unknown option: --" + name);
            }
        }

        // --seed 与 --seeds 互斥
        if (seedExplicit != null && seedsRaw != null) {
            throw new IllegalArgumentException("--seed and --seeds cannot be used together");
        }

        long[] seeds;
        if (seedsRaw != null) {
            String[] parts = seedsRaw.split(",");
            seeds = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                seeds[i] = Long.parseLong(parts[i].trim());
            }
        } else if (seedExplicit != null) {
            seeds = new long[]{seedExplicit};
        } else {
            seeds = new long[]{DEFAULT_SEED};
        }

        validateAgent(agent);
        String[] normalizedOpponents = new String[opponents.length];
        for (int i = 0; i < opponents.length; i++) {
            normalizedOpponents[i] = normalize(opponents[i].trim());
            validateAgent(normalizedOpponents[i]);
        }
        if (games <= 0) {
            throw new IllegalArgumentException("games must be > 0");
        }
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        return new CliOptions(agent, normalizedOpponents, games, seeds, maxPlies, csvPath, summaryCsvPath, bothSides);
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
                  --seed 1            (single seed; mutually exclusive with --seeds)
                  --seeds 1,2,3       (multiple seeds; mutually exclusive with --seed)
                  --maxPlies 200
                  --bothSides         (run agent as both red and black)
                  --csv target/ai-benchmark.csv
                  --summaryCsv target/ai-benchmark-summary.csv

                Example:
                  java -cp target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \\
                    --agent expecti --opponents random,greedy,tactical \\
                    --games 5 --seeds 1,2,3 --bothSides --maxPlies 120 \\
                    --csv docs/ai-benchmark/expecti-baseline-raw.csv \\
                    --summaryCsv docs/ai-benchmark/expecti-baseline-summary.csv
                """;
    }

    // ================================================================
    // === 类型定义
    // ================================================================

    record CliOptions(
            String agent,
            String[] opponents,
            int games,
            long[] seeds,
            int maxPlies,
            String csvPath,
            String summaryCsvPath,
            boolean bothSides) {
    }

    /**
     * 单行评测结果。
     */
    public record BenchmarkRow(
            String agent,
            String opponent,
            String side,
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
            Objects.requireNonNull(side, "side");
            if (!"red".equals(side) && !"black".equals(side)) {
                throw new IllegalArgumentException("side must be 'red' or 'black', got: " + side);
            }
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
         * 从 SelfPlayResult 构造，始终从 agent 视角统计。
         * side="red"   → agent 执红，wins = redWins
         * side="black" → agent 执黑，wins = blackWins
         */
        static BenchmarkRow from(String agent, String opponent, String side,
                                  SelfPlayResult result, long seed, int maxPlies) {
            int agentWins = "red".equals(side) ? result.redWins() : result.blackWins();
            int agentLosses = "red".equals(side) ? result.blackWins() : result.redWins();
            double winRate = result.games() == 0 ? 0.0 : (double) agentWins / result.games();
            return new BenchmarkRow(
                    agent, opponent, side,
                    result.games(),
                    agentWins, agentLosses, result.draws(),
                    winRate, result.averagePlies(),
                    seed, maxPlies);
        }

        public String winRateText() {
            return String.format(Locale.ROOT, "%.4f", winRate);
        }

        public String averagePliesText() {
            return String.format(Locale.ROOT, "%.2f", averagePlies);
        }

        public String toCsv() {
            return String.join(",",
                    agent, opponent, side,
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

    /**
     * 汇总累积器，按 (agent, opponent) 聚合多行。
     */
    static final class SummaryAcc {
        private final String agent;
        private final String opponent;
        private int totalGames;
        private int wins;
        private int losses;
        private int draws;
        private double totalPlies;
        private final Set<Long> seeds = new HashSet<>();

        SummaryAcc(String agent, String opponent) {
            this.agent = agent;
            this.opponent = opponent;
        }

        void add(BenchmarkRow row) {
            totalGames += row.games();
            wins += row.wins();
            losses += row.losses();
            draws += row.draws();
            totalPlies += row.averagePlies() * row.games();
            seeds.add(row.seed());   // 用 Set 去重，避免 bothSides 模式下同一 seed 被重复计数
        }

        double aggregatedWinRate() {
            return totalGames == 0 ? 0.0 : (double) wins / totalGames;
        }

        double aggregatedAveragePlies() {
            return totalGames == 0 ? 0.0 : totalPlies / totalGames;
        }

        String toCsv() {
            return String.join(",",
                    agent, opponent,
                    Integer.toString(totalGames),
                    Integer.toString(wins),
                    Integer.toString(losses),
                    Integer.toString(draws),
                    String.format(Locale.ROOT, "%.4f", aggregatedWinRate()),
                    String.format(Locale.ROOT, "%.2f", aggregatedAveragePlies()),
                    Integer.toString(seeds.size()));
        }
    }
}
