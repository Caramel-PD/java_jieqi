package jieqi.ai;

/**
 * Lightweight diagnostics from the latest ExpectiAgent search.
 */
public record SearchStats(
        int completedDepth,
        long searchedNodes,
        long betaCutoffs,
        long quiescenceNodes,
        long ttHits,
        long ttStores,
        long ttCutoffs,
        boolean timedOut) {

    public SearchStats {
        if (completedDepth < 0) {
            throw new IllegalArgumentException("completedDepth must be >= 0");
        }
        if (searchedNodes < 0 || betaCutoffs < 0 || quiescenceNodes < 0
                || ttHits < 0 || ttStores < 0 || ttCutoffs < 0) {
            throw new IllegalArgumentException("search counters must be >= 0");
        }
    }
}
