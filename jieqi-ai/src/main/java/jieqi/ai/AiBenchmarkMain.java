package jieqi.ai;

import jieqi.common.Move;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * AI 对战评测 CSV 导出工具（D-04 / D-05 / D-06）。
 * <p>
 * 支持单边/双向、单种子/多种子、汇总 CSV、性能统计（不修改 AI 算法代码）。
 */
public final class AiBenchmarkMain {

    static final String CSV_HEADER =
            "agent,opponent,side,games,wins,losses,draws,winRate,averagePlies,seed,maxPlies,"
                    + "measuredMoves,timeoutMoves,timeoutRate,avgNodesPerMove,avgDepth,ttHits,ttStores,ttCutoffs";

    static final String SUMMARY_HEADER =
            "agent,opponent,totalGames,wins,losses,draws,winRate,averagePlies,seedCount";

    private static final int DEFAULT_GAMES = 20;
    private static final String DEFAULT_AGENT = "expecti";
    private static final String DEFAULT_OPPONENTS = "random,greedy,tactical";
    private static final long DEFAULT_SEED = 1L;
    private static final int DEFAULT_MAX_PLIES = 200;

    private AiBenchmarkMain() {}

    public static void main(String[] args) throws IOException {
        if (containsHelp(args)) { System.out.println(usage()); return; }
        CliOptions opts = parseArgs(args);
        List<BenchmarkRow> rows = run(opts);
        String csv = toCsv(rows);
        if (opts.csvPath != null) {
            writeFile(Path.of(opts.csvPath), csv);
            System.out.println("wrote CSV: " + opts.csvPath);
        } else { System.out.print(csv); }
        if (opts.summaryCsvPath != null) {
            String summaryCsv = toSummaryCsv(rows);
            writeFile(Path.of(opts.summaryCsvPath), summaryCsv);
            System.out.println("wrote summary: " + opts.summaryCsvPath);
        }
    }

    // ================================================================
    // === 评测执行（自跑 game loop，用 wrapper 收集性能统计）
    // ================================================================

    static List<BenchmarkRow> run(CliOptions opts) {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (long seed : opts.seeds) {
            for (String opponent : opts.opponents) {
                rows.add(runMatchup(opts.agent, opponent, "red", opts.games, seed, opts.maxPlies));
                if (opts.bothSides) {
                    rows.add(runMatchup(opts.agent, opponent, "black", opts.games, seed, opts.maxPlies));
                }
            }
        }
        return rows;
    }

    /**
     * 跑 agentName vs opponentName 的 N 局对局。
     * side 表示被测 agent 执哪一方。
     */
    static BenchmarkRow runMatchup(String agentName, String opponentName, String side,
                                    int games, long seed, int maxPlies) {
        boolean agentIsRed = "red".equals(side);
        Random rng = new Random(seed);
        int redWins = 0, blackWins = 0, draws = 0, totalPlies = 0;

        // 累积 agent 侧的性能统计（逐 move）
        long totalNodes = 0, ttHits = 0, ttStores = 0, ttCutoffs = 0;
        int totalDepth = 0, measuredMoves = 0, timeoutMoves = 0;

        for (int i = 0; i < games; i++) {
            // 创建 agent（不修改 C 的 createAgent）
            Agent redRaw = SelfPlayMain.createAgent(agentIsRed ? agentName : opponentName, rng.nextLong());
            Agent blackRaw = SelfPlayMain.createAgent(agentIsRed ? opponentName : agentName, rng.nextLong());

            // 只对被测 agent 包装 stats 收集器
            StatsCapture capture = new StatsCapture();
            Agent red = agentIsRed ? statsWrapper(redRaw, capture) : redRaw;
            Agent black = agentIsRed ? blackRaw : statsWrapper(blackRaw, capture);

            SelfPlayGame game = new SelfPlayGame(red, black, rng.nextLong(), maxPlies);
            SelfPlayGame.PlayedGame result = game.play();
            totalPlies += result.plies();
            switch (result.winner()) {
                case RED -> redWins++;
                case BLACK -> blackWins++;
                case DRAW -> draws++;
            }

            // 聚合一局的 stats（逐 move 累计）
            totalNodes += capture.totalNodes;
            totalDepth += capture.totalDepth;
            timeoutMoves += capture.timeoutMoves;
            ttHits += capture.ttHits;
            ttStores += capture.ttStores;
            ttCutoffs += capture.ttCutoffs;
            measuredMoves += capture.sampleCount;
        }

        int agentWins = agentIsRed ? redWins : blackWins;
        int agentLosses = agentIsRed ? blackWins : redWins;
        double winRate = games == 0 ? 0.0 : (double) agentWins / games;
        double avgPlies = games == 0 ? 0.0 : (double) totalPlies / games;
        double avgNodes = measuredMoves == 0 ? 0.0 : (double) totalNodes / measuredMoves;
        double avgDepth = measuredMoves == 0 ? 0.0 : (double) totalDepth / measuredMoves;
        double timeoutRate = measuredMoves == 0 ? 0.0 : (double) timeoutMoves / measuredMoves;

        return new BenchmarkRow(agentName, opponentName, side, games,
                agentWins, agentLosses, draws, winRate, avgPlies, seed, maxPlies,
                measuredMoves, timeoutMoves, timeoutRate,
                avgNodes, avgDepth, ttHits, ttStores, ttCutoffs);
    }

