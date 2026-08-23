package chess.engine;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.pieces.Piece;

import java.util.ArrayList;
import java.util.List;

public class MoveGenerator {

    public List<Move> generateLegalMoves(Piece piece, Position position, Board board) {
        List<Move> legalMoves = new ArrayList<>();

        if (piece == null) {
            return legalMoves;
        }

        for (Move move : piece.generateMoves(position, board)) {
            Board copy = board.copyAndMakeMoveForValidation(move);

            if (!copy.isInCheck(piece.isWhite())) {
                legalMoves.add(move);
            }
        }

        return legalMoves;
    }
}
