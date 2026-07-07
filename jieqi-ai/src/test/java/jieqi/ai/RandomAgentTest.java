package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomAgentTest {

    @Test
    void returnsLegalMoveOnInitialBoard() {
        BoardText.ParsedPosition initial = BoardText.parse(BoardText.INITIAL);
        PlayerView view = PlayerView.of(initial.board(), initial.sideToMove());
        Agent agent = new RandomAgent(20260707L);

        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(1000));

        assertTrue(selected.isPresent(), "initial board should have legal moves");
        Move move = selected.orElseThrow();
        assertTrue(
                RuleEngine.validate(initial.board(), view.sideToMove(), move.from(), move.to()).legal(),
                "RandomAgent must return a move accepted by RuleEngine");
    }

    @Test
    void returnsEmptyWhenNoLegalMoveExists() {
        Agent agent = new RandomAgent(1L);
        BoardSnapshot emptyBoard = BoardSnapshot.of(new jieqi.rules.CellState[10][9]);
        PlayerView view = PlayerView.of(emptyBoard, Color.RED);

        assertTrue(agent.selectMove(view, TimeBudget.ofMillis(1000)).isEmpty());
    }
}
