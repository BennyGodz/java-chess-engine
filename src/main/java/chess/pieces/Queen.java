package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

/** The queen: combines the rook's straight slides with the bishop's diagonal slides. */
public class Queen extends Piece {

  public Queen(boolean white) {
    super(white);
  }

  /** Returns 'Q' for White and 'q' for Black. */
  @Override
  public char getSymbol() {
    return white ? 'Q' : 'q';
  }

  /** A queen is worth 900 centipawns. */
  @Override
  public int getValue() {
    return 900;
  }

  /** Generates the moves along all eight directions until blocked. */
  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();

    addDirection(moves, position, board, -1, 0);
    addDirection(moves, position, board, 1, 0);
    addDirection(moves, position, board, 0, -1);
    addDirection(moves, position, board, 0, 1);
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
