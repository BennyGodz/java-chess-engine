package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

public class Knight extends Piece {

  private static final int[][] OFFSETS = {
    {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}
  };

  public Knight(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'N' : 'n';
  }

  @Override
  public int getValue() {
    return 320;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();
    for (int[] offset : OFFSETS) {
      int row = position.getRow() + offset[0];
      int column = position.getColumn() + offset[1];
      if (!board.isValid(row, column)) continue;
      Position target = new Position(row, column);
      if (canMoveTo(board, target)) moves.add(new Move(position, target));
    }
    return moves;
  }
}
