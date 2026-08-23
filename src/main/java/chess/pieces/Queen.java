package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.List;

public class Queen extends Piece {

  private static final int[][] DIRECTIONS = {
    {-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
  };

  public Queen(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'Q' : 'q';
  }

  @Override
  public int getValue() {
    return 900;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    return slidingMoves(position, board, DIRECTIONS);
  }
}
