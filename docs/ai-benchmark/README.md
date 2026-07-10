# AI 对战评测证据包

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
  --csv target/bench-raw.csv
```

### 双方评测（agent 执红 + 执黑）

```bash
java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
  --agent expecti \
  --opponents random,greedy,tactical \
  --games 20 \
  --seed 1 \
  --maxPlies 200 \
  --bothSides \
  --csv target/bench-raw.csv
```

### 多种子评测

```bash
java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
  --agent expecti \
  --opponents random,greedy,tactical \
  --games 20 \
  --seeds 1,2,3 \
  --bothSides \
  --maxPlies 200 \
  --csv target/bench-raw.csv \
  --summaryCsv target/bench-summary.csv
```

## CSV 字段说明

### 原始 CSV（expecti-baseline-raw.csv）

| 字段 | 含义 |
|------|------|
| agent | 被测 Agent 名称 |
| opponent | 对手 Agent 名称 |
| side | agent 执红(`red`)还是执黑(`black`) |
| games | 对局数 |
| wins | agent 胜局数 |
| losses | agent 负局数 |
| draws | 和局数 |
| winRate | agent 胜率 = wins / games（始终从 agent 视角） |
| averagePlies | 平均半步数 |
| seed | 随机种子 |
| maxPlies | 单局最大半步数限制 |

### 汇总 CSV（expecti-baseline-summary.csv）

| 字段 | 含义 |
|------|------|
| agent | 被测 Agent |
| opponent | 对手 Agent |
| totalGames | 合计总局数（红黑双方 + 全部种子） |
| wins | 合计胜局 |
| losses | 合计负局 |
| draws | 合计和局 |
| winRate | 合计胜率 |
| averagePlies | 合计平均半步数 |
| seedCount | 包含的种子组合数 |

## winRate 视角说明

**winRate 始终从被测 agent 视角计算**：

- agent 执红时：`winRate = 红胜数 / games`
- agent 执黑时：`winRate = 黑胜数 / games`

这保证无论是单边还是双向评测，winRate 始终反映被测 agent 的表现。

## 样本说明

本目录下的 CSV 为小规模样例（5局 × 3种子 × 2方向 = 每对手30局），
**仅证明流程可运行，不能证明最终胜率达标**。

最终 95% / 80% 门槛需要按设计文档 §8.9 的规模（200局 + 逐局换先）进行正式评测。

## 生成命令

```bash
java -cp jieqi-ai/target/jieqi-ai.jar jieqi.ai.AiBenchmarkMain \
  --agent expecti \
  --opponents random,greedy,tactical \
  --games 5 \
  --seeds 1,2,3 \
  --bothSides \
  --maxPlies 120 \
  --csv docs/ai-benchmark/expecti-baseline-raw.csv \
  --summaryCsv docs/ai-benchmark/expecti-baseline-summary.csv
```
