package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

/** Base type for pieces; subclasses generate pseudo-legal moves. */
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

  protected boolean canMoveTo(Board board, Position target) {
    Piece piece = board.getPiece(target);
    return piece == null || (piece.isWhite() != white && !(piece instanceof King));
  }

  protected List<Move> slidingMoves(Position start, Board board, int[][] directions) {
    List<Move> moves = new ArrayList<>();
    for (int[] direction : directions) {
      int row = start.getRow() + direction[0];
      int column = start.getColumn() + direction[1];
      while (board.isValid(row, column)) {
        Position target = new Position(row, column);
        if (!canMoveTo(board, target)) break;
        moves.add(new Move(start, target));
        if (!board.isEmpty(target)) break;
        row += direction[0];
        column += direction[1];
      }
    }
    return moves;
  }
}
