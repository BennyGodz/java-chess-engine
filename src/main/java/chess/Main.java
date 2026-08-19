package chess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.ChessEngine;
import chess.engine.opening.OpeningManager;
import chess.engine.search.SearchEngine;
import chess.pieces.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Console chess UI with SAN input and SAN output.
 *
 * The opening book is optional.
 *
 * The engine checks opening moves before playing them.
 * If an opponent deviates and makes the book move bad,
 * the engine abandons the opening and searches normally.
 */
public class Main {

    /*
     * Normal engine settings.
     *
     * These are appropriate for blitz.
     */
    private static final int ENGINE_DEPTH = 9;
    private static final long ENGINE_TIME_MS = 1000;

    /*
     * Short search used to verify an opening move.
     *
     * This should be much faster than the main search.
     */
    private static final int OPENING_CHECK_DEPTH = 5;
    private static final long OPENING_CHECK_TIME_MS = 150;

    /*
     * How much worse the opening move can be compared
     * with the engine's best move.
     *
     * 50 = half a pawn.
     *
     * If the book move is more than this much worse,
     * abandon the opening.
     */
    private static final int MAX_OPENING_LOSS_CP = 50;

    private static Position parseSquare(String square) {

        if (square == null ||
                !square.matches("[a-hA-H][1-8]")) {

            throw new IllegalArgumentException(
                    "Invalid square: " + square
            );
        }

        char file =
                Character.toLowerCase(
                        square.charAt(0)
                );

        char rank =
                square.charAt(1);

        return new Position(
                8 - (rank - '0'),
                file - 'a'
        );
    }

    public static void main(String[] args) {

        Scanner scanner =
                new Scanner(System.in);

        System.out.println(
                "================================"
        );

        System.out.println(
                "        JAVA CHESS ENGINE"
        );

        System.out.println(
                "================================"
        );

        System.out.println(
                "SAN input is supported: "
                        + "e4, Nf3, Nbd2, R1e2, "
                        + "Bxe6+, O-O, O-O-O, e8=Q"
        );

        System.out.println(
                "You can also enter coordinate moves "
                        + "such as e2e4 or e2 e4."
        );

        System.out.println();

        System.out.println(
                "Choose your color:"
        );

        System.out.println(
                "1. White"
        );

        System.out.println(
                "2. Black"
        );

        System.out.print("> ");

        String choiceLine =
                scanner.nextLine().trim();

        boolean playerIsWhite;

        if (choiceLine.equals("1")) {

            playerIsWhite = true;

        } else if (choiceLine.equals("2")) {

            playerIsWhite = false;

        } else {

            System.out.println(
                    "Invalid choice."
            );

            scanner.close();
            return;
        }

        System.out.println(
                "\nYou are playing "
                        + (playerIsWhite
                        ? "White"
                        : "Black")
                        + "."
        );

        boolean engineIsWhite =
                !playerIsWhite;

        Board board =
                new Board();

        ChessEngine engine =
                new ChessEngine();

        OpeningManager openingManager =
                new OpeningManager();

        while (true) {

            board.printBoard(playerIsWhite);

            System.out.println(
                    "Move "
                            + board.getFullmoveNumber()
                            + ": "
                            + board.getGameStatus()
            );

            /*
             * Automatic game ending.
             */
            boolean side =
                    board.isWhiteToMove();

            if (board.isCheckmate(side)
                    || board.isStalemate(side)
                    || board.isSeventyFiveMoveRule()
                    || board.isFivefoldRepetition()
                    || board.isInsufficientMaterial()) {

                break;
            }

            /*
             * Engine's turn.
             */
            if (side == engineIsWhite) {

                makeEngineMove(
                        board,
                        engine,
                        openingManager
                );

                continue;
            }

            /*
             * Human's turn.
             */
            System.out.println(
                    "Enter SAN "
                            + "(e.g. e4, Nf3, Nbd2, "
                            + "R1e2, Bxe6+, O-O, e8=Q)"
            );

            System.out.println(
                    "Commands: moves, fen, help, "
                            + "claim50, claim3, eval, quit"
            );

            System.out.print("> ");

            String input =
                    scanner.nextLine().trim();

            if (input.equalsIgnoreCase("quit")) {
                break;
            }

            try {

                if (input.equalsIgnoreCase("help")) {

                    printHelp();
                    continue;
                }

                if (input.equalsIgnoreCase("fen")) {

                    System.out.println(
                            board.toFEN()
                    );

                    continue;
                }

                if (input.equalsIgnoreCase("claim50")) {

                    if (board.isFiftyMoveRule()) {

                        System.out.println(
                                "Draw claimed by the "
                                        + "50-move rule."
                        );

                        break;
                    }

                    System.out.println(
                            "The 50-move rule is "
                                    + "not currently claimable."
                    );

                    continue;
                }

                if (input.equalsIgnoreCase("claim3")) {

                    if (board.isThreefoldRepetition()) {

                        System.out.println(
                                "Draw claimed by "
                                        + "threefold repetition."
                        );

                        break;
                    }

                    System.out.println(
                            "Threefold repetition is "
                                    + "not currently claimable."
                    );

                    continue;
                }

                if (input.equalsIgnoreCase("moves")) {

                    printLegalMoves(board);
                    continue;
                }

                if (input.equalsIgnoreCase("eval")) {

                    System.out.println(
                            "Engine is searching..."
                    );

                    SearchEngine.SearchResult result =
                            engine.findBestMove(
                                    board,
                                    ENGINE_DEPTH,
                                    ENGINE_TIME_MS
                            );

                    System.out.printf(
                            "Search Evaluation: %+.2f "
                                    + "[depth %d, nodes %,d]%n",
                            result.score() / 100.0,
                            result.depth(),
                            result.nodes()
                    );

                    continue;
                }

                Move move =
                        parseMove(
                                board,
                                input
                        );

                if (move == null) {

                    System.out.println(
                            "Invalid or illegal move."
                    );

                    continue;
                }

                String san =
                        board.formatMove(move);

                board.playMove(move);

                /*
                 * If the human made a move that does not
                 * match the selected opening, disable it.
                 *
                 * We determine this by asking the opening
                 * manager what it expected.
                 */
                if (openingManager.isOpeningActive()) {

                    /*
                     * The opening manager can only continue
                     * if the position after the human move
                     * has a valid next book move.
                     *
                     * We don't disable it here because it
                     * may still be the correct position.
                     */
                }

                System.out.println(
                        "Played " + san
                );

            } catch (IllegalArgumentException e) {

                System.out.println(
                        "Invalid move: "
                                + e.getMessage()
                );
            }
        }

        System.out.println(
                "\nGame over."
        );

        System.out.println(
                board.getGameStatus()
        );

        scanner.close();
    }

