package jieqi.ai;

import jieqi.common.Move;
import jieqi.rules.BoardText;
import jieqi.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyAgentTest {

    @Test
    void prefersCaptureWhenAvailable() {
        BoardText.ParsedPosition position = BoardText.parse(
                "4k4/9/9/9/p8/4P4/9/9/9/R3K4 r");
        PlayerView view = PlayerView.of(position.board(), position.sideToMove());
        Agent agent = new GreedyAgent();

        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(1000));

        assertTrue(selected.isPresent(), "capture board should have legal moves");
        assertEquals(Move.parse("a0a5"), selected.orElseThrow());
    }

    @Test
    void prefersRookOverPawnWhenBothCanBeCaptured() {
        BoardText.ParsedPosition position = BoardText.parse(
                "4k4/9/9/4p4/r3R4/9/9/9/9/4K4 r");
        PlayerView view = PlayerView.of(position.board(), position.sideToMove());
        Agent agent = new GreedyAgent();

        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(1000));

        assertTrue(selected.isPresent(), "capture board should have legal moves");
        assertEquals(Move.parse("e5a5"), selected.orElseThrow());
    }

    @Test
    void returnsLegalMoveWhenNoCaptureIsAvailable() {
        BoardText.ParsedPosition initial = BoardText.parse(BoardText.INITIAL);
        PlayerView view = PlayerView.of(initial.board(), initial.sideToMove());
        Agent agent = new GreedyAgent();

        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(1000));

        assertTrue(selected.isPresent(), "initial board should have legal moves");
        Move move = selected.orElseThrow();
        assertTrue(
                RuleEngine.validate(initial.board(), view.sideToMove(), move.from(), move.to()).legal(),
                "GreedyAgent must return a move accepted by RuleEngine");
    }

    @Test
    void keepsStableOrderWhenCaptureValuesTie() {
        BoardText.ParsedPosition position = BoardText.parse(
                "3k5/9/9/9/p3R3p/9/9/9/9/4K4 r");
        PlayerView view = PlayerView.of(position.board(), position.sideToMove());
        Agent agent = new GreedyAgent();

        Optional<Move> first = agent.selectMove(view, TimeBudget.ofMillis(1000));
        Optional<Move> second = agent.selectMove(view, TimeBudget.ofMillis(1000));

        assertEquals(first, second);
        assertEquals(Move.parse("e5a5"), first.orElseThrow());
    }

    @Test
    void hiddenTargetUsesAverageUnknownValueWithoutTrueIdentity() {
        BoardText.ParsedPosition position = BoardText.parse(
                "4k4/9/9/4x4/4R4/9/9/9/9/4K4 r");
        PlayerView view = PlayerView.of(position.board(), position.sideToMove());
        PositionEvaluator evaluator = new PositionEvaluator();

        assertEquals(PositionEvaluator.UNKNOWN_HIDDEN_VALUE,
                evaluator.targetValue(view, Move.parse("e5e6").to()));
    }
}
