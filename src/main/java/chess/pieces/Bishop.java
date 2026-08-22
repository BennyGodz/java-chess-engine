package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

/** The bishop: slides any distance along the four diagonals. */
public class Bishop extends Piece {

  public Bishop(boolean white) {
    super(white);
  }

  /** Returns 'B' for White and 'b' for Black. */
  @Override
  public char getSymbol() {
    return white ? 'B' : 'b';
  }

  /** A bishop is worth 330 centipawns. */
  @Override
  public int getValue() {
    return 330;
  }

  /** Generates the moves along all four diagonal directions until blocked. */
  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();

    addDirection(moves, position, board, -1, -1);
    addDirection(moves, position, board, -1, 1);
    addDirection(moves, position, board, 1, -1);
    addDirection(moves, position, board, 1, 1);

    return moves;
  }

  /**
   * Walks in one direction adding every empty square; stops at the first occupied square, adding a
   * capture if it holds an enemy (kings are excluded because they can never actually be captured).
   */
  private void addDirection(
      List<Move> moves, Position start, Board board, int rowStep, int columnStep) {
    int row = start.getRow() + rowStep;
    int column = start.getColumn() + columnStep;

    while (board.isValid(row, column)) {
      Position target = new Position(row, column);

      if (board.isEmpty(target)) {
        moves.add(new Move(start, target));
      } else {
        if (board.isEnemyPiece(target, white) && !(board.getPiece(target) instanceof King)) {
          moves.add(new Move(start, target));
        }
        break;
      }

      row += rowStep;
      column += columnStep;
    }
  }
}
