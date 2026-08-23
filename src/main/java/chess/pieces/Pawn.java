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
        addPawnMove(moves, position, oneForward, promotionRow);
        if (position.getRow() == startRow) {
          Position twoForward =
              new Position(position.getRow() + 2 * direction, position.getColumn());
          if (board.isEmpty(twoForward)) {
            moves.add(new Move(position, twoForward));
          }
        }
      }
    }

    for (int columnOffset : new int[] {-1, 1}) {
      int column = position.getColumn() + columnOffset;
      if (!board.isValid(oneRow, column)) continue;
      Position target = new Position(oneRow, column);
      if (!board.isEmpty(target) && canMoveTo(board, target)) {
        addPawnMove(moves, position, target, promotionRow);
      }
      if (board.isEnPassantTarget(target)) {
        moves.add(new Move(position, target, null, false, true));
      }
    }
    return moves;
  }

  private void addPawnMove(List<Move> moves, Position start, Position end, int promotionRow) {
    if (end.getRow() == promotionRow) {
      for (Piece piece :
          new Piece[] {new Queen(white), new Rook(white), new Bishop(white), new Knight(white)}) {
        moves.add(new Move(start, end, piece, false, false));
      }
    } else {
      moves.add(new Move(start, end));
    }
  }
}
