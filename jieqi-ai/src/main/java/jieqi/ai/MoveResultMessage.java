package jieqi.ai;

import jieqi.common.Move;
import jieqi.common.PieceType;

import java.util.Objects;
import java.util.Optional;

/**
 * AI-side representation of a moveResult message.
 */
public record MoveResultMessage(
        boolean valid,
        Move move,
        boolean isFlip,
        Optional<PieceType> flipResult,
        CapturedPiece capturedPiece) {

    public MoveResultMessage {
        move = Objects.requireNonNull(move, "move");
        flipResult = Objects.requireNonNull(flipResult, "flipResult");
        capturedPiece = Objects.requireNonNull(capturedPiece, "capturedPiece");
    }

    public record CapturedPiece(Kind kind, PieceType type) {
        public enum Kind {
            NONE,
            UNKNOWN,
            KNOWN
        }

        public CapturedPiece {
            kind = Objects.requireNonNull(kind, "kind");
            if (kind == Kind.KNOWN) {
                Objects.requireNonNull(type, "type");
            }
            if (kind != Kind.KNOWN && type != null) {
                throw new IllegalArgumentException("type only applies to known captured pieces");
            }
        }

        public static CapturedPiece none() {
            return new CapturedPiece(Kind.NONE, null);
        }

        public static CapturedPiece unknown() {
            return new CapturedPiece(Kind.UNKNOWN, null);
        }

        public static CapturedPiece known(PieceType type) {
            return new CapturedPiece(Kind.KNOWN, type);
        }

        public Optional<PieceType> knownType() {
            return Optional.ofNullable(type);
        }
    }
}
