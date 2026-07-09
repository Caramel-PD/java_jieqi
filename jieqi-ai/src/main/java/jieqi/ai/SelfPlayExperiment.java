package jieqi.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Batch self-play experiment output for report-friendly CSV data.
 */
public record SelfPlayExperiment(List<SelfPlayResult> results, long seed, int maxPlies) {

    public static final String CSV_HEADER =
            "redAgent,blackAgent,games,redWins,blackWins,draws,averagePlies,seed,maxPlies";

    private static final String[] AGENTS = {"random", "greedy", "tactical", "expecti"};

    public SelfPlayExperiment {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
    }

    public static SelfPlayExperiment matrix(int games, long seed, int maxPlies) {
        SelfPlayResult[] rows = new SelfPlayResult[AGENTS.length * AGENTS.length];
        int index = 0;
        for (String red : AGENTS) {
            for (String black : AGENTS) {
                rows[index++] = SelfPlayMain.run(new SelfPlayMain.CliOptions(games, red, black, seed, maxPlies));
            }
        }
        return new SelfPlayExperiment(List.of(rows), seed, maxPlies);
    }

    public String toCsv() {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (SelfPlayResult result : results) {
            csv.append(result.toCsvRow(seed, maxPlies)).append('\n');
        }
        return csv.toString();
    }

    public void writeCsv(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, toCsv(), StandardCharsets.UTF_8);
    }
}
