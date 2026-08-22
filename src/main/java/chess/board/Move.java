package chess.board;

import chess.pieces.Piece;

/**
 * A single chess move from one square to another.
 *
 * <p>Special moves carry extra information: the promotion piece for promotions, and flags for
 * castling and en passant captures.
 */
public class Move {

  private final Position start;
  private final Position end;
  private final Piece promotionPiece;
  private final boolean castling;
  private final boolean enPassant;

  /** Creates a plain move, such as a step or a capture. */
  public Move(Position start, Position end) {
    this(start, end, null, false, false);
  }

  /** Creates a move with full detail (promotion piece and special-move flags). */
  public Move(
      Position start, Position end, Piece promotionPiece, boolean castling, boolean enPassant) {
    this.start = start;
    this.end = end;
    this.promotionPiece = promotionPiece;
    this.castling = castling;
    this.enPassant = enPassant;
  }

  /** Returns the square the move starts from. */
  public Position getStart() {
    return start;
  }

  /** Returns the square the move ends on. */
  public Position getEnd() {
    return end;
  }

  /** Returns the piece being promoted to, or null for non-promotions. */
  public Piece getPromotionPiece() {
    return promotionPiece;
  }

  /** Returns whether this move promotes a pawn. */
  public boolean isPromotion() {
    return promotionPiece != null;
  }

  /** Returns whether this move is a castling move. */
  public boolean isCastling() {
    return castling;
  }

  /** Returns whether this move captures en passant. */
  public boolean isEnPassant() {
    return enPassant;
  }

  @Override
  public String toString() {
    return start + " -> " + end;
  }
}
