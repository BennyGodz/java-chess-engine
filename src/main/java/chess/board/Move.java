package chess.board;

import chess.pieces.Piece;

public class Move {

    private final Position start;
    private final Position end;
    private final Piece promotionPiece;
    private final boolean castling;
    private final boolean enPassant;

    public Move(Position start, Position end) {
        this(start, end, null, false, false);
    }

    public Move(
            Position start,
            Position end,
            Piece promotionPiece,
            boolean castling,
            boolean enPassant
    ) {
        this.start = start;
        this.end = end;
        this.promotionPiece = promotionPiece;
        this.castling = castling;
        this.enPassant = enPassant;
    }

    public Position getStart() {
        return start;
    }

    public Position getEnd() {
        return end;
    }

    public Piece getPromotionPiece() {
        return promotionPiece;
    }

    public boolean isPromotion() {
        return promotionPiece != null;
    }

    public boolean isCastling() {
        return castling;
    }

    public boolean isEnPassant() {
        return enPassant;
    }

    @Override
    public String toString() {
        return start + " -> " + end;
    }
}
