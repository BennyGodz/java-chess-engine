package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.List;

/**
 * Base class for all chess pieces.
 *
 * <p>A piece knows its colour and provides its display symbol, its material value and its pseudo
 * legal moves. Legality filtering (leaving your own king in check) happens elsewhere.
 */
public abstract class Piece {

  protected final boolean white;

  public Piece(boolean white) {
    this.white = white;
  }

  /** Returns whether this piece belongs to White. */
  public boolean isWhite() {
    return white;
  }

  /** Returns the FEN-style symbol, uppercase for White and lowercase for Black. */
  public abstract char getSymbol();

  /** Returns the material value of the piece in centipawns. */
  public abstract int getValue();

  /** Generates this piece's pseudo legal moves from the given square on the given board. */
  public abstract List<Move> generateMoves(Position position, Board board);

  /** Returns the letter used for this piece in algebraic notation ('N', 'B', 'R', 'Q', 'K'). */
  public char getNotationSymbol() {
    return Character.toUpperCase(getSymbol());
  }
}
