package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

/**
 * Converts a chess position into NNUE input features.
 *
 * 12 piece types x 64 squares = 768 features
 *
 * Feature 768:
 *
 * 1 = White to move
 * 0 = Black to move
 *
 * Total:
 *
 * 769 inputs
 */
public class NNUEFeatureExtractor {

    public static final int PIECE_TYPES = 12;
    public static final int SQUARES = 64;
    public static final int INPUT_SIZE = 769;

    /**
     * Extract the board into NNUE features.
     */
    public double[] extract(Board board) {

        double[] features =
                new double[INPUT_SIZE];

        for (int row = 0; row < 8; row++) {

            for (int column = 0; column < 8; column++) {

                Piece piece =
                        board.getPiece(
                                new Position(row, column)
                        );

                if (piece == null) {
                    continue;
                }

                int pieceType =
                        getPieceType(piece);

                int square =
                        row * 8 + column;

                int index =
                        pieceType * 64 + square;

                features[index] = 1.0;
            }
        }

        /*
         * Side to move.
         *
         * 1 = White
         * 0 = Black
         */
        features[768] =
                board.isWhiteToMove()
                        ? 1.0
                        : 0.0;

        return features;
    }

    /**
     * Convert a piece into one of the 12 piece types.
     *
     * 0  = white pawn
     * 1  = white knight
     * 2  = white bishop
     * 3  = white rook
     * 4  = white queen
     * 5  = white king
     *
     * 6  = black pawn
     * 7  = black knight
     * 8  = black bishop
     * 9  = black rook
     * 10 = black queen
     * 11 = black king
     */
    private int getPieceType(Piece piece) {

        int offset =
                piece.isWhite()
                        ? 0
                        : 6;

        if (piece instanceof Pawn) {
            return offset;
        }

        if (piece instanceof Knight) {
            return offset + 1;
        }

        if (piece instanceof Bishop) {
            return offset + 2;
        }

        if (piece instanceof Rook) {
            return offset + 3;
        }

        if (piece instanceof Queen) {
            return offset + 4;
        }

        if (piece instanceof King) {
            return offset + 5;
        }

        throw new IllegalArgumentException(
                "Unknown piece type: "
                        + piece.getClass().getName()
        );
    }

    /**
     * Return the feature index for a piece on a square.
     */
    public int getFeatureIndex(
            Piece piece,
            Position position
    ) {

        int pieceType =
                getPieceType(piece);

        int square =
                position.getRow() * 8
                        + position.getColumn();

        return pieceType * 64 + square;
    }
}