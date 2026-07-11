package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveOrdererTest {

    private final MoveOrderer orderer = new MoveOrderer();

    @Test
    void kingCaptureIsOrderedFirst() {
        BoardText.ParsedPosition position = BoardText.parse("""
                4k4/9/9/9/9/9/9/9/9/K3R4 r
                """);

        List<Move> ordered = ordered(position);

        assertEquals(Move.parse("e0e9"), ordered.get(0));
    }

    @Test
    void highValueCapturePrecedesQuietMove() {
        BoardText.ParsedPosition position = BoardText.parse("""
                4k4/9/9/9/9/9/4P4/r8/9/R3K4 r
                """);

        List<Move> ordered = ordered(position);

        assertTrue(ordered.indexOf(Move.parse("a0a2")) < ordered.indexOf(Move.parse("a0a1")));
    }

    @Test
    void hiddenVictimUsesBeliefExpectedValue() {
        BoardText.ParsedPosition position = BoardText.parse("""
                4k4/9/9/x8/9/9/4P4/9/9/R3K4 r
                """);

        List<Move> ordered = ordered(position);

        assertTrue(ordered.indexOf(Move.parse("a0a6")) < ordered.indexOf(Move.parse("a0a1")));
    }

    @Test
    void orderingKeepsEveryLegalMove() {
        BoardText.ParsedPosition position = BoardText.parse(BoardText.INITIAL);
        BoardSnapshot board = position.board();
        List<Move> legalMoves = RuleEngine.generateLegalMoves(board, position.sideToMove());

        List<Move> ordered = orderer.order(board, position.sideToMove(), legalMoves, BeliefState.initial());

        assertEquals(legalMoves.size(), ordered.size());
        assertEquals(new HashSet<>(legalMoves), new HashSet<>(ordered));
    }

    @Test
    void moveOrderingDoesNotChangeLegalMoveSet() {
        BoardText.ParsedPosition position = BoardText.parse("""
                r3k3r/9/9/9/9/9/9/9/9/R3K3R r
                """);
        BoardSnapshot board = position.board();
        List<Move> legalMoves = RuleEngine.generateLegalMoves(board, position.sideToMove());

        List<Move> ordered = orderer.order(board, position.sideToMove(), legalMoves, BeliefState.initial());

        assertEquals(legalMoves.size(), ordered.size());
        assertEquals(new HashSet<>(legalMoves), new HashSet<>(ordered));
    }

    @Test
    void orderingIsDeterministic() {
        BoardText.ParsedPosition position = BoardText.parse(BoardText.INITIAL);
        BoardSnapshot board = position.board();
        List<Move> legalMoves = RuleEngine.generateLegalMoves(board, position.sideToMove());
        BeliefState belief = BeliefState.initial();

        List<Move> first = orderer.order(board, Color.RED, legalMoves, belief);
        List<Move> second = orderer.order(board, Color.RED, legalMoves, belief);

        assertEquals(first, second);
    }

    private List<Move> ordered(BoardText.ParsedPosition position) {
        return orderer.order(
                position.board(),
                position.sideToMove(),
                RuleEngine.generateLegalMoves(position.board(), position.sideToMove()),
                BeliefState.initial());
    }
}
