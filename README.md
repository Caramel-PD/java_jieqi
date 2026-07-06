# jieqi —— 揭棋大作业（AI 博弈方向）

对应《揭棋AI博弈_设计文档_v1.0》§5.1 模块规划与 §10.1 测试策略。

**当前状态:P0 已完成**(jieqi-common + jieqi-rules 全量实现,判例测试 65/65 全绿)。
本仓库另含 P1 服务器半成品(进行中,详见 `jieqi-server/README.md` 与 `交付进度说明.md`)。

## 模块地图（设计文档 §5.1）

| 模块 | 内容 | 状态 |
|---|---|---|
| `jieqi-common` | Coord / Color / PieceType / Move / GameOverReason / ErrorCode / Json | 已完成 |
| `jieqi-rules` | 规则引擎 + 判例测试(本仓库重点) | 已完成,65 测试全绿 |
| `jieqi-server` | WS 服务器 | P1 进行中(未接入父 POM 构建) |
| `jieqi-ai` / `jieqi-client` | AI、客户端 | P2/P3 待做 |

`jieqi-rules` 全部已实现:`RuleEngine`(走法生成/合法性校验/照面检测/将军检测/吃王判据)、
`RepetitionTracker`(长将/长捉/80 半步状态机)、`BoardSnapshot`(不可变棋盘 + 机械 apply)、
`BoardText`(FEN 式局面文本)、`InitialLayout`(暗子虚拟类型唯一权威)、`ZobristHash`、
`CellState`/`Legality`/`RepetitionVerdict`。均为纯函数,无网络/线程/随机——服务器裁判与
AI 走法生成共用同一套,杜绝"服务器认可而 AI 不认可"(设计 §3.4-4 规则单点原则)。

## 运行(重要:多模块工程从根目录构建)

本工程是 Maven 多模块工程,`jieqi-rules` 依赖 `jieqi-common`。**必须在根目录构建**,
让 Maven reactor 按依赖顺序先编 common 再编 rules:

```bash
# 正确:根目录跑,reactor 自动处理模块间依赖
mvn test

# 也可:只测这两个模块,-am 表示"同时构建被依赖的模块"(common)
mvn test -pl jieqi-common,jieqi-rules -am
```

不要单独进 `jieqi-rules/` 子目录跑 `mvn test`——那样 Maven 会去本地仓库找
`jieqi-common` 的 jar,而它尚未 install 过,会报"找不到 jieqi-common"而失败。
根目录 reactor 构建不需要预先 install。

期望输出:`Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`。

### 无 Maven 环境的最小验证(javac + JUnit standalone)

```bash
# 1) 先编 common,打成 jar
javac --release 17 -encoding UTF-8 -d out/common jieqi-common/src/main/java/jieqi/common/*.java
(cd out/common && jar cf ../jieqi-common.jar .)
# 2) rules 用该 jar 作依赖编译
javac --release 17 -encoding UTF-8 -cp out/jieqi-common.jar -d out/rules jieqi-rules/src/main/java/jieqi/rules/*.java
# 3) 编译并运行测试(JUnit 5 standalone jar)
javac --release 17 -encoding UTF-8 -cp out/jieqi-common.jar:out/rules:junit-platform-console-standalone.jar -d out/test jieqi-rules/src/test/java/jieqi/rules/*.java
java -jar junit-platform-console-standalone.jar -cp out/jieqi-common.jar:out/rules:out/test --scan-classpath
```

> 注:common 的 `Json` 工具依赖 Gson。用 Maven 时自动拉取;手动 javac 时 classpath 需加
> gson jar。若只想验证纯规则(不碰 Json),rules 模块本身不依赖 Gson。

## 判例测试:65 个,全绿(已实测)

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `InfrastructureTest` | 6 | 局面文本往返、子力普查、虚拟类型、apply、Zobrist |
| `MoveLegalityTest` | 41 | 附录 B 判例 1-14:暗子虚拟走法、士象强化、蹩腿/象眼/炮架、兵、照面、送将、from==to |
| `MoveGenerationTest` | 3 | 开局双方各 44 步(核算表在测试 javadoc)、照面剔除、送将保留 |
| `KingCaptureTest` | 2 | 吃将终局判据(Q2) |
| `CheckDetectionTest` | 4 | 将军检测(车/炮/马) |
| `RepetitionTrackerTest` | 8 | 长将判负、长捉、兵卒长捉和、80 半步、中断清零(Q3/Q4/Q5) |
| `RepetitionIntegrationTest` | 1 | 真实棋盘长将全流程 |

关键裁定全部对齐教师问答 Q1-Q45:送将合法、照面非法、不允许原地翻子、翻子不算吃子、
兵卒长捉判和等,均有对应测试锁定。

## 判例映射到设计文档附录 B

| 附录 B | 判例 | 落点 |
|---|---|---|
| 1-14 | 走子合法性(暗子虚拟类型、士象强化、蹩腿/象眼/炮架、兵、照面、送将、from==to) | `MoveLegalityTest`(用例名含 Bnn) |
| 15 | 吃将终局 checkmate | `KingCaptureTest` |
| 19-24 | 80 半步 / 长将 / 长捉全分支(Q3/Q4/Q5) | `RepetitionTrackerTest`(B 编号在 DisplayName) |
| 16-18 | 轮次 2002 / 超时 2003 / 迟到 move | 延后 jieqi-server(TurnEngine / GameClock) |
| 25-28 | 抽池守恒、差异化广播、明子被吃广播、1KB 帧防护 | 延后 jieqi-server(HiddenPool / Broadcaster / WS 层) |

## 局面文本速查（BoardText）

10 行自 rank9(黑)至 rank0(红)以 `/` 分隔;数字=连续空格;
明子 `K R N C P G B`(帅车马炮兵仕相,大写红/小写黑);`X/x`=暗子(只允许在暗子初始占位格,
违者解析即抛异常——这是"暗子恒在原始格"的护栏);末尾 ` r`/` b` 为行棋方。

开局:`xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX r`

## 最易写错的裁定(接手 P1/P2 前先背下来)

- 送将 / 不应将合法(Q2);照面非法(Q7);终局判据 = 王被实际吃掉。
- 不允许原地翻子(Q6/Q11) => 暗子恒在原始格 => 虚拟类型查表即得;`from==to` 一律 2001。
- 翻子不算吃子(Q3):不重置 80 半步计数、不清长将长捉 streak。
- 长将/长捉必须连续,中断清零(Q4);兵卒长捉->和、兵卒长将->负(Q5)。
- 明士可离宫过河、明相可过河,塞象眼始终有效;暗士限宫、暗象天然不过河(§2.4)。
