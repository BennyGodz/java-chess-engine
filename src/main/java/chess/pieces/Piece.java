package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.List;

public abstract class Piece {

  protected final boolean white;

  public Piece(boolean white) {
    this.white = white;
  }

  public boolean isWhite() {
    return white;
  }

  public abstract char getSymbol();

  public abstract int getValue();

  public abstract List<Move> generateMoves(Position position, Board board);

  public char getNotationSymbol() {
    return Character.toUpperCase(getSymbol());
  }
}
