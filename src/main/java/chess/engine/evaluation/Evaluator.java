package chess.engine.evaluation;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

public class Evaluator {

    // Piece values in centipawns
    private static final int PAWN_VALUE = 100;
    private static final int KNIGHT_VALUE = 320;
    private static final int BISHOP_VALUE = 330;
    private static final int ROOK_VALUE = 500;
    private static final int QUEEN_VALUE = 900;
    private static final int KING_VALUE = 20000;

    // Toned down Knight Piece Square Table
    private static final int[][] KNIGHT_TABLE = {
            {-60, -40, -30, -20, -20, -30, -40, -60},
            {-40, -20, -10,  -5,  -5, -10, -20, -40},
            {-30, -10,   5,   8,   8,   5, -10, -30},
            {-20,  -5,   8,  10,  10,   8,  -5, -20},
            {-20,  -5,   8,  10,  10,   8,  -5, -20},
            {-30, -10,   5,   8,   8,   5, -10, -30},
            {-40, -20, -10,  -5,  -5, -10, -20, -40},
            {-60, -40, -30, -20, -20, -30, -40, -60}
    };

    private static final int[][] BISHOP_TABLE = {
            {-20, -10, -10, -10, -10, -10, -10, -20},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-10,   0,   5,  10,  10,   5,   0, -10},
            {-10,   5,   5,  10,  10,   5,   5, -10},
            {-10,   0,  10,  10,  10,  10,   0, -10},
            {-10,  10,  10,  10,  10,  10,  10, -10},
            {-10,   5,   0,   0,   0,   0,   5, -10},
            {-20, -10, -10, -10, -10, -10, -10, -20}
    };

    private static final int[][] PAWN_TABLE = {
            { 0,  0,  0,  0,  0,  0,  0,  0},
            {50, 50, 50, 50, 50, 50, 50, 50},
            {10, 10, 20, 30, 30, 20, 10, 10},
            { 5,  5, 10, 25, 25, 10,  5,  5},
            { 0,  0,  0, 20, 20,  0,  0,  0},
            { 5, -5,-10,  0,  0,-10, -5,  5},
            { 5, 10, 10,-20,-20, 10, 10,  5},
            { 0,  0,  0,  0,  0,  0,  0,  0}
    };

    /**
     * Evaluate position relative to White.
     *
     * Positive = advantage White
     * Negative = advantage Black
     */
    public int evaluate(Board board) {

        int score = 0;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {

                Position pos = new Position(r, c);
                Piece piece = board.getPiece(pos);

                if (piece == null) {
                    continue;
                }

                int pVal = getPieceValue(piece);
                int pstVal = getPSTValue(piece, r, c);

                if (piece.isWhite()) {
                    score += pVal + pstVal;
                } else {
                    score -= pVal + pstVal;
                }

                // Custom positional logic
                if (piece instanceof Queen) {
                    score += evaluateQueen(board, piece, r, c);

                } else if (piece instanceof Knight) {
                    score += evaluateKnight(board, piece, r, c);
                }
            }
        }

        /*
         * Opening pawn structure and king safety.
         *
         * These are evaluated separately from individual pieces.
         */
        score += evaluateEarlyFPawnMoves(board, true);
        score += evaluateEarlyFPawnMoves(board, false);

        score += evaluateKingExposure(board, true);
        score += evaluateKingExposure(board, false);

        return score;
    }

    private int getPieceValue(Piece piece) {

        if (piece instanceof Pawn) return PAWN_VALUE;
        if (piece instanceof Knight) return KNIGHT_VALUE;
        if (piece instanceof Bishop) return BISHOP_VALUE;
        if (piece instanceof Rook) return ROOK_VALUE;
        if (piece instanceof Queen) return QUEEN_VALUE;
        if (piece instanceof King) return KING_VALUE;

        return 0;
    }

    private int getPSTValue(Piece piece, int row, int col) {

        int r = piece.isWhite() ? row : (7 - row);

        if (piece instanceof Knight) {
            return KNIGHT_TABLE[r][col];
        }

        if (piece instanceof Bishop) {
            return BISHOP_TABLE[r][col];
        }

        if (piece instanceof Pawn) {
            return PAWN_TABLE[r][col];
        }

        return 0;
    }

    /**
     * Penalizes premature queen deployment.
     */
    private int evaluateQueen(
            Board board,
            Piece queen,
            int row,
            int col
    ) {

        int score = 0;

        boolean isWhite = queen.isWhite();

        boolean queenOnHomeSquare =
                isWhite
                        ? (row == 7 && col == 3)
                        : (row == 0 && col == 3);

        if (!queenOnHomeSquare) {

            // General penalty for bringing the queen out early
            score += isWhite ? -50 : 50;

            // Extra penalty before developing two minor pieces
            if (countDevelopedMinorPieces(board, isWhite) < 2) {
                score += isWhite ? -75 : 75;
            }
        }

        return score;
    }

    /**
     * Evaluates knight placement and safe outposts.
     */
    private int evaluateKnight(
            Board board,
            Piece knight,
            int row,
            int col
    ) {

        int score = 0;

        boolean isWhite = knight.isWhite();

        if (isSafeOutpost(board, row, col, isWhite)) {
            score += isWhite ? 10 : -10;
        }

        return score;
    }

    /**
     * Penalizes moving the f pawn too early.
     *
     * White:
     * f2 -> f3 is bad early
     * f2 -> f4 is worse early
     *
     * Black:
     * f7 -> f6 is bad early
     * f7 -> f5 is worse early
     *
     * The penalty is strongest when the minor pieces are still
     * undeveloped.
     */
    private int evaluateEarlyFPawnMoves(
            Board board,
            boolean isWhite
    ) {

        int score = 0;

        int pawnStartRow = isWhite ? 6 : 1;
        int pawnCol = 5;

        Piece fPawn =
                board.getPiece(new Position(pawnStartRow, pawnCol));

        /*
         * If the f pawn is still on its starting square, there is
         * nothing to penalize.
         */
        if (fPawn instanceof Pawn && fPawn.isWhite() == isWhite) {
            return 0;
        }

        /*
         * If the pawn has disappeared from the f file, it may have
         * been captured or promoted. Do not automatically assume
         * that means it was moved early.
         */
        Piece fPawnOnFile = null;

        for (int r = 0; r < 8; r++) {

            Piece piece =
                    board.getPiece(new Position(r, pawnCol));

            if (piece instanceof Pawn && piece.isWhite() == isWhite) {
                fPawnOnFile = piece;
                break;
            }
        }

        if (fPawnOnFile == null) {
            return 0;
        }

        int pawnRow = findPieceRow(board, fPawnOnFile, pawnCol);

        if (pawnRow == -1) {
            return 0;
        }

        /*
         * Determine how far the pawn has moved.
         */
        int advancement;

        if (isWhite) {
            advancement = 6 - pawnRow;
        } else {
            advancement = pawnRow - 1;
        }

        /*
         * Only penalize actual advancement.
         */
        if (advancement <= 0) {
            return 0;
        }

        /*
         * Opening phase is determined by minor piece development.
         */
        int developedMinorPieces =
                countDevelopedMinorPieces(board, isWhite);

        /*
         * Once two or more minor pieces are developed, the f pawn
         * is much less suspicious.
         */
        if (developedMinorPieces >= 2) {
            return 0;
        }

        /*
         * Base penalty.
         *
         * f3/f6  -> -25
         * f4/f5  -> -45
         */
        int penalty;

        if (advancement == 1) {
            penalty = 25;
        } else {
            penalty = 45;
        }

        /*
         * Make it slightly worse when NONE of the minor pieces
         * have developed.
         */
        if (developedMinorPieces == 0) {
            penalty += 15;
        }

        return isWhite ? -penalty : penalty;
    }

    /**
     * Finds the row of a specific piece on a file.
     */
    private int findPieceRow(
            Board board,
            Piece target,
            int col
    ) {

        for (int r = 0; r < 8; r++) {

            Piece piece =
                    board.getPiece(new Position(r, col));

            if (piece == target) {
                return r;
            }
        }

        return -1;
    }

    /**
     * Penalizes exposing the king by moving the f, g, or h pawns
     * before castling.
     *
     * The engine should generally prefer keeping the king behind
     * the original pawn shield during the opening.
     */
    private int evaluateKingExposure(
            Board board,
            boolean isWhite
    ) {

        int score = 0;

        int kingRow = isWhite ? 7 : 0;

        int kingCol = findKingColumn(board, isWhite);

        if (kingCol == -1) {
            return 0;
        }

        /*
         * If the king has already moved away from its starting square,
         * apply a moderate penalty unless it appears to have castled.
         */
        boolean kingMovedFromHome =
                kingCol != 4
                        || board.getPiece(
                        new Position(kingRow, 4)
                ) == null;

        /*
         * We do not want to punish a castled king.
         *
         * A king on g1/g8 or c1/c8 is assumed to have castled or
         * otherwise reached a safer position.
         */
        boolean kingCastled =
                kingCol == 6 || kingCol == 2;

        if (kingMovedFromHome && !kingCastled) {

            int penalty = 25;

            /*
             * If the king has moved while the minor pieces are still
             * undeveloped, increase the penalty.
             */
            int developed =
                    countDevelopedMinorPieces(board, isWhite);

            if (developed < 2) {
                penalty += 25;
            }

            score += isWhite ? -penalty : penalty;
        }

        /*
         * Evaluate the pawn shield around the king.
         *
         * f, g and h pawns are the main pieces protecting a castled
         * king. Moving them too early creates holes.
         */
        int shieldRow = isWhite ? 6 : 1;

        int missingShieldPawns = 0;

        for (int col = 5; col <= 7; col++) {

            Piece pawn =
                    board.getPiece(new Position(shieldRow, col));

            if (!(pawn instanceof Pawn)
                    || pawn.isWhite() != isWhite) {

                missingShieldPawns++;
            }
        }

        /*
         * Only apply this penalty during the opening.
         */
        int developed =
                countDevelopedMinorPieces(board, isWhite);

        if (developed < 2) {

            /*
             * Each missing pawn in the king shield costs 12.
             *
             * f pawn is handled separately above, so this is mostly
             * protecting against unnecessary g and h pawn moves.
             */
            int shieldPenalty =
                    missingShieldPawns * 12;

            score += isWhite
                    ? -shieldPenalty
                    : shieldPenalty;
        }

        return score;
    }

    /**
     * Finds the king's column.
     */
    private int findKingColumn(
            Board board,
            boolean isWhite
    ) {

        int kingRow = isWhite ? 7 : 0;

        for (int col = 0; col < 8; col++) {

            Piece piece =
                    board.getPiece(
                            new Position(kingRow, col)
                    );

            if (piece instanceof King
                    && piece.isWhite() == isWhite) {

                return col;
            }
        }

        /*
         * Search the entire board as a fallback in case the king
         * has moved away from its home rank.
         */
        for (int row = 0; row < 8; row++) {

            for (int col = 0; col < 8; col++) {

                Piece piece =
                        board.getPiece(
                                new Position(row, col)
                        );

                if (piece instanceof King
                        && piece.isWhite() == isWhite) {

                    return col;
                }
            }
        }

        return -1;
    }

    /**
     * Outpost is only valid if protected by a friendly pawn AND
     * not threatened by an enemy pawn.
     */
    private boolean isSafeOutpost(
            Board board,
            int row,
            int col,
            boolean isWhite
    ) {

        // Must be in opponent territory
        if (isWhite && row > 4) {
            return false;
        }

        if (!isWhite && row < 3) {
            return false;
        }

        // Must be protected by a friendly pawn
        boolean protectedByPawn = false;

        int pawnRow =
                isWhite
                        ? row + 1
                        : row - 1;

        if (pawnRow >= 0 && pawnRow < 8) {

            if (col > 0
                    && isPawn(
                    board,
                    pawnRow,
                    col - 1,
                    isWhite
            )) {
                protectedByPawn = true;
            }

            if (col < 7
                    && isPawn(
                    board,
                    pawnRow,
                    col + 1,
                    isWhite
            )) {
                protectedByPawn = true;
            }
        }

        if (!protectedByPawn) {
            return false;
        }

        // Must NOT be attackable by enemy pawns
        int enemyPawnRow =
                isWhite
                        ? row - 1
                        : row + 1;

        if (enemyPawnRow >= 0 && enemyPawnRow < 8) {

            if (col > 0
                    && isPawn(
                    board,
                    enemyPawnRow,
                    col - 1,
                    !isWhite
            )) {
                return false;
            }

            if (col < 7
                    && isPawn(
                    board,
                    enemyPawnRow,
                    col + 1,
                    !isWhite
            )) {
                return false;
            }
        }

        return true;
    }

    private boolean isPawn(
            Board board,
            int row,
            int col,
            boolean isWhite
    ) {

        Piece p =
                board.getPiece(
                        new Position(row, col)
                );

        return p instanceof Pawn
                && p.isWhite() == isWhite;
    }

    /**
     * Counts developed knights and bishops.
     */
    private int countDevelopedMinorPieces(
            Board board,
            boolean isWhite
    ) {

        int count = 0;

        for (int r = 0; r < 8; r++) {

            for (int c = 0; c < 8; c++) {

                Piece piece =
                        board.getPiece(
                                new Position(r, c)
                        );

                if (piece == null
                        || piece.isWhite() != isWhite) {
                    continue;
                }

                if (piece instanceof Knight
                        || piece instanceof Bishop) {

                    boolean isUndeveloped =
                            isWhite
                                    ? (r == 7
                                    && (c == 1
                                    || c == 2
                                    || c == 5
                                    || c == 6))

                                    : (r == 0
                                    && (c == 1
                                    || c == 2
                                    || c == 5
                                    || c == 6));

                    if (!isUndeveloped) {
                        count++;
                    }
                }
            }
        }

        return count;
    }
}