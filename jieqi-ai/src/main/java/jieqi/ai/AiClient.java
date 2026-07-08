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
import java.util.function.Consumer;

/**
 * Minimal WebSocket AI client skeleton.
 */
public final class AiClient {

    private static final Gson GSON = new Gson();

    private final AiClientConfig config;
    private final Agent agent;
    private Consumer<String> outbound;
    private volatile AiGameState gameState;
    private volatile MoveResultMessage lastMoveResult;
    private volatile Optional<Move> lastSelectedMove = Optional.empty();
    private volatile String lastMoveJson;

    public AiClient(AiClientConfig config, Agent agent) {
        this(config, agent, json -> {
            throw new IllegalStateException("client is not connected");
        });
    }

    public AiClient(AiClientConfig config, Agent agent, Consumer<String> outbound) {
        this.config = Objects.requireNonNull(config, "config");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
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
            if (gameState.firstHand()) {
                selectAndSendMove();
            }
        } else if ("moveresult".equals(messageType)) {
            lastMoveResult = AiProtocolCodec.parseMoveResult(json);
        }
    }

    public Optional<Move> selectAndSendMove() {
        AiGameState state = gameState;
        if (state == null) {
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

    private void send(JsonObject json) {
        outbound.accept(GSON.toJson(json));
    }

    private static JsonObject base(String messageType) {
        JsonObject json = new JsonObject();
        json.addProperty("messageType", messageType);
        return json;
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
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
            error.printStackTrace(System.err);
        }
    }
}
