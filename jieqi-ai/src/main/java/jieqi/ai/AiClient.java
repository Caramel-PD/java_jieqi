package jieqi.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jieqi.common.Json;
import jieqi.common.Move;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Minimal WebSocket AI client skeleton.
 */
public final class AiClient {

    private static final Gson GSON = new Gson();

    private final AiClientConfig config;
    private final Agent agent;
    private final Consumer<String> logger;
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    private Consumer<String> outbound;
    private volatile AiGameState gameState;
    private volatile MoveResultMessage lastMoveResult;
    private volatile Optional<Move> lastSelectedMove = Optional.empty();
    private volatile String lastMoveJson;
    private volatile boolean stopped;

    public AiClient(AiClientConfig config, Agent agent) {
        this(config, agent, json -> {
            throw new IllegalStateException("client is not connected");
        }, System.out::println);
    }

    public AiClient(AiClientConfig config, Agent agent, Consumer<String> outbound) {
        this(config, agent, outbound, ignored -> {
        });
    }

    public AiClient(AiClientConfig config, Agent agent, Consumer<String> outbound, Consumer<String> logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<WebSocket> connect() {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(config.serverUrl(), new Listener())
                .thenApply(webSocket -> {
                    outbound = json -> webSocket.sendText(json, true);
                    return webSocket;
                });
    }

    public void sendLogin() {
        JsonObject json = base("Login");
        json.addProperty("userId", config.userId());
        json.addProperty("password", config.password());
        send(json);
    }

    public void sendRegister() {
        JsonObject json = base("register");
        json.addProperty("userId", config.userId());
        json.addProperty("password", config.password());
        json.addProperty("nickname", config.nickname());
        send(json);
    }

    public void sendStartMatch() {
        send(base("startMatch"));
    }

    public void sendReady() {
        send(base("Ready"));
    }

    public void handleServerMessage(String json) {
        JsonObject object = Json.parseObject(json);
        String messageType = Json.messageType(object);
        if ("gamestart".equals(messageType)) {
            gameState = AiProtocolCodec.parseGameStart(json);
            log("game start");
            if (gameState.firstHand() && !stopped) {
                selectAndSendMove();
            }
        } else if ("loginresult".equals(messageType)) {
            if (Json.optBool(object, "success", false) && !stopped) {
                log("login success");
                sendStartMatch();
            } else {
                stop("login failed");
            }
        } else if ("registerresult".equals(messageType)) {
            if (Json.optBool(object, "success", false) && !stopped) {
                log("register success");
                sendStartMatch();
            } else {
                stop("register failed");
            }
        } else if ("matchsuccess".equals(messageType)) {
            if (!stopped) {
                log("match success");
                sendReady();
            }
        } else if ("moveresult".equals(messageType)) {
            lastMoveResult = AiProtocolCodec.parseMoveResult(json);
            log("move result");
            if (lastMoveResult.valid() && gameState != null && !stopped) {
                PlayerView updatedView = gameState.playerView().apply(lastMoveResult);
                gameState = new AiGameState(
                        gameState.redPlayerId(),
                        gameState.blackPlayerId(),
                        gameState.yourColor(),
                        gameState.firstHand(),
                        updatedView);
                if (updatedView.sideToMove() == gameState.yourColor()) {
                    selectAndSendMove();
                }
            }
        } else if ("gameover".equals(messageType) || "timeout".equals(messageType)) {
            stop("game over");
        }
    }

    public Optional<Move> selectAndSendMove() {
        AiGameState state = gameState;
        if (state == null || stopped || state.playerView().sideToMove() != state.yourColor()) {
            return Optional.empty();
        }
        Optional<Move> selected = agent.selectMove(
                state.playerView(),
                TimeBudget.ofMillis(config.thinkTimeMillis()));
        lastSelectedMove = selected;
        selected.ifPresent(move -> {
            String json = AiProtocolCodec.encodeMove(state.playerView(), move);
            lastMoveJson = json;
            outbound.accept(json);
            log("move sent");
        });
        return selected;
    }

    public Optional<AiGameState> gameState() {
        return Optional.ofNullable(gameState);
    }

    public Optional<MoveResultMessage> lastMoveResult() {
        return Optional.ofNullable(lastMoveResult);
    }

    public Optional<Move> lastSelectedMove() {
        return lastSelectedMove;
    }

    public Optional<String> lastMoveJson() {
        return Optional.ofNullable(lastMoveJson);
    }

    public boolean stopped() {
        return stopped;
    }

    public void awaitStopped() throws InterruptedException {
        stoppedLatch.await();
    }

    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stoppedLatch.await(timeout, unit);
    }

    private void send(JsonObject json) {
        outbound.accept(GSON.toJson(json));
    }

    private void stop(String message) {
        if (!stopped) {
            stopped = true;
            log(message);
            stoppedLatch.countDown();
        }
    }

    private void log(String message) {
        logger.accept(message);
    }

    private static JsonObject base(String messageType) {
        JsonObject json = new JsonObject();
        json.addProperty("messageType", messageType);
        return json;
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            outbound = json -> webSocket.sendText(json, true);
            log("connected");
            if (config.registerOnConnect()) {
                sendRegister();
            } else {
                sendLogin();
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleServerMessage(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("connection error: " + error.getMessage());
            stop("game over");
            error.printStackTrace(System.err);
        }
    }
}