    /**
     * Make an engine move.
     *
     * The engine first checks whether there is a book move.
     *
     * If there is:
     *
     * 1. Search the position normally.
     * 2. Search the position after the book move.
     * 3. Compare the two.
     * 4. Play the book move only if it is safe.
     *
     * This prevents weird opponent moves from forcing
     * the engine into bad opening moves.
     */
    private static void makeEngineMove(
            Board board,
            ChessEngine engine,
            OpeningManager openingManager
    ) {

        System.out.println();
        System.out.println(
                "================================"
        );
        System.out.println(
                "ENGINE THINKING..."
        );
        System.out.println(
                "================================"
        );

        System.out.println(
                "Position: "
                        + board.toFEN()
        );

        /*
         * Make absolutely sure it is the engine's turn.
         */
        Move bookMove = null;

        if (openingManager.isOpeningActive()) {

            bookMove =
                    openingManager.getOpeningMove(
                            board
                    );
        }

        /*
         * No opening move.
         *
         * Just use the normal engine.
         */
        if (bookMove == null) {

            SearchEngine.SearchResult result =
                    engine.findBestMove(
                            board,
                            ENGINE_DEPTH,
                            ENGINE_TIME_MS
                    );

            Move bestMove =
                    result.bestMove();

            if (bestMove == null) {

                System.out.println(
                        "Engine found no legal move."
                );

                return;
            }

            playEngineMove(
                    board,
                    bestMove,
                    result
            );

            return;
        }

        /*
         * =====================================================
         * OPENING SAFETY CHECK
         * =====================================================
         */

        System.out.println(
                "Book move candidate: "
                        + board.formatMove(bookMove)
        );

        /*
         * Search the current position.
         *
         * This gives us the engine's normal best move.
         */
        SearchEngine.SearchResult normalResult =
                engine.findBestMove(
                        board,
                        OPENING_CHECK_DEPTH,
                        OPENING_CHECK_TIME_MS
                );

        Move normalBestMove =
                normalResult.bestMove();

        if (normalBestMove == null) {

            /*
             * Something unusual happened.
             * Fall back to the book move.
             */
            board.playMove(bookMove);
            openingManager.advance();

            System.out.println(
                    "Playing opening move."
            );

            System.out.println(
                    "Opening: "
                            + openingManager.getOpeningName()
            );

            System.out.println(
                    "Move: "
                            + board.formatMove(bookMove)
            );

            return;
        }

        /*
         * Search the position AFTER the book move.
         *
         * We create a fresh Board from FEN so that the
         * real board is not modified.
         */
        Board bookBoard =
                new Board();

        bookBoard.loadFEN(
                board.toFEN()
        );

        bookBoard.playMove(
                findEquivalentMove(
                        bookBoard,
                        bookMove
                )
        );

        /*
         * The search result here is from the perspective
         * of the opponent.
         *
         * Therefore negate it to get the score from the
         * perspective of the side that played the book move.
         */
        SearchEngine.SearchResult bookResult =
                engine.findBestMove(
                        bookBoard,
                        OPENING_CHECK_DEPTH,
                        OPENING_CHECK_TIME_MS
                );

        int normalScore =
                normalResult.score();

        int bookScore =
                -bookResult.score();

        int difference =
                normalScore - bookScore;

        System.out.println(
                "Opening safety check:"
        );

        System.out.printf(
                "Normal engine: %+.2f%n",
                normalScore / 100.0
        );

        System.out.printf(
                "Book move:     %+.2f%n",
                bookScore / 100.0
        );

        System.out.printf(
                "Difference:    %.2f%n",
                difference / 100.0
        );

        /*
         * If the book move is not significantly worse,
         * continue the opening.
         */
        if (difference <= MAX_OPENING_LOSS_CP) {

            String san =
                    board.formatMove(bookMove);

            board.playMove(bookMove);

            openingManager.advance();

            System.out.println();
            System.out.println(
                    "PLAYING BOOK MOVE"
            );

            System.out.println(
                    "Move: "
                            + san
            );

            System.out.println(
                    "Opening: "
                            + openingManager.getOpeningName()
            );

            System.out.println(
                    "Opening move accepted."
            );

            return;
        }

        /*
         * The book move is too bad.
         *
         * Abandon the opening permanently.
         */
        System.out.println();
        System.out.println(
                "BOOK MOVE REJECTED."
        );

        System.out.println(
                "Opponent's move made the "
                        + "opening move unsafe."
        );

        openingManager.disable();

        /*
         * Use the normal engine's best move.
         */
        playEngineMove(
                board,
                normalBestMove,
                normalResult
        );
    }

