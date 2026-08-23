package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.List;

public class Bishop extends Piece {

  private static final int[][] DIRECTIONS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

  public Bishop(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'B' : 'b';
  }

  @Override
  public int getValue() {
    return 330;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    return slidingMoves(position, board, DIRECTIONS);
  }
}
