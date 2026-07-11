# C-19 ExpectiAgent Search Efficiency Comparison

## Bottleneck Check

Before changing the search code, a 200 ms self-play sample was profiled with `SearchStats`
and Java stack sampling.

- `avgDepth` was often below 1.0 and timeout rate was commonly above 85%.
- Stack samples were concentrated in `ExpectiAgent.quiescence`, hidden chance branches,
  `MoveOrderer.order`, and repeated `RuleEngine.givesCheck` calls triggered by sorting.
- The previous transposition table key was built with `BoardText.format(...)`, so every
  probe/store allocated a long FEN-style string.

The implemented changes keep the same legal move set and information boundary:

- TT keys now use an exact compact `PositionKey` with packed board cells, side to move,
  belief counts, and unknown removals.
- Move ordering computes each move score once per ordering pass instead of recomputing it
  during comparator calls.
- Iterative deepening uses a conservative next-depth time guard so it does not start a new
  depth when the remaining budget is clearly too small.

No fixed candidate truncation was added.

## Fixed Position Results

Command shape:

```bash
java -cp jieqi-ai/target/classes;jieqi-rules/target/classes;jieqi-common/target/classes;target/c19-tools jieqi.ai.C19PositionBenchmark
```

Budget: `TimeBudget.ofMillis(200)` for each position.

| case | before ms | after ms | before depth | after depth | before nodes | after nodes | before qNodes | after qNodes | legal |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| opening | 200 | 201 | 0 | 0 | 1 | 1 | 411 | 675 | true |
| hidden-midgame | 200 | 181 | 1 | 2 | 1423 | 1507 | 1536 | 1626 | true |
| capture-rich | 200 | 117 | 1 | 2 | 134 | 143 | 1067 | 1231 | true |
| king-threat | 4 | 2 | 3 | 3 | 65 | 65 | 56 | 56 | true |
| endgame | 0 | 0 | 3 | 3 | 27 | 27 | 17 | 17 | true |
| high-branch | 33 | 23 | 3 | 3 | 137 | 137 | 275 | 275 | true |
| hidden-capture | 4 | 3 | 3 | 3 | 169 | 169 | 138 | 138 | true |
| mixed-black | 8 | 3 | 3 | 3 | 140 | 140 | 137 | 137 | true |
| tt-repeat | 9 | 9 | 3 | 3 | 272 | 272 | 251 | 251 | true |
| tactical-file | 16 | 7 | 3 | 3 | 229 | 229 | 198 | 198 | true |

Average elapsed time improved from `67.4 ms` to `54.6 ms`.
Average completed depth improved from `2.3` to `2.5`.

The opening position still cannot finish depth 1 inside 200 ms because the initial board has
many hidden chance branches. It stays bounded and returns a legal move.

## Regression Smoke Check

Command:

```bash
java -cp jieqi-ai/target/classes;jieqi-rules/target/classes;jieqi-common/target/classes jieqi.ai.AiBenchmarkMain --agent expecti --opponents random,greedy,tactical --games 9 --seeds 1,2,3 --maxPlies 120 --bothSides --csv target/c19-regression-raw.csv --summaryCsv docs/ai-benchmark/c19-regression-summary.csv
```

This runs 54 games per opponent with red/black balanced and the existing 200 ms self-play
budget.

| opponent | games | wins | losses | draws | winRate |
|---|---:|---:|---:|---:|---:|
| random | 54 | 54 | 0 | 0 | 1.0000 |
| greedy | 54 | 48 | 5 | 1 | 0.8889 |
| tactical | 54 | 41 | 10 | 3 | 0.7593 |

Grouped performance stats from the same run:

| opponent | avgDepth | timeoutRate | avgNodes |
|---|---:|---:|---:|
| random | 0.455 | 0.729 | 329.20 |
| greedy | 0.808 | 0.682 | 442.33 |
| tactical | 1.130 | 0.537 | 560.63 |

Random remains above 95%, Greedy remains above 80%, and Tactical is close to the D-06
formal baseline of 76.50% in this smaller smoke sample.