    /**
     * Find the equivalent move on another Board.
     */
    private static Move findEquivalentMove(
            Board board,
            Move original
    ) {

        List<Move> legalMoves =
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        for (Move move : legalMoves) {

            if (!move.getStart()
                    .equals(original.getStart())) {
                continue;
            }

            if (!move.getEnd()
                    .equals(original.getEnd())) {
                continue;
            }

            if (move.isPromotion()
                    != original.isPromotion()) {
                continue;
            }

            if (move.isPromotion()) {

                if (move.getPromotionPiece()
                        .getNotationSymbol()
                        != original
                        .getPromotionPiece()
                        .getNotationSymbol()) {

                    continue;
                }
            }

            return move;
        }

        throw new IllegalStateException(
                "Could not recreate opening move."
        );
    }

    /**
     * Play a normal engine move.
     */
    private static void playEngineMove(
            Board board,
            Move move,
            SearchEngine.SearchResult result
    ) {

        String san =
                board.formatMove(move);

        board.playMove(move);

        System.out.printf(
                "Engine plays %s  "
                        + "[depth %d, nodes %,d, score %+.2f]%n%n",
                san,
                result.depth(),
                result.nodes(),
                result.score() / 100.0
        );
    }

    /**
     * Parse SAN, long algebraic, or coordinate notation.
     */
    private static Move parseMove(
            Board board,
            String rawInput
    ) {

        String input =
                normalizeSan(rawInput);

        if (input.isEmpty()) {
            return null;
        }

        List<Move> legalMoves =
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        /*
         * Castling.
         */
        if (input.equals("O-O")) {

            return uniqueEndMove(
                    legalMoves,
                    6
            );
        }

        if (input.equals("O-O-O")) {

            return uniqueEndMove(
                    legalMoves,
                    2
            );
        }

        /*
         * Coordinate notation.
         */
        Move coordinateMove =
                parseCoordinateMove(
                        board,
                        input,
                        legalMoves
                );

        if (coordinateMove != null) {
            return coordinateMove;
        }

        /*
         * SAN.
         */
        boolean suppliedMate =
                input.endsWith("#");

        boolean suppliedCheck =
                input.endsWith("+");

        String core =
                input.replaceFirst(
                        "[+#]+$",
                        ""
                );

        char promotion =
                '\0';

        int promotionIndex =
                core.indexOf('=');

        if (promotionIndex >= 0) {

            if (promotionIndex
                    != core.length() - 2) {

                return null;
            }

            promotion =
                    Character.toUpperCase(
                            core.charAt(
                                    core.length() - 1
                            )
                    );

            core =
                    core.substring(
                            0,
                            promotionIndex
                    );

        } else if (core.length() >= 3) {

            char last =
                    Character.toUpperCase(
                            core.charAt(
                                    core.length() - 1
                            )
                    );

            if ((last == 'Q'
                    || last == 'R'
                    || last == 'B'
                    || last == 'N')
                    && core.charAt(
                    core.length() - 2
            ) >= '1'
                    && core.charAt(
                    core.length() - 2
            ) <= '8') {

                promotion = last;

                core =
                        core.substring(
                                0,
                                core.length() - 1
                        );
            }
        }

        if (!core.matches(
                "[KQRBN]?[a-h1-8]{0,2}x?[a-h][1-8]"
        )) {

            return null;
        }

        String destinationText =
                core.substring(
                        core.length() - 2
                );

        Position destination =
                parseSquare(destinationText);

        String prefix =
                core.substring(
                        0,
                        core.length() - 2
                );

        boolean captureSpecified =
                prefix.contains("x");

        prefix =
                prefix.replace("x", "");

        char pieceLetter = 'P';

        if (!prefix.isEmpty()
                && "KQRBN".indexOf(
                prefix.charAt(0)
        ) >= 0) {

            pieceLetter =
                    prefix.charAt(0);

            prefix =
                    prefix.substring(1);
        }

        if (prefix.length() > 2) {
            return null;
        }

        String disambiguation =
                prefix;

        List<Move> matches =
                new ArrayList<>();

        for (Move move : legalMoves) {

            if (!move.getEnd()
                    .equals(destination)) {
                continue;
            }

            Piece piece =
                    board.getPiece(
                            move.getStart()
                    );

            if (piece == null
                    || piece.getNotationSymbol()
                    != pieceLetter) {
                continue;
            }

            boolean isCapture =
                    move.isEnPassant()
                            || board.getPiece(
                            move.getEnd()
                    ) != null;

            if (captureSpecified
                    && !isCapture) {
                continue;
            }

            if (move.isPromotion()) {

                if (promotion == '\0') {
                    continue;
                }

                if (move.getPromotionPiece()
                        .getNotationSymbol()
                        != promotion) {
                    continue;
                }

            } else if (promotion != '\0') {

                continue;
            }

            if (!matchesDisambiguation(
                    move,
                    disambiguation
            )) {
                continue;
            }

            matches.add(move);
        }

        if (matches.size() != 1) {
            return null;
        }

        Move selected =
                matches.get(0);

        String actual =
                board.formatMove(selected);

        if (suppliedMate
                && !actual.endsWith("#")) {
            return null;
        }

        if (suppliedCheck
                && !(actual.endsWith("+")
                || actual.endsWith("#"))) {
            return null;
        }

        return selected;
    }

