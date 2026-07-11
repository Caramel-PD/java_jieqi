# AI 对战评测证据包（D-05）

## 构建

```bash
mvn package -pl jieqi-ai -am -DskipTests
```

## 运行评测

### 单边评测（agent 只执红）

```bash
java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
  --agent expecti \
  --opponents random,greedy,tactical \
  --games 20 \
  --seed 1 \
  --maxPlies 200 \
  --csv target/ai-benchmark.csv
```

### 双向评测（agent 红黑双方）

```bash
java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
  --agent expecti \
  --opponents random,greedy,tactical \
  --games 5 \
  --seeds 1,2,3 \
  --bothSides \
  --maxPlies 200 \
  --csv target/expecti-baseline-raw.csv \
  --summaryCsv target/expecti-baseline-summary.csv
```

### 多种子评测

使用 `--seeds 1,2,3` 替代 `--seed 1`，每个种子独立输出一组结果。
`--seed` 与 `--seeds` 不能同时使用。

## CSV 字段说明（原始 CSV）

| 字段 | 含义 |
|------|------|
| agent | 被测 Agent（如 expecti） |
| opponent | 对手 Agent |
| side | agent 执红=red / 执黑=black |
| games | 该组合总局数 |
| wins | agent 获胜局数 |
| losses | agent 落败局数 |
| draws | 和棋局数 |
| winRate | 胜率 = wins / games（始终从 agent 视角） |
| averagePlies | 平均半步数 |
| seed | 随机种子 |
| maxPlies | 每局最大半步数上限 |

## CSV 字段说明（汇总 CSV）

| 字段 | 含义 |
|------|------|
| agent | 被测 Agent |
| opponent | 对手 Agent |
| totalGames | 所有 side × seed 合计局数 |
| wins / losses / draws | 合计胜负平 |
| winRate | 总胜率 = wins / totalGames |
| averagePlies | 加权平均半步数 |
| seedCount | 使用的种子数 |

**winRate 始终从被测 agent 视角计算**：
- agent 执红时：wins = redWins
- agent 执黑时：wins = blackWins

## 当前样例数据参数

| 参数 | 值 |
|------|----|
| Agent | expecti |
| ExpectiAgent 最大深度 | 3 |
| 每步 TimeBudget | 200ms |
| maxPlies | 120 |
| seeds | 1, 2, 3 |
| 红黑双方 | bothSides |
| 每组合局数 | 5 |
| **总局数** | **3 opponents × 2 sides × 3 seeds × 5 games = 90 局** |

## 重要说明

**小样本只能证明评测流程可运行，不能证明最终胜率达标。**

当前样例每组合仅 5 局，胜率抖动极大（如 expecti vs tactical 种子 1 红方仅 40%，
种子 2 红方 100%）。最终 95%/80% 门槛需要扩大到设计文档规定的规模
（≥200 局、逐局交换先后手、二项检验 p<0.05）。

## 文件列表

- `expecti-baseline-raw.csv` — 按 seed × side 的原始逐行数据（程序生成）
- `expecti-baseline-summary.csv` — 按 agent+opponent 聚合的汇总数据（程序生成）
- `README.md` — 本文档
