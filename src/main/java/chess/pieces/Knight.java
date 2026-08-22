package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

/** The knight: jumps in an L-shape and can never be blocked. */
public class Knight extends Piece {

  public Knight(boolean white) {
    super(white);
  }

  /** Returns 'N' for White and 'n' for Black. */
  @Override
  public char getSymbol() {
    return white ? 'N' : 'n';
  }

  /** A knight is traditionally worth slightly more than a bishop: 320 centipawns. */
  @Override
  public int getValue() {
    return 320;
  }

  /** Generates the moves to all eight knight destinations on the board. */
  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();

    int[][] offsets = {
      {-2, -1}, {-2, 1},
      {-1, -2}, {-1, 2},
      {1, -2}, {1, 2},
      {2, -1}, {2, 1}
    };

    for (int[] offset : offsets) {
      int row = position.getRow() + offset[0];
      int column = position.getColumn() + offset[1];

      if (!board.isValid(row, column)) {
        continue;
      }

      Position target = new Position(row, column);

      if (board.isEmpty(target)
          || (board.isEnemyPiece(target, white) && !(board.getPiece(target) instanceof King))) {
        moves.add(new Move(position, target));
      }
    }

    return moves;
  }
}
