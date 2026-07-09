package jieqi.ai;

import java.util.Locale;
import java.util.Objects;

/**
 * Aggregated statistics for a deterministic self-play experiment.
 */
public record SelfPlayResult(
        int games,
        String redAgent,
        String blackAgent,
        int redWins,
        int blackWins,
        int draws,
        int totalPlies) {

    public SelfPlayResult {
        if (games < 0) {
            throw new IllegalArgumentException("games must be >= 0");
        }
        redAgent = requireNonBlank(redAgent, "redAgent");
        blackAgent = requireNonBlank(blackAgent, "blackAgent");
        if (redWins < 0 || blackWins < 0 || draws < 0 || totalPlies < 0) {
            throw new IllegalArgumentException("statistics must be >= 0");
        }
        if (redWins + blackWins + draws != games) {
            throw new IllegalArgumentException("win/draw counts must add up to games");
        }
    }

    public double averagePlies() {
        return games == 0 ? 0.0 : (double) totalPlies / games;
    }

    public String toReport() {
        return """
                games=%d
                redAgent=%s
                blackAgent=%s
                redWins=%d
                blackWins=%d
                draws=%d
                averagePlies=%s
                """.formatted(
                games,
                redAgent,
                blackAgent,
                redWins,
                blackWins,
                draws,
                String.format(Locale.ROOT, "%.2f", averagePlies()));
    }

    static Builder builder(String redAgent, String blackAgent) {
        return new Builder(redAgent, blackAgent);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static final class Builder {
        private final String redAgent;
        private final String blackAgent;
        private int games;
        private int redWins;
        private int blackWins;
        private int draws;
        private int totalPlies;

        private Builder(String redAgent, String blackAgent) {
            this.redAgent = requireNonBlank(redAgent, "redAgent");
            this.blackAgent = requireNonBlank(blackAgent, "blackAgent");
        }

        void add(SelfPlayGame.PlayedGame game) {
            Objects.requireNonNull(game, "game");
            games++;
            totalPlies += game.plies();
            switch (game.winner()) {
                case RED -> redWins++;
                case BLACK -> blackWins++;
                case DRAW -> draws++;
            }
        }

        SelfPlayResult build() {
            return new SelfPlayResult(games, redAgent, blackAgent, redWins, blackWins, draws, totalPlies);
        }
    }
}
