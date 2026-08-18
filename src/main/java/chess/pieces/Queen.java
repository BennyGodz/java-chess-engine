package chess.pieces;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;

import java.util.ArrayList;
import java.util.List;

public class Queen extends Piece {

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

    private void addDirection(
            List<Move> moves,
            Position start,
            Board board,
            int rowStep,
            int columnStep
    ) {
        int row = start.getRow() + rowStep;
        int column = start.getColumn() + columnStep;

        while (board.isValid(row, column)) {
            Position target = new Position(row, column);

            if (board.isEmpty(target)) {
                moves.add(new Move(start, target));
            } else {
                if (board.isEnemyPiece(target, white)
                        && !(board.getPiece(target) instanceof King)) {
                    moves.add(new Move(start, target));
                }
                break;
            }

            row += rowStep;
            column += columnStep;
        }
    }
}
