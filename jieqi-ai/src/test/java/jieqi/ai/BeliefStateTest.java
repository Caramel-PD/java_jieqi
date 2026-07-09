package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.PieceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BeliefStateTest {

    @Test
    void initialPoolHasDesignCountsAndNoKing() {
        BeliefState belief = BeliefState.initial();

        assertEquals(0, belief.count(Color.RED, PieceType.KING));
        assertEquals(2, belief.count(Color.RED, PieceType.ROOK));
        assertEquals(2, belief.count(Color.RED, PieceType.KNIGHT));
        assertEquals(2, belief.count(Color.RED, PieceType.CANNON));
        assertEquals(5, belief.count(Color.RED, PieceType.PAWN));
        assertEquals(2, belief.count(Color.RED, PieceType.GUARD));
        assertEquals(2, belief.count(Color.RED, PieceType.BISHOP));
        assertEquals(2, belief.count(Color.BLACK, PieceType.ROOK));
        assertEquals(0.0, belief.probability(Color.RED, PieceType.KING));
        assertEquals(2.0 / 15.0, belief.probability(Color.RED, PieceType.ROOK), 1e-9);
    }

    @Test
    void initialExpectedValueIs204() {
        BeliefState belief = BeliefState.initial();

        assertEquals(204, belief.expectedValue(Color.RED));
        assertEquals(204, belief.expectedValue(Color.BLACK));
    }

    @Test
    void knownRevealShrinksPoolAndUpdatesExpectedValue() {
        BeliefState belief = BeliefState.initial();

        belief.recordKnownReveal(Color.RED, PieceType.ROOK);

        assertEquals(1, belief.count(Color.RED, PieceType.ROOK));
        assertEquals(1.0 / 14.0, belief.probability(Color.RED, PieceType.ROOK), 1e-9);
        assertEquals(176, belief.expectedValue(Color.RED));
        assertEquals(204, belief.expectedValue(Color.BLACK));
    }

    @Test
    void unknownRemovalDoesNotChangeExpectedValue() {
        BeliefState belief = BeliefState.initial();

        belief.recordUnknownRemoval(Color.RED);

        assertEquals(1, belief.unknownRemovals(Color.RED));
        assertEquals(2, belief.count(Color.RED, PieceType.ROOK));
        assertEquals(204, belief.expectedValue(Color.RED));
    }

    @Test
    void cannotRevealMorePiecesThanPoolContains() {
        BeliefState belief = BeliefState.initial();

        belief.recordKnownReveal(Color.RED, PieceType.ROOK);
        belief.recordKnownReveal(Color.RED, PieceType.ROOK);

        assertThrows(IllegalStateException.class,
                () -> belief.recordKnownReveal(Color.RED, PieceType.ROOK));
    }

    @Test
    void positionEvaluatorUsesDesignHiddenValue204() {
        assertEquals(204, PositionEvaluator.UNKNOWN_HIDDEN_VALUE);
    }
}
