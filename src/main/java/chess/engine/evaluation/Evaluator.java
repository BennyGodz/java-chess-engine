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

    // Knight piece square table
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

        /*
         * Determine the current game phase.
         *
         * Opening:
         * 0
         *
         * Middlegame:
         * 1
         *
         * Endgame:
         * 2
         */
        int phase = getGamePhase(board);

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

                /*
                 * Piece specific evaluation.
                 */
                if (piece instanceof Queen) {

                    score += evaluateQueen(
                            board,
                            piece,
                            r,
                            c,
                            phase
                    );

                } else if (piece instanceof Knight) {

                    score += evaluateKnight(
                            board,
                            piece,
                            r,
                            c,
                            phase
                    );
                }
            }
        }

        /*
         * Opening development.
         */
        score += evaluateDevelopment(
                board,
                true,
                phase
        );

        score += evaluateDevelopment(
                board,
                false,
                phase
        );

        /*
         * Pawn structure.
         */
        score += evaluateEarlyFPawnMoves(
                board,
                true,
                phase
        );

        score += evaluateEarlyFPawnMoves(
                board,
                false,
                phase
        );

        /*
         * King safety.
         */
        score += evaluateKingExposure(
                board,
                true,
                phase
        );

        score += evaluateKingExposure(board, false, phase);
        score += evaluateCentralPawnDevelopment(board, true, phase);
        score += evaluateCentralPawnDevelopment(board, false, phase);

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

    private int getPSTValue(
            Piece piece,
            int row,
            int col
    ) {

        int r =
                piece.isWhite()
                        ? row
                        : 7 - row;

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
     * Determines the game phase.
     *
     * Opening:
     * Most pieces still on their original squares.
     *
     * Middlegame:
     * Several pieces developed and/or queens still present.
     *
     * Endgame:
     * Queens gone and relatively little material remains.
     */
    private int getGamePhase(Board board) {

        int whiteMinor =
                countDevelopedMinorPieces(board, true);

        int blackMinor =
                countDevelopedMinorPieces(board, false);

        int queens = 0;
        int rooks = 0;
        int bishops = 0;
        int knights = 0;

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {

                Piece piece =
                        board.getPiece(
                                new Position(r, c)
                        );

                if (piece == null) {
                    continue;
                }

                if (piece instanceof Queen) {
                    queens++;
                }

                if (piece instanceof Rook) {
                    rooks++;
                }

                if (piece instanceof Bishop) {
                    bishops++;
                }

                if (piece instanceof Knight) {
                    knights++;
                }
            }
        }

        /*
         * Endgame.
         */
        if (queens == 0
                && rooks <= 2
                && bishops + knights <= 4) {

            return 2;
        }

        /*
         * Opening.
         *
         * Both sides still have most minor pieces undeveloped.
         */
        if (whiteMinor <= 2
                && blackMinor <= 2
                && queens >= 2) {

            return 0;
        }

        /*
         * Otherwise middlegame.
         */
        return 1;
    }

    /**
     * Strong opening bonus for developing all minor pieces.
     *
     * Knights and bishops should generally come out before the queen.
     */
    private int evaluateDevelopment(
            Board board,
            boolean isWhite,
            int phase
    ) {

        /*
         * Development is mainly an opening concept.
         */
        if (phase != 0) {
            return 0;
        }

        int score = 0;

        int developed =
                countDevelopedMinorPieces(
                        board,
                        isWhite
                );

        /*
         * Reward each developed minor piece.
         *
         * This encourages:
         *
         * Nf3
         * Nc3
         * Bc4
         * Be2
         *
         * instead of repeatedly moving one knight.
         */
        score += developed * 18;

        /*
         * Stronger bonus for developing the second minor piece.
         */
        if (developed >= 2) {
            score += 15;
        }

        /*
         * Strong bonus for developing all four minor pieces.
         */
        if (developed >= 4) {
            score += 25;
        }

        /*
         * Reward having both knights developed.
         */
        if (isKnightDeveloped(board, isWhite, false)
                && isKnightDeveloped(board, isWhite, true)) {

            score += 15;
        }

        /*
         * Reward having both bishops developed.
         */
        if (isBishopDeveloped(board, isWhite, false)
                && isBishopDeveloped(board, isWhite, true)) {

            score += 15;
        }

        /*
         * Reward castling or having the king safely developed.
         */
        if (isKingCastled(board, isWhite)) {
            score += 35;
        }

        return isWhite ? score : -score;
    }

    /**
     * Evaluates knight placement.
     *
     * This specifically discourages premature knight jumps such as
     * Nc5 in the Caro Kann when the knight is unsupported.
     */
    private int evaluateKnight(
            Board board,
            Piece knight,
            int row,
            int col,
            int phase
    ) {

        int score = 0;

        boolean isWhite = knight.isWhite();

        /*
         * Safe outposts are good, but only more strongly in the
         * middlegame.
         */
        if (isSafeOutpost(
                board,
                row,
                col,
                isWhite
        )) {

            if (phase == 0) {
                score += isWhite ? 5 : -5;
            } else {
                score += isWhite ? 15 : -15;
            }
        }

        /*
         * Opening knight logic.
         */
        if (phase == 0) {

            /*
             * Reward normal development squares.
             *
             * White:
             * b1 -> c3/a3
             * g1 -> f3/h3
             *
             * Black:
             * b8 -> c6/a6
             * g8 -> f6/h6
             */
            if (isNaturalKnightSquare(
                    row,
                    col,
                    isWhite
            )) {

                score += isWhite ? 15 : -15;
            }

            /*
             * Penalize advanced knights.
             *
             * In particular this catches moves such as Nc5 before
             * the position is ready for them.
             */
            if (isAdvancedKnight(
                    row,
                    col,
                    isWhite
            )) {

                score += isWhite ? -15 : 15;
            }

            /*
             * Unsupported advanced knight.
             */
            if (isAdvancedKnight(
                    row,
                    col,
                    isWhite
            ) && !isKnightSupported(
                    board,
                    row,
                    col,
                    isWhite
            )) {

                score += isWhite ? -30 : 30;
            }

            /*
             * Even more suspicious if the other minor pieces have
             * barely developed.
             */
            if (isAdvancedKnight(
                    row,
                    col,
                    isWhite
            ) && countDevelopedMinorPieces(
                    board,
                    isWhite
            ) <= 1) {

                score += isWhite ? -20 : 20;
            }
        }

        return score;
    }

    /**
     * Natural knight development squares.
     */
    private boolean isNaturalKnightSquare(
            int row,
            int col,
            boolean isWhite
    ) {

        if (isWhite) {

            return (row == 5 && col == 2)
                    || (row == 5 && col == 5)
                    || (row == 7 && col == 2)
                    || (row == 7 && col == 5);
        }

        return (row == 2 && col == 2)
                || (row == 2 && col == 5)
                || (row == 0 && col == 2)
                || (row == 0 && col == 5);
    }

    /**
     * Determines whether a knight is deep in enemy territory.
     *
     * White:
     * rows 2 and 3 are especially advanced.
     *
     * Black:
     * rows 4 and 5 are especially advanced.
     */
    private boolean isAdvancedKnight(
            int row,
            int col,
            boolean isWhite
    ) {

        if (isWhite) {
            return row <= 3;
        }

        return row >= 4;
    }

    /**
     * Checks whether a knight has friendly pawn support.
     */
    private boolean isKnightSupported(
            Board board,
            int row,
            int col,
            boolean isWhite
    ) {

        int pawnRow =
                isWhite
                        ? row + 1
                        : row - 1;

        if (pawnRow < 0 || pawnRow >= 8) {
            return false;
        }

        if (col > 0
                && isPawn(
                board,
                pawnRow,
                col - 1,
                isWhite
        )) {

            return true;
        }

        if (col < 7
                && isPawn(
                board,
                pawnRow,
                col + 1,
                isWhite
        )) {

            return true;
        }

        return false;
    }
    /**
     * Strongly rewards central pawn development during the opening.
     *
     * White prefers:
     *     e4 and d4
     *
     * Black prefers:
     *     e5 and d5
     *
     * This is intentionally strongest in the opening and fades
     * during the middlegame.
     */
    private int evaluateCentralPawnDevelopment(
            Board board,
            boolean isWhite,
            int phase
    ) {
        if (phase != 0) {
            return 0;
        }

        int score = 0;

        int pawnRow = isWhite ? 6 : 1;

        /*
         * White e pawn = e2
         * White d pawn = d2
         *
         * Black e pawn = e7
         * Black d pawn = d7
         */
        Piece dPawn = board.getPiece(
                new Position(pawnRow, 3)
        );

        Piece ePawn = board.getPiece(
                new Position(pawnRow, 4)
        );

        boolean dPawnHome =
                dPawn instanceof Pawn
                        && dPawn.isWhite() == isWhite;

        boolean ePawnHome =
                ePawn instanceof Pawn
                        && ePawn.isWhite() == isWhite;

        /*
         * Reward having moved the d pawn.
         */
        if (!dPawnHome) {
            score += 30;
        }

        /*
         * Reward having moved the e pawn.
         */
        if (!ePawnHome) {
            score += 35;
        }

        /*
         * Strong extra bonus for having both central pawns advanced.
         */
        if (!dPawnHome && !ePawnHome) {
            score += 25;
        }

        /*
         * If neither central pawn has moved, strongly encourage
         * central pawn development.
         */
        if (dPawnHome && ePawnHome) {
            score -= 20;
        }

        return isWhite ? score : -score;
    }
    /**
     * Penalizes premature queen deployment.
     *
     * In the opening, the queen should generally stay on its
     * starting square until the minor pieces are developed.
     */
    private int evaluateQueen(
            Board board,
            Piece queen,
            int row,
            int col,
            int phase
    ) {
        int score = 0;

        boolean isWhite = queen.isWhite();

        boolean queenOnHomeSquare =
                isWhite
                        ? (row == 7 && col == 3)
                        : (row == 0 && col == 3);

        if (queenOnHomeSquare) {
            return 0;
        }

        /*
         * Only strongly discourage queen movement in the opening.
         */
        if (phase == 0) {

            // Basic penalty for moving the queen early
            score += isWhite ? -40 : 40;

            /*
             * Extra penalty if fewer than two minor pieces
             * have been developed.
             */
            int developed =
                    countDevelopedMinorPieces(board, isWhite);

            if (developed < 2) {
                score += isWhite ? -60 : 60;
            }

            /*
             * Even stronger if no minor pieces have been developed.
             */
            if (developed == 0) {
                score += isWhite ? -30 : 30;
            }
        }

        /*
         * In the middlegame, queen activity is much more acceptable.
         */
        else if (phase == 1) {

            score += isWhite ? -5 : 5;
        }

        /*
         * No queen development penalty in the endgame.
         */
        return score;
    }

    /**
     * Penalizes premature f pawn moves.
     */
    private int evaluateEarlyFPawnMoves(
            Board board,
            boolean isWhite,
            int phase
    ) {

        /*
         * Only apply this strongly during the opening.
         */
        if (phase != 0) {
            return 0;
        }

        int pawnStartRow =
                isWhite ? 6 : 1;

        int pawnCol = 5;

        Piece startingPawn =
                board.getPiece(
                        new Position(
                                pawnStartRow,
                                pawnCol
                        )
                );

        /*
         * Still on starting square.
         */
        if (startingPawn instanceof Pawn
                && startingPawn.isWhite() == isWhite) {

            return 0;
        }

        Piece fPawn = null;
        int pawnRow = -1;

        for (int r = 0; r < 8; r++) {

            Piece piece =
                    board.getPiece(
                            new Position(r, pawnCol)
                    );

            if (piece instanceof Pawn
                    && piece.isWhite() == isWhite) {

                fPawn = piece;
                pawnRow = r;
                break;
            }
        }

        /*
         * Pawn was captured.
         */
        if (fPawn == null) {
            return 0;
        }

        int advancement =
                isWhite
                        ? 6 - pawnRow
                        : pawnRow - 1;

        if (advancement <= 0) {
            return 0;
        }

        int developed =
                countDevelopedMinorPieces(
                        board,
                        isWhite
                );

        int penalty;

        if (advancement == 1) {
            penalty = 25;
        } else {
            penalty = 45;
        }

        if (developed == 0) {
            penalty += 15;
        }

        return isWhite
                ? -penalty
                : penalty;
    }

    /**
     * King safety and pawn shield.
     */
    private int evaluateKingExposure(
            Board board,
            boolean isWhite,
            int phase
    ) {

        int score = 0;

        int kingCol =
                findKingColumn(
                        board,
                        isWhite
                );

        if (kingCol == -1) {
            return 0;
        }

        /*
         * Opening king safety is especially important.
         */
        if (phase == 0) {

            if (isKingCastled(
                    board,
                    isWhite
            )) {

                score += 35;

            } else {

                /*
                 * Encourage getting ready to castle without
                 * unnecessarily moving the king.
                 */
                int developed =
                        countDevelopedMinorPieces(
                                board,
                                isWhite
                        );

                if (developed >= 2) {
                    score += 10;
                }
            }
        }

        /*
         * Pawn shield.
         *
         * f, g and h pawns protect the king after castling.
         */
        if (phase == 0) {

            int shieldRow =
                    isWhite ? 6 : 1;

            int missingShieldPawns = 0;

            for (int col = 5; col <= 7; col++) {

                Piece pawn =
                        board.getPiece(
                                new Position(
                                        shieldRow,
                                        col
                                )
                        );

                if (!(pawn instanceof Pawn)
                        || pawn.isWhite() != isWhite) {

                    missingShieldPawns++;
                }
            }

            int penalty =
                    missingShieldPawns * 12;

            score += isWhite
                    ? -penalty
                    : penalty;
        }

        return isWhite ? score : -score;
    }

    /**
     * Checks whether the king is on a normal castled square.
     */
    private boolean isKingCastled(
            Board board,
            boolean isWhite
    ) {

        int row =
                isWhite ? 7 : 0;

        /*
         * g1/g8
         */
        Piece kingG =
                board.getPiece(
                        new Position(row, 6)
                );

        if (kingG instanceof King
                && kingG.isWhite() == isWhite) {

            return true;
        }

        /*
         * c1/c8
         */
        Piece kingC =
                board.getPiece(
                        new Position(row, 2)
                );

        return kingC instanceof King
                && kingC.isWhite() == isWhite;
    }

    /**
     * Finds the king column.
     */
    private int findKingColumn(
            Board board,
            boolean isWhite
    ) {

        for (int r = 0; r < 8; r++) {

            for (int c = 0; c < 8; c++) {

                Piece piece =
                        board.getPiece(
                                new Position(r, c)
                        );

                if (piece instanceof King
                        && piece.isWhite() == isWhite) {

                    return c;
                }
            }
        }

        return -1;
    }

    /**
     * Determines whether a knight is developed.
     *
     *
     */
    private boolean isKnightDeveloped(
            Board board,
            boolean isWhite,
            boolean queenSide
    ) {

        int homeRow =
                isWhite ? 7 : 0;

        int homeCol =
                queenSide ? 1 : 6;

        Piece homePiece =
                board.getPiece(
                        new Position(
                                homeRow,
                                homeCol
                        )
                );

        /*
         * If the original knight is gone, it was developed.
         *
         * This is not perfect because it could have been captured,
         * but it prevents the evaluator from assuming it remains
         * undeveloped.
         */
        if (!(homePiece instanceof Knight)
                || homePiece.isWhite() != isWhite) {

            return true;
        }

        return false;
    }

    /**
     * Determines whether a bishop has left its original square.
     */
    private boolean isBishopDeveloped(
            Board board,
            boolean isWhite,
            boolean queenSide
    ) {

        int homeRow =
                isWhite ? 7 : 0;

        int homeCol =
                queenSide ? 2 : 5;

        Piece homePiece =
                board.getPiece(
                        new Position(
                                homeRow,
                                homeCol
                        )
                );

        if (!(homePiece instanceof Bishop)
                || homePiece.isWhite() != isWhite) {

            return true;
        }

        return false;
    }

    /**
     * Outpost is valid if protected by a friendly pawn and not
     * attackable by an enemy pawn.
     */
    private boolean isSafeOutpost(
            Board board,
            int row,
            int col,
            boolean isWhite
    ) {

        if (isWhite && row > 4) {
            return false;
        }

        if (!isWhite && row < 3) {
            return false;
        }

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

        int enemyPawnRow =
                isWhite
                        ? row - 1
                        : row + 1;

        if (enemyPawnRow >= 0
                && enemyPawnRow < 8) {

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