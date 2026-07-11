# ExpectiAgent 正式双边评测报告（D-06 返工修正版）

**生成日期**：2026-07-11
**基准提交**：f61af3f（含 C-17 TT + C-18 协议更新）
**评测工具**：AiBenchmarkMain（StatsCapture 逐 move 统计）

---

## 评测配置

| 参数 | 值 |
|------|----|
| Agent | expecti |
| 搜索深度上限 | 3（ExpectiAgent config maxDepth） |
| 每步 TimeBudget | 200ms（SelfPlayGame 内置） |
| maxPlies | 200 |
| seeds | 1, 2, 3, 4, 5 |
| 红黑双方 | bothSides |

---

## 正式评测结果

| 对手 | 总局数 | 胜 | 负 | 和 | 胜率 | 平均半步 | 达标？ |
|------|--------|----|----|----|------|----------|--------|
| random | 400 (红200+黑200) | 398 | 2 | 0 | **99.50%** | 16.97 | ✅ >95% |
| greedy | 400 (红200+黑200) | 353 | 18 | 29 | **88.25%** | 45.40 | ✅ >80% |
| tactical | 200 (红100+黑100) | 153 | 47 | 0 | **76.50%** | 27.00 | ❌ <80% |

**总局数**：1,000 局

---

## 性能统计

| 对手 | measuredMoves | timeoutMoves | timeoutRate | avgDepth 范围 |
|------|---------------|-------------|-------------|---------------|
| random | 3,493 | 3,088 | 88.41% | 0.14–0.31 |
| greedy | 9,169 | 8,467 | 92.34% | 0.47–1.20 |
| tactical | 2,726 | 2,436 | 89.36% | 0.40–1.00 |

---

## 问题回答

### 1. timeoutMoves 是多少？
random: 3,088 / greedy: 8,467 / tactical: 2,436。合计 13,991 次着法超时预算。

### 2. measuredMoves 是多少？
random: 3,493 / greedy: 9,169 / tactical: 2,726。合计 15,388 次着法采集了 SearchStats。

### 3. timeoutRate 是多少？
约 88–92%。1000 局中绝大多数着法在 200ms 预算内未完成完整的迭代加深。

### 4. 为什么迭代加深通常会用完时间预算？
迭代加深的设计就是持续搜索更深层，直到时间预算耗尽，然后返回最后一个完整深度的结果。
`timedOut=true` **不是程序错误**，而是预期行为。真正需要关注的指标是 `completedDepth`。

### 5. “用完预算”是否属于异常？
**不属于异常**。timeoutRate 88–92% 说明 200ms 预算对当前搜索实现来说非常紧张，
约 88% 的着法在完成一次完整深度迭代前就耗尽了预算。
但这不意味着 AI 在随机走——它返回的是 quiescence search 或部分深度搜索的最佳结果。

### 6. 实际平均完成深度是多少？
avgDepth 范围 0.14–1.20，视对手而定。
- 对 random：0.14–0.31（大量着法连 depth 1 都未完整完成）
- 对 greedy：0.47–1.20（对局更长，搜索更充分）
- 对 tactical：0.40–1.00

`avgDepth` 表示每次 selectMove 最后完整完成的迭代加深层数的平均值。
当前数据中 **没有任何对手达到搜索深度上限 3**。

### 7. 是否有大量着法连 depth 1 都没有完整完成？
**是**。对 random 时 avgDepth 低至 0.14，说明大部分着法在 depth 1 的迭代中就超时了，
只能依赖 quiescence search 或未完成的 depth 1 评估。尽管如此，对 random 仍达 99.5% 胜率，
说明 random 的随机着法即使浅层搜索也足够碾压。

### 8. 下一步应优先提升搜索效率，还是增加最大深度？
**优先提升搜索效率**。当前 avgDepth 远低于 1，说明基础搜索效率是瓶颈。
在 200ms 内连 depth 1 都不能稳定完成的情况下，增加最大深度毫无意义。

具体建议：
- 审查迭代加深的时间检查频率（当前可能检查间隔过大导致迟迟不退出）
- 优化走法排序（减少无谓节点展开）
- 增加 quiescence search 深度（当前 shallow depth 下 q-search 质量至关重要）
- 待 avgDepth 稳定 ≥ 2 后再考虑增加最大深度

---

## 设计文档验收目标

| 目标 | 实际 | 达标？ |
|------|------|--------|
| L2 > L0 (random) ≥ 95% | 99.50% | ✅ |
| L2 > L1 (greedy) ≥ 80% | 88.25% | ✅ |
| Tactical | 76.50% | ❌ 不属设计文档强制门槛 |
| 200 局基准 | 200–400 局 | ✅ |

Tactical 胜率 76.5% 低于 80%，**但不属于设计文档强制的 Random/Greedy 两项门槛**。
Tactical 是更强的 baseline，需要后续 C 团队优化后重新验证。

---

## 数据文件

| 文件 | 行数 | 说明 |
|------|------|------|
| formal-current-raw.csv | 30 行 | 逐 seed × side（含 measuredMoves/timeoutMoves/timeoutRate） |
| formal-current-summary.csv | 3 行 | 按对手聚合 |
| formal-current-report.md | — | 本报告 |

---

## 免责声明

本评测基于 5 个 seed、200ms TimeBudget、maxPlies=200。结果反映当前 ExpectiAgent 实际水平。
timeoutRate 88–92% 为迭代加深的正常行为，不代表程序缺陷。
Tactical 76.5% 不满足 80%，需后续优化后重测，但设计文档两项强制门槛均已通过。