    private static boolean matchesDisambiguation(
            Move move,
            String disambiguation
    ) {

        if (disambiguation.isEmpty()) {
            return true;
        }

        char file =
                (char) (
                        'a'
                                + move.getStart()
                                .getColumn()
                );

        char rank =
                (char) (
                        '8'
                                - move.getStart()
                                .getRow()
                );

        if (disambiguation.length() == 1) {

            return disambiguation.charAt(0) == file
                    || disambiguation.charAt(0) == rank;
        }

        return disambiguation.charAt(0) == file
                && disambiguation.charAt(1) == rank;
    }

    /**
     * Coordinate notation.
     */
    private static Move parseCoordinateMove(
            Board board,
            String input,
            List<Move> legalMoves
    ) {

        String compact =
                input
                        .replace("-", "")
                        .replace(" ", "");

        if (!compact.matches(
                "[a-hA-H][1-8]x?[a-hA-H][1-8](=?[QRBNqrbn])?"
        )) {

            return null;
        }

        boolean captureSpecified =
                compact.charAt(2) == 'x'
                        || compact.charAt(2) == 'X';

        int endIndex =
                captureSpecified ? 5 : 4;

        String startText =
                compact.substring(0, 2);

        String endText =
                compact.substring(
                        captureSpecified ? 3 : 2,
                        captureSpecified ? 5 : 4
                );

        char promotion =
                '\0';

        if (compact.length() > endIndex) {

            String suffix =
                    compact.substring(
                            endIndex
                    ).replace("=", "");

            if (suffix.length() != 1) {
                return null;
            }

            promotion =
                    Character.toUpperCase(
                            suffix.charAt(0)
                    );
        }

        Position start =
                parseSquare(startText);

        Position end =
                parseSquare(endText);

        for (Move move : legalMoves) {

            if (!move.getStart()
                    .equals(start)
                    || !move.getEnd()
                    .equals(end)) {
                continue;
            }

            boolean isCapture =
                    move.isEnPassant()
                            || board.getPiece(
                            move.getEnd()
                    ) != null;

            if (captureSpecified
                    && !isCapture) {
                continue;
            }

            if (move.isPromotion()) {

                if (promotion == 'Q'
                        && move.getPromotionPiece()
                        instanceof Queen) {
                    return move;
                }

                if (promotion == 'R'
                        && move.getPromotionPiece()
                        instanceof Rook) {
                    return move;
                }

                if (promotion == 'B'
                        && move.getPromotionPiece()
                        instanceof Bishop) {
                    return move;
                }

                if (promotion == 'N'
                        && move.getPromotionPiece()
                        instanceof Knight) {
                    return move;
                }

            } else if (promotion == '\0') {

                return move;
            }
        }

        return null;
    }

