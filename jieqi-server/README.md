# jieqi-server(P1 · 进行中 🟡)

**状态**:约 40% 完成,能独立编译,**尚未接入父 POM 构建**(避免半成品拖垮 `mvn test`)。
这是 P1 的起点,不是成品。归属人见《分工文档》B(服务器)。

## 已有(可直接用)

- `Core.java`:配置总表 `ServerConfig`、传输通道抽象 `ClientChannel`(测试可注入 Fake)、
  `Session`、账户存储 `AccountStore`(sha256 + users.json)、抽池 `HiddenPool`、
  服务器落子流水线 `ServerBoard`(设计 §5.3-b:抽池翻子 + 揭示被吃暗子)。
- `Messages.java`:S→C 全部消息构造器(严出)、initialBoard 虚拟类型填充(§4.4)。

## 待做(设计 §6,约 60%)

1. `GameRoom` 房间状态机(§6.2)——`Core.Session.room` 当前是 `Object` 占位,实现后改回类型
2. `Lobby` 大厅:登录/注册/匹配/先手协商路由(§6.3)
3. WebSocket 传输层 + >1KB 帧断连(Q27)
4. `GameClock` 计时器 65s + 超时判负(§6.5)
5. 走子处理流水线九步串联(§6.4)
6. `GameRecorder` 棋谱双格式落盘(§9.2)
7. `ServerMain` 入口

代码内待做点已用 `// P1 待实现` 标注。所有规则判定(validate/apply/givesCheck/重复判定)
在 jieqi-rules 里已就绪,直接调用即可。

## 接入构建的方法

P1 完成到可测时,在父 `pom.xml` 的 `<modules>` 加入:

```xml
<module>jieqi-server</module>
```

本目录已备好 `pom.xml`(含 shade fat-jar 配置,主类 `jieqi.server.ServerMain`)。
依赖:jieqi-rules、Java-WebSocket 1.5.7、SLF4J、Gson(经 common 传递)。
