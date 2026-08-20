package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.util.ArrayList;
import java.util.List;

public class King extends Piece {

  public King(boolean white) {
    super(white);
  }

  @Override
  public char getSymbol() {
    return white ? 'K' : 'k';
  }

  @Override
  public int getValue() {
    return 20000;
  }

  @Override
  public List<Move> generateMoves(Position position, Board board) {
    List<Move> moves = new ArrayList<>();

    for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
      for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
        if (rowOffset == 0 && columnOffset == 0) {
          continue;
        }

        int row = position.getRow() + rowOffset;
        int column = position.getColumn() + columnOffset;

        if (!board.isValid(row, column)) {
          continue;
        }

        Position target = new Position(row, column);

        if (board.isEmpty(target)
            || (board.isEnemyPiece(target, white) && !(board.getPiece(target) instanceof King))) {
          moves.add(new Move(position, target));
        }
      }
    }

    if (board.canCastleKingside(white)) {
      moves.add(new Move(position, new Position(position.getRow(), 6), null, true, false));
    }

    if (board.canCastleQueenside(white)) {
      moves.add(new Move(position, new Position(position.getRow(), 2), null, true, false));
    }

    return moves;
  }
}
