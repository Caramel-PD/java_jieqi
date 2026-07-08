package jieqi.ai;

import java.net.URI;
import java.util.Objects;

/**
 * Runtime settings for the AI WebSocket client.
 */
public record AiClientConfig(
        URI serverUrl,
        String userId,
        String password,
        String nickname,
        long thinkTimeMillis) {

    public static final URI DEFAULT_SERVER_URL = URI.create("ws://localhost:8887");
    public static final String DEFAULT_USER_ID = "ai";
    public static final String DEFAULT_PASSWORD = "ai";
    public static final String DEFAULT_NICKNAME = "AI";
    public static final long DEFAULT_THINK_TIME_MILLIS = 10_000L;

    public AiClientConfig {
        serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        userId = requireNonBlank(userId, "userId");
        password = requireNonBlank(password, "password");
        nickname = requireNonBlank(nickname, "nickname");
        if (thinkTimeMillis < 0) {
            throw new IllegalArgumentException("thinkTimeMillis must be >= 0");
        }
    }

    public static AiClientConfig defaults() {
        return new AiClientConfig(
                DEFAULT_SERVER_URL,
                DEFAULT_USER_ID,
                DEFAULT_PASSWORD,
                DEFAULT_NICKNAME,
                DEFAULT_THINK_TIME_MILLIS);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