    private static Move uniqueEndMove(
            List<Move> legalMoves,
            int endColumn
    ) {

        for (Move move : legalMoves) {

            if (move.isCastling()
                    && move.getEnd()
                    .getColumn() == endColumn) {

                return move;
            }
        }

        return null;
    }

    private static String normalizeSan(
            String input
    ) {

        String s =
                input.trim();

        s =
                s.replace('0', 'O');

        s =
                s.replace('−', '-');

        s =
                s.replace('–', '-');

        s =
                s.replace('—', '-');

        s =
                s.replaceAll(
                        "\\s+",
                        ""
                );

        return s;
    }

    private static void printLegalMoves(
            Board board
    ) {

        List<Move> moves =
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        List<String> notation =
                new ArrayList<>();

        for (Move move : moves) {

            notation.add(
                    board.formatMove(move)
            );
        }

        notation.sort(
                String::compareTo
        );

        System.out.println(
                "Legal moves:"
        );

        System.out.println(
                String.join(
                        " ",
                        notation
                )
        );
    }

    private static void printHelp() {

        System.out.println();

        System.out.println(
                "SAN examples:"
        );

        System.out.println(
                "  e4       pawn move"
        );

        System.out.println(
                "  Nf3      knight move"
        );

        System.out.println(
                "  Nbd2     specify knight file"
        );

        System.out.println(
                "  R1e2     specify rook rank"
        );

        System.out.println(
                "  Bxe6+    capture with check"
        );

        System.out.println(
                "  Qh7#     checkmate"
        );

        System.out.println(
                "  O-O      kingside castle"
        );

        System.out.println(
                "  O-O-O    queenside castle"
        );

        System.out.println(
                "  e8=Q     promotion"
        );

        System.out.println();

        System.out.println(
                "Draw commands:"
        );

        System.out.println(
                "  claim50"
        );

        System.out.println(
                "  claim3"
        );

        System.out.println();

        System.out.println(
                "Also accepted:"
        );

        System.out.println(
                "  e2e4"
        );

        System.out.println(
                "  e2-e4"
        );

        System.out.println(
                "  e2 e4"
        );

        System.out.println(
                "  e7e8Q"
        );

        System.out.println();
    }
}