package chess.engine.evaluation;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;

/**
 * Explainable static evaluator.
 * Scores are centipawns from White's perspective:
 * positive = White is better, negative = Black is better.
 */
public class Evaluator {

    private static final int BISHOP_PAIR_BONUS = 30;

    /*
     * Knights strongly prefer useful central squares.
     * Edge squares are deliberately made quite unattractive.
     */
    private static final int[][] KNIGHT_TABLE = {
            {-55, -35, -25, -20, -20, -25, -35, -55},
            {-40, -20,  -5,   0,   0,  -5, -20, -40},
            {-30,  -5,   5,   8,   8,   5,  -5, -30},
            {-25,   0,   8,  12,  12,   8,   0, -25},
            {-25,   0,   8,  12,  12,   8,   0, -25},
            {-30,  -5,   5,   8,   8,   5,  -5, -30},
            {-40, -20,  -5,   0,   0,  -5, -20, -40},
            {-55, -35, -25, -20, -20, -25, -35, -55}
    };

    private static final int[][] BISHOP_TABLE = {
            {-20, -10, -10, -10, -10, -10, -10, -20},
            {-10,   5,   0,   0,   0,   0,   5, -10},
            {-10,  10,  10,  10,  10,  10,  10, -10},
            {-10,   0,  10,  10,  10,  10,   0, -10},
            {-10,   5,   5,  10,  10,   5,   5, -10},
            {-10,   0,   5,  10,  10,   5,   0, -10},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-20, -10, -10, -10, -10, -10, -10, -20}
    };

    private static final int[][] ROOK_TABLE = {
            {  0,   0,   0,   5,   5,   0,   0,   0},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {-10,   0,   0,   0,   0,   0,   0, -10},
            {  5,  10,  10,  10,  10,  10,  10,   5},
            {  0,   0,   0,   5,   5,   0,   0,   0}
    };

    /*
     * Queens are deliberately kept relatively neutral in the center.
     * Opening development penalties below are more important.
     */
    private static final int[][] QUEEN_TABLE = {
            {-30, -20, -15, -10, -10, -15, -20, -30},
            {-20, -10,  -5,  -5,  -5,  -5, -10, -20},
            {-15,  -5,   0,   0,   0,   0,  -5, -15},
            {-10,  -5,   0,   5,   5,   0,  -5, -10},
            {-10,  -5,   0,   5,   5,   0,  -5, -10},
            {-15,  -5,   0,   0,   0,   0,  -5, -15},
            {-20, -10,  -5,  -5,  -5,  -5, -10, -20},
            {-30, -20, -15, -10, -10, -15, -20, -30}
    };

    private static final int[][] KING_MIDDLEGAME_TABLE = {
            {-30, -40, -40, -50, -50, -40, -40, -30},
            {-30, -40, -40, -50, -50, -40, -40, -30},
            {-30, -40, -40, -50, -50, -40, -40, -30},
            {-30, -40, -40, -50, -50, -40, -40, -30},
            {-20, -30, -30, -40, -40, -30, -30, -20},
            {-10, -20, -20, -20, -20, -20, -20, -10},
            { 20,  20,   0,   0,   0,   0,  20,  20},
            { 20,  30,  10,   0,   0,  10,  30,  20}
    };

    private static final int[][] KING_ENDGAME_TABLE = {
            {-50, -30, -30, -30, -30, -30, -30, -50},
            {-30, -10,   0,   0,   0,   0, -10, -30},
            {-30,   0,  20,  30,  30,  20,   0, -30},
            {-30,   0,  30,  40,  40,  30,   0, -30},
            {-30,   0,  30,  40,  40,  30,   0, -30},
            {-30,   0,  20,  30,  30,  20,   0, -30},
            {-30, -10,   0,   0,   0,   0, -10, -30},
            {-50, -30, -30, -30, -30, -30, -30, -50}
    };

    public int evaluate(Board board) {
        int score = 0;

        score += materialScore(board);
        score += pawnStructureScore(board);
        score += pieceSquareScore(board);
        score += mobilityScore(board);
        score += pieceActivityScore(board);
        score += kingSafetyScore(board);
        score += endgameScore(board);

        return score;
    }

    public String explain(Board board) {
        int material = materialScore(board);
        int pawn = pawnStructureScore(board);
        int pst = pieceSquareScore(board);
        int mobility = mobilityScore(board);
        int activity = pieceActivityScore(board);
        int king = kingSafetyScore(board);
        int endgame = endgameScore(board);

        int total =
                material +
                        pawn +
                        pst +
                        mobility +
                        activity +
                        king +
                        endgame;

        return "Evaluation: " + formatScore(total) + "\n" +
                "Material:        " + formatScore(material) + "\n" +
                "Pawn structure:  " + formatScore(pawn) + "\n" +
                "Piece-square:    " + formatScore(pst) + "\n" +
                "Mobility:         " + formatScore(mobility) + "\n" +
                "Piece activity:  " + formatScore(activity) + "\n" +
                "King safety:     " + formatScore(king) + "\n" +
                "Endgame:         " + formatScore(endgame);
    }

    private String formatScore(int score) {
        return String.format("%+.2f", score / 100.0);
    }

    private int materialScore(Board board) {
        int score = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Piece piece = board.getPiece(new Position(row, col));

                if (piece == null) {
                    continue;
                }

                int value = piece instanceof King ? 0 : piece.getValue();

                score += piece.isWhite() ? value : -value;
            }
        }

        return score;
    }

    private int pawnStructureScore(Board board) {
        int score = 0;
        int[][] pawnFiles = new int[2][8];

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Piece piece = board.getPiece(new Position(row, col));

                if (piece instanceof Pawn) {
                    pawnFiles[piece.isWhite() ? 0 : 1][col]++;
                }
            }
        }

        for (boolean white : new boolean[]{true, false}) {

            int side = white ? 0 : 1;

            for (int file = 0; file < 8; file++) {

                int count = pawnFiles[side][file];

                if (count > 1) {
                    score +=
                            (white ? -1 : 1)
                                    * 20
                                    * (count - 1);
                }
            }

            int islands = 0;
            boolean previous = false;

            for (int file = 0; file < 8; file++) {

                boolean present = pawnFiles[side][file] > 0;

                if (present && !previous) {
                    islands++;
                }

                previous = present;
            }

            if (islands > 1) {
                score +=
                        (white ? -1 : 1)
                                * 8
                                * (islands - 1);
            }
        }

        for (boolean white : new boolean[]{true, false}) {

            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {

                    Piece piece =
                            board.getPiece(new Position(row, col));

                    if (!(piece instanceof Pawn)
                            || piece.isWhite() != white) {
                        continue;
                    }

                    boolean isolated = true;

                    int side = white ? 0 : 1;

                    if (col > 0
                            && pawnFiles[side][col - 1] > 0) {
                        isolated = false;
                    }

                    if (col < 7
                            && pawnFiles[side][col + 1] > 0) {
                        isolated = false;
                    }

                    if (isolated) {
                        score += white ? -15 : 15;
                    }

                    if (isPassedPawn(board, row, col, white)) {

                        int advanced =
                                white ? 6 - row : row - 1;

                        int bonus =
                                15
                                        + Math.max(0, advanced) * 12;

                        score += white ? bonus : -bonus;
                    }
                }
            }
        }

        return score;
    }

    private boolean isPassedPawn(
            Board board,
            int row,
            int col,
            boolean white) {

        int direction = white ? -1 : 1;

        for (
                int r = row + direction;
                r >= 0 && r < 8;
                r += direction
        ) {

            for (
                    int file = col - 1;
                    file <= col + 1;
                    file++
            ) {

                if (!board.isValid(r, file)) {
                    continue;
                }

                Piece piece =
                        board.getPiece(new Position(r, file));

                if (piece instanceof Pawn
                        && piece.isWhite() != white) {
                    return false;
                }
            }
        }

        return true;
    }

    private int pieceSquareScore(Board board) {

        int score = 0;
        boolean endgame = isEndgame(board);

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Piece piece =
                        board.getPiece(new Position(row, col));

                if (piece == null) {
                    continue;
                }

                int r =
                        piece.isWhite()
                                ? row
                                : 7 - row;

                int bonus = 0;

                if (piece instanceof Knight) {
                    bonus = KNIGHT_TABLE[r][col];

                } else if (piece instanceof Bishop) {
                    bonus = BISHOP_TABLE[r][col];

                } else if (piece instanceof Rook) {
                    bonus = ROOK_TABLE[r][col];

                } else if (piece instanceof Queen) {
                    bonus = QUEEN_TABLE[r][col];

                } else if (piece instanceof King) {

                    bonus =
                            (endgame
                                    ? KING_ENDGAME_TABLE
                                    : KING_MIDDLEGAME_TABLE)[r][col];
                }

                score +=
                        piece.isWhite()
                                ? bonus
                                : -bonus;
            }
        }

        return score;
    }

    /*
     * Mobility is slightly less important than before.
     *
     * During the opening, queen mobility is ignored so the engine
     * does not think that moving the queen is automatically good.
     */
    private int mobilityScore(Board board) {
        return 2 *
                (
                        pseudoMobility(board, true)
                                - pseudoMobility(board, false)
                );
    }

    private int pseudoMobility(
            Board board,
            boolean white) {

        int count = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Position position =
                        new Position(row, col);

                Piece piece =
                        board.getPiece(position);

                if (piece == null
                        || piece.isWhite() != white) {
                    continue;
                }

                /*
                 * Do not let queen mobility encourage unnecessary
                 * queen development during the opening.
                 */
                if (piece instanceof Queen
                        && isOpening(board)) {
                    continue;
                }

                count +=
                        piece.generateMoves(
                                position,
                                board
                        ).size();
            }
        }

        return count;
    }

    private int pieceActivityScore(Board board) {

        int score = 0;

        int whiteBishops = 0;
        int blackBishops = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Position position =
                        new Position(row, col);

                Piece piece =
                        board.getPiece(position);

                if (piece == null) {
                    continue;
                }

                /*
                 * Bishop pair.
                 */
                if (piece instanceof Bishop) {

                    if (piece.isWhite()) {
                        whiteBishops++;
                    } else {
                        blackBishops++;
                    }
                }

                /*
                 * Knight activity.
                 */
                if (piece instanceof Knight) {

                    /*
                     * Strong edge penalty.
                     */
                    if (col == 0 || col == 7) {
                        score +=
                                piece.isWhite()
                                        ? -25
                                        : 25;
                    }

                    /*
                     * Outposts are still valuable.
                     */
                    if (isKnightOutpost(
                            board,
                            row,
                            col,
                            piece.isWhite())) {

                        score +=
                                piece.isWhite()
                                        ? 25
                                        : -25;
                    }

                    /*
                     * During the opening, discourage knights from
                     * wandering away from their useful development.
                     *
                     * This is intentionally a small penalty.
                     * We do NOT want to completely forbid knight moves.
                     */
                    if (isOpening(board)) {

                        boolean whiteHomeKnight =
                                piece.isWhite()
                                        && row == 7
                                        && (col == 1 || col == 6);

                        boolean blackHomeKnight =
                                !piece.isWhite()
                                        && row == 0
                                        && (col == 1 || col == 6);

                        if (!whiteHomeKnight
                                && !blackHomeKnight) {

                            score +=
                                    piece.isWhite()
                                            ? -8
                                            : 8;
                        }
                    }
                }

                /*
                 * Queen activity.
                 *
                 * In the opening, moving the queen away from d1/d8
                 * receives a penalty.
                 */
                if (piece instanceof Queen) {

                    if (isOpening(board)) {

                        boolean queenOnHomeSquare =
                                (piece.isWhite()
                                        && row == 7
                                        && col == 3)
                                        ||
                                        (!piece.isWhite()
                                                && row == 0
                                                && col == 3);

                        if (!queenOnHomeSquare) {

                            score +=
                                    piece.isWhite()
                                            ? -25
                                            : 25;
                        }
                    }
                }

                /*
                 * Rook activity.
                 */
                if (piece instanceof Rook) {

                    boolean whitePawn = false;
                    boolean blackPawn = false;

                    for (int r = 0; r < 8; r++) {

                        Piece p =
                                board.getPiece(
                                        new Position(r, col)
                                );

                        if (p instanceof Pawn) {

                            if (p.isWhite()) {
                                whitePawn = true;
                            } else {
                                blackPawn = true;
                            }
                        }
                    }

                    /*
                     * Completely open file.
                     */
                    if (!whitePawn && !blackPawn) {

                        score +=
                                piece.isWhite()
                                        ? 20
                                        : -20;

                    } else if (!whitePawn
                            && piece.isWhite()) {

                        score += 10;

                    } else if (!blackPawn
                            && !piece.isWhite()) {

                        score -= 10;
                    }
                }
            }
        }

        /*
         * Bishop pair.
         */
        if (whiteBishops >= 2) {
            score += BISHOP_PAIR_BONUS;
        }

        if (blackBishops >= 2) {
            score -= BISHOP_PAIR_BONUS;
        }

        return score;
    }

    /*
     * Determines whether the position is still an opening position.
     *
     * We use remaining undeveloped material as an approximation
     * because this evaluator does not currently track move history.
     */
    private boolean isOpening(Board board) {

        int queens = 0;
        int rooks = 0;
        int bishops = 0;
        int knights = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Piece piece =
                        board.getPiece(
                                new Position(row, col)
                        );

                if (piece instanceof Queen) {
                    queens++;

                } else if (piece instanceof Rook) {
                    rooks++;

                } else if (piece instanceof Bishop) {
                    bishops++;

                } else if (piece instanceof Knight) {
                    knights++;
                }
            }
        }

        /*
         * Starting position:
         *
         * 2 queens
         * 4 rooks
         * 4 bishops
         * 4 knights
         *
         * This keeps opening principles active while most
         * of the original pieces are still on the board.
         */
        return queens == 2
                && rooks >= 3
                && bishops >= 3
                && knights >= 3;
    }

    private boolean isKnightOutpost(
            Board board,
            int row,
            int col,
            boolean white) {

        int supportRow =
                row + (white ? 1 : -1);

        if (!board.isValid(supportRow, col)) {
            return false;
        }

        boolean supported = false;

        for (int file : new int[]{col - 1, col + 1}) {

            if (!board.isValid(supportRow, file)) {
                continue;
            }

            Piece piece =
                    board.getPiece(
                            new Position(supportRow, file)
                    );

            if (piece instanceof Pawn
                    && piece.isWhite() == white) {

                supported = true;
            }
        }

        if (!supported) {
            return false;
        }

        int enemyDirection =
                white ? -1 : 1;

        for (
                int r = row + enemyDirection;
                r >= 0 && r < 8;
                r += enemyDirection
        ) {

            for (
                    int file : new int[]{col - 1, col + 1}
            ) {

                if (!board.isValid(r, file)) {
                    continue;
                }

                Piece piece =
                        board.getPiece(
                                new Position(r, file)
                        );

                if (piece instanceof Pawn
                        && piece.isWhite() != white) {

                    return false;
                }
            }
        }

        return true;
    }

    private int kingSafetyScore(Board board) {

        int score = 0;

        for (boolean white :
                new boolean[]{true, false}) {

            Position king =
                    board.findKing(white);

            if (king == null) {
                continue;
            }

            int shield = 0;

            int shieldRow =
                    king.getRow()
                            + (white ? -1 : 1);

            if (board.isValid(
                    shieldRow,
                    king.getColumn())) {

                for (
                        int file = king.getColumn() - 1;
                        file <= king.getColumn() + 1;
                        file++
                ) {

                    if (!board.isValid(
                            shieldRow,
                            file)) {
                        continue;
                    }

                    Piece piece =
                            board.getPiece(
                                    new Position(
                                            shieldRow,
                                            file
                                    )
                            );

                    if (piece instanceof Pawn
                            && piece.isWhite() == white) {

                        shield++;
                    }
                }
            }

            int localAttacks = 0;

            for (
                    int row =
                    Math.max(
                            0,
                            king.getRow() - 2
                    );

                    row <=
                            Math.min(
                                    7,
                                    king.getRow() + 2
                            );

                    row++
            ) {

                for (
                        int col =
                        Math.max(
                                0,
                                king.getColumn() - 2
                        );

                        col <=
                                Math.min(
                                        7,
                                        king.getColumn() + 2
                                );

                        col++
                ) {

                    Position square =
                            new Position(row, col);

                    Piece occupant =
                            board.getPiece(square);

                    if (occupant != null
                            && occupant.isWhite() != white
                            && board.isSquareAttacked(
                            square,
                            !white)) {

                        localAttacks++;
                    }
                }
            }

            int value =
                    shield * 8
                            - localAttacks * 5;

            if (board.isInCheck(white)) {
                value -= 35;
            }

            score +=
                    white
                            ? value
                            : -value;
        }

        return score;
    }

    private int endgameScore(Board board) {

        if (!isEndgame(board)) {
            return 0;
        }

        int score = 0;

        for (boolean white :
                new boolean[]{true, false}) {

            Position king =
                    board.findKing(white);

            if (king == null) {
                continue;
            }

            int distance =
                    Math.abs(
                            king.getRow() - 3
                    )
                            +
                            Math.abs(
                                    king.getColumn() - 3
                            );

            int bonus =
                    18 - distance * 4;

            score +=
                    white
                            ? bonus
                            : -bonus;
        }

        return score;
    }

    private boolean isEndgame(Board board) {

        int queens = 0;
        int rooks = 0;
        int nonKingMaterial = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                Piece piece =
                        board.getPiece(
                                new Position(row, col)
                        );

                if (piece == null
                        || piece instanceof King
                        || piece instanceof Pawn) {

                    continue;
                }

                nonKingMaterial += piece.getValue();

                if (piece instanceof Queen) {
                    queens++;
                }

                if (piece instanceof Rook) {
                    rooks++;
                }
            }
        }

        return queens == 0
                || (
                queens <= 1
                        && rooks <= 2
                        && nonKingMaterial <= 2000
        );
    }
}