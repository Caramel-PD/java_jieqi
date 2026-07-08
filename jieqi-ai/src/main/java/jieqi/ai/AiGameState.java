package jieqi.ai;

import jieqi.common.Color;

import java.util.Objects;

/**
 * AI-side state extracted from a server gameStart message.
 */
public record AiGameState(
        String redPlayerId,
        String blackPlayerId,
        Color yourColor,
        boolean firstHand,
        PlayerView playerView) {

    public AiGameState {
        redPlayerId = Objects.requireNonNull(redPlayerId, "redPlayerId");
        blackPlayerId = Objects.requireNonNull(blackPlayerId, "blackPlayerId");
        yourColor = Objects.requireNonNull(yourColor, "yourColor");
        playerView = Objects.requireNonNull(playerView, "playerView");
    }
}