    /** 包装 Agent：selectMove 后逐次收集 SearchStats，每次 move 独立计数 */
    private static Agent statsWrapper(Agent delegate, StatsCapture capture) {
        return new Agent() {
            @Override
            public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
                Optional<Move> move = delegate.selectMove(view, budget);
                if (delegate instanceof ExpectiAgent ea) {
                    SearchStats s = ea.lastStats();
                    capture.totalNodes += s.searchedNodes();
                    capture.totalDepth += s.completedDepth();
                    if (s.timedOut()) capture.timeoutMoves++;
                    capture.ttHits += s.ttHits();
                    capture.ttStores += s.ttStores();
                    capture.ttCutoffs += s.ttCutoffs();
                    capture.sampleCount++;
                }
                return move;
            }
        };
    }

    private static final class StatsCapture {
        long totalNodes, ttHits, ttStores, ttCutoffs;
        int totalDepth, sampleCount;
        int timeoutMoves;  // 逐 move 统计的超时次数
    }

    // ================================================================
    // === CSV 输出
    // ================================================================

    static String toCsv(List<BenchmarkRow> rows) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (BenchmarkRow row : rows) sb.append(row.toCsv()).append('\n');
        return sb.toString();
    }

    static String toSummaryCsv(List<BenchmarkRow> rows) {
        Map<String, SummaryAcc> groups = new LinkedHashMap<>();
        for (BenchmarkRow row : rows) {
            String key = row.agent() + "|" + row.opponent();
            groups.computeIfAbsent(key, k -> new SummaryAcc(row.agent(), row.opponent())).add(row);
        }
        StringBuilder sb = new StringBuilder(SUMMARY_HEADER).append('\n');
        for (SummaryAcc acc : groups.values()) sb.append(acc.toCsv()).append('\n');
        return sb.toString();
    }

    static void writeFile(Path path, String content) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ================================================================
    // === CLI 解析
    // ================================================================

    static CliOptions parseArgs(String[] args) {
        String agent = DEFAULT_AGENT;
        String[] opponents = DEFAULT_OPPONENTS.split(",");
        int games = DEFAULT_GAMES;
        Long seedExplicit = null;
        String seedsRaw = null;
        int maxPlies = DEFAULT_MAX_PLIES;
        String csvPath = null;
        String summaryCsvPath = null;
        boolean bothSides = false;

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (!raw.startsWith("--")) throw new IllegalArgumentException("unknown argument: " + raw);
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

        if (seedExplicit != null && seedsRaw != null)
            throw new IllegalArgumentException("--seed and --seeds cannot be used together");

        long[] seeds;
        if (seedsRaw != null) {
            String[] parts = seedsRaw.split(",");
            seeds = new long[parts.length];
            for (int i = 0; i < parts.length; i++) seeds[i] = Long.parseLong(parts[i].trim());
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
        if (games <= 0) throw new IllegalArgumentException("games must be > 0");
        if (maxPlies < 0) throw new IllegalArgumentException("maxPlies must be >= 0");
        return new CliOptions(agent, normalizedOpponents, games, seeds, maxPlies,
                csvPath, summaryCsvPath, bothSides);
    }

    private static String requireValue(String name, String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("--" + name + " requires a value");
        return value;
    }
    private static int parseInt(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--" + name + " must be an integer: " + value); }
    }
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").trim();
    }
    private static void validateAgent(String agentType) {
        if (!"random".equals(agentType) && !"greedy".equals(agentType)
                && !"tactical".equals(agentType) && !"expecti".equals(agentType))
            throw new IllegalArgumentException("unsupported agent: " + agentType);
    }
    private static boolean containsHelp(String[] args) {
        for (String arg : args) if ("--help".equals(arg) || "-h".equals(arg)) return true;
        return false;
    }

    static String usage() {
        return """
                Usage: java -cp target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain [options]
                Options:
                  --agent expecti    --opponents random,greedy,tactical
                  --games 20         --seed 1 | --seeds 1,2,3
                  --maxPlies 200     --bothSides
                  --csv <path>       --summaryCsv <path>
                """;
    }

    // ================================================================
    // === 类型
    // ================================================================

    record CliOptions(String agent, String[] opponents, int games, long[] seeds,
                      int maxPlies, String csvPath, String summaryCsvPath, boolean bothSides) {}

    public record BenchmarkRow(
            String agent, String opponent, String side,
            int games, int wins, int losses, int draws,
            double winRate, double averagePlies, long seed, int maxPlies,
            int measuredMoves, int timeoutMoves, double timeoutRate,
            double avgNodesPerMove, double avgDepth,
            long ttHits, long ttStores, long ttCutoffs) {

        public BenchmarkRow {
            Objects.requireNonNull(agent); Objects.requireNonNull(opponent); Objects.requireNonNull(side);
            if (!"red".equals(side) && !"black".equals(side))
                throw new IllegalArgumentException("side: " + side);
            if (games < 0 || wins < 0 || losses < 0 || draws < 0 || maxPlies < 0
                    || measuredMoves < 0 || timeoutMoves < 0)
                throw new IllegalArgumentException("negative field");
            if (wins + losses + draws != games)
                throw new IllegalArgumentException("counts != games");
        }

        public String toCsv() {
            return String.join(",",
                    agent, opponent, side,
                    str(games), str(wins), str(losses), str(draws),
                    fmt4(winRate), fmt2(averagePlies),
                    str(seed), str(maxPlies),
                    str(measuredMoves), str(timeoutMoves), fmt4(timeoutRate),
                    fmt2(avgNodesPerMove), fmt2(avgDepth),
                    str(ttHits), str(ttStores), str(ttCutoffs));
        }
    }

    static final class SummaryAcc {
        private final String agent, opponent;
        private int totalGames, wins, losses, draws;
        private double totalPlies;
        private final java.util.Set<Long> seeds = new java.util.HashSet<>();
        SummaryAcc(String a, String o) { agent = a; opponent = o; }
        void add(BenchmarkRow r) {
            totalGames += r.games(); wins += r.wins(); losses += r.losses(); draws += r.draws();
            totalPlies += r.averagePlies() * r.games();
            seeds.add(r.seed());
        }
        double wr() { return totalGames == 0 ? 0.0 : (double) wins / totalGames; }
        double ap() { return totalGames == 0 ? 0.0 : totalPlies / totalGames; }
        String toCsv() {
            return String.join(",",
                    agent, opponent, str(totalGames),
                    str(wins), str(losses), str(draws),
                    fmt4(wr()), fmt2(ap()), str(seeds.size()));
        }
    }

    private static String str(long v) { return Long.toString(v); }
    private static String str(int v) { return Integer.toString(v); }
    private static String fmt4(double v) { return String.format(Locale.ROOT, "%.4f", v); }
    private static String fmt2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
