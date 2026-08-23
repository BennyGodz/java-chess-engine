package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.List;

public class Rook extends Piece {

  private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

  public Rook(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'R' : 'r';
  }

  @Override
  public int getValue() {
    return 500;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    return slidingMoves(position, board, DIRECTIONS);
  }
}
