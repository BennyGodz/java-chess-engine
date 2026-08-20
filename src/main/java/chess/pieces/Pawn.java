package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

public class Pawn extends Piece {

  public Pawn(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'P' : 'p';
  }

  @Override
  public int getValue() {
    return 100;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();

    int direction = white ? -1 : 1;
    int startRow = white ? 6 : 1;
    int promotionRow = white ? 0 : 7;

    int oneRow = position.getRow() + direction;

    if (board.isValid(oneRow, position.getColumn())) {
      Position oneForward = new Position(oneRow, position.getColumn());

      if (board.isEmpty(oneForward)) {
        addPawnMove(moves, position, oneForward, promotionRow, white);

        if (position.getRow() == startRow) {
          int twoRow = position.getRow() + 2 * direction;
          Position twoForward = new Position(twoRow, position.getColumn());

          if (board.isEmpty(twoForward)) {
            moves.add(new Move(position, twoForward));
          }
        }
      }
    }

    for (int columnOffset : new int[] {-1, 1}) {
      int row = position.getRow() + direction;
      int column = position.getColumn() + columnOffset;

      if (!board.isValid(row, column)) {
        continue;
      }

      Position target = new Position(row, column);

      if (board.isEnemyPiece(target, white) && !(board.getPiece(target) instanceof King)) {
        addPawnMove(moves, position, target, promotionRow, white);
      }

      if (board.isEnPassantTarget(target)) {
        moves.add(new Move(position, target, null, false, true));
      }
    }

    return moves;
  }

  private void addPawnMove(
      List<Move> moves, Position start, Position end, int promotionRow, boolean white) {
    if (end.getRow() == promotionRow) {
      // The GUI/CLI can choose among these four moves.
      moves.add(new Move(start, end, new Queen(white), false, false));
      moves.add(new Move(start, end, new Rook(white), false, false));
      moves.add(new Move(start, end, new Bishop(white), false, false));
      moves.add(new Move(start, end, new Knight(white), false, false));
    } else {
      moves.add(new Move(start, end));
    }
  }
}
