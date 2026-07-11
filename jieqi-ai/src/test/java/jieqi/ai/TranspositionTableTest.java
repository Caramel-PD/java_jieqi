package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranspositionTableTest {

    @Test
    void exactEntryCanBeReused() {
        TranspositionTable table = new TranspositionTable(8);
        TranspositionTable.PositionKey key = keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());
        Move bestMove = Move.parse("e1e2");

        table.store(new TranspositionEntry(key, 3, 42, BoundType.EXACT, bestMove));

        TranspositionTable.ProbeResult result = table.probe(key, 2, -100, 100);

        assertTrue(result.hit());
        assertTrue(result.cutoff());
        assertEquals(42, result.score());
        assertEquals(bestMove, result.bestMove().orElseThrow());
    }

    @Test
    void shallowEntryCannotReplaceDeepSearch() {
        TranspositionTable table = new TranspositionTable(8);
        TranspositionTable.PositionKey key = keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());

        table.store(new TranspositionEntry(key, 1, 10, BoundType.EXACT, Move.parse("e1e2")));

        TranspositionTable.ProbeResult result = table.probe(key, 3, -100, 100);

        assertFalse(result.hit());
    }

    @Test
    void exactEntryAtSameDepthKeepsStrongerBound() {
        TranspositionTable table = new TranspositionTable(8);
        TranspositionTable.PositionKey key = keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());

        table.store(new TranspositionEntry(key, 3, 42, BoundType.EXACT, Move.parse("e1e2")));
        table.store(new TranspositionEntry(key, 3, 7, BoundType.LOWER, Move.parse("e1e0")));

        TranspositionTable.ProbeResult result = table.probe(key, 3, -100, 100);

        assertTrue(result.cutoff());
        assertEquals(42, result.score());
    }

    @Test
    void lowerBoundRaisesAlphaOnly() {
        TranspositionTable table = new TranspositionTable(8);
        TranspositionTable.PositionKey key = keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());

        table.store(new TranspositionEntry(key, 3, 30, BoundType.LOWER, Move.parse("e1e2")));

        TranspositionTable.ProbeResult result = table.probe(key, 3, 0, 100);

        assertTrue(result.hit());
        assertFalse(result.cutoff());
        assertEquals(30, result.alpha());
        assertEquals(100, result.beta());
    }

    @Test
    void upperBoundLowersBetaOnly() {
        TranspositionTable table = new TranspositionTable(8);
        TranspositionTable.PositionKey key = keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());

        table.store(new TranspositionEntry(key, 3, 70, BoundType.UPPER, Move.parse("e1e2")));

        TranspositionTable.ProbeResult result = table.probe(key, 3, 0, 100);

        assertTrue(result.hit());
        assertFalse(result.cutoff());
        assertEquals(0, result.alpha());
        assertEquals(70, result.beta());
    }

    @Test
    void beliefStateIsPartOfKey() {
        TranspositionTable.PositionKey initialKey =
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());
        BeliefState changed = BeliefState.initial();

        changed.recordKnownReveal(Color.RED, PieceType.ROOK);
        changed.recordUnknownRemoval(Color.BLACK);

        TranspositionTable.PositionKey changedKey =
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", changed);

        assertNotEquals(initialKey, changedKey);
    }

    @Test
    void sideToMoveIsPartOfKey() {
        TranspositionTable.PositionKey redToMove =
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());
        TranspositionTable.PositionKey blackToMove =
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 b", BeliefState.initial());

        assertNotEquals(redToMove, blackToMove);
    }

    @Test
    void compactKeyKeepsDifferentBoardsSeparate() {
        TranspositionTable.PositionKey first =
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial());
        TranspositionTable.PositionKey second =
                keyOf("4k4/9/9/9/9/9/9/4P4/9/4K4 r", BeliefState.initial());

        assertNotEquals(first, second);
        assertFalse(first.toString().contains("/"));
    }

    @Test
    void tableCapacityIsBounded() {
        TranspositionTable table = new TranspositionTable(2);

        table.store(new TranspositionEntry(
                keyOf("4k4/9/9/9/9/9/9/9/4P4/4K4 r", BeliefState.initial()),
                1,
                1,
                BoundType.EXACT,
                null));
        table.store(new TranspositionEntry(
                keyOf("4k4/9/9/9/9/9/9/4P4/9/4K4 r", BeliefState.initial()),
                1,
                2,
                BoundType.EXACT,
                null));
        table.store(new TranspositionEntry(
                keyOf("4k4/9/9/9/9/4P4/9/9/9/4K4 r", BeliefState.initial()),
                1,
                3,
                BoundType.EXACT,
                null));

        assertTrue(table.size() <= 2);
        assertTrue(table.generation() > 0);
    }

    private static TranspositionTable.PositionKey keyOf(String boardText, BeliefState belief) {
        BoardText.ParsedPosition position = BoardText.parse(boardText);
        return TranspositionTable.positionKey(position.board(), position.sideToMove(), belief);
    }
}
