package chess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.ChessEngine;
import chess.engine.search.SearchEngine;
import chess.engine.evaluation.*;
import chess.pieces.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Console chess UI with SAN input and SAN output. */
public class Main {

    private static Position parseSquare(String square) {
        if (square == null || !square.matches("[a-hA-H][1-8]")) {
            throw new IllegalArgumentException("Invalid square: " + square);
        }
        char file = Character.toLowerCase(square.charAt(0));
        char rank = square.charAt(1);
        return new Position(8 - (rank - '0'), file - 'a');
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("================================");
        System.out.println("        JAVA CHESS ENGINE");
        System.out.println("================================");
        System.out.println("SAN input is supported: e4, Nf3, Nbd2, R1e2, Bxe6+, O-O, O-O-O, e8=Q");
        System.out.println("You can also enter coordinate moves such as e2e4 or e2 e4.");
        System.out.println();

        System.out.println("Choose your color:");
        System.out.println("1. White");
        System.out.println("2. Black");
        System.out.print("> ");

        String choiceLine = scanner.nextLine().trim();
        boolean playerIsWhite;
        if (choiceLine.equals("1")) playerIsWhite = true;
        else if (choiceLine.equals("2")) playerIsWhite = false;
        else {
            System.out.println("Invalid choice.");
            scanner.close();
            return;
        }

        System.out.println("\nYou are playing " + (playerIsWhite ? "White" : "Black") + ".");
        boolean engineIsWhite = !playerIsWhite;
        Board board = new Board();
        ChessEngine engine = new ChessEngine();

        // Engine settings.
        final int ENGINE_DEPTH = 9;
        final long ENGINE_TIME_MS = 1000;

        while (true) {
            board.printBoard(playerIsWhite);
            System.out.println("Move " + board.getFullmoveNumber() + ": " + board.getGameStatus());

            // Automatic game-ending conditions.
            boolean side = board.isWhiteToMove();
            if (board.isCheckmate(side)
                    || board.isStalemate(side)
                    || board.isSeventyFiveMoveRule()
                    || board.isFivefoldRepetition()
                    || board.isInsufficientMaterial()) {
                break;
            }

            // Let the engine play its side automatically.
            if (side == engineIsWhite) {
                System.out.println("Engine is thinking...");

                SearchEngine.SearchResult result = engine.findBestMove(
                        board,
                        ENGINE_DEPTH,
                        ENGINE_TIME_MS
                );

                Move engineMove = result.bestMove();
                if (engineMove == null) {
                    System.out.println("Engine found no legal move.");
                    break;
                }

                String engineSan = board.formatMove(engineMove);
                board.playMove(engineMove);

                System.out.printf(
                        "Engine plays %s  [depth %d, nodes %,d]%n%n",
                        engineSan,
                        result.depth(),
                        result.nodes()
                );
                continue;
            }

            System.out.println("Enter SAN (e.g. e4, Nf3, Nbd2, R1e2, Bxe6+, O-O, e8=Q)");
            System.out.println("Commands: moves, fen, help, claim50, claim3, eval, quit");
            System.out.print("> ");

            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("quit")) break;

            try {
                if (input.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }
                if (input.equalsIgnoreCase("fen")) {
                    System.out.println(board.toFEN());
                    continue;
                }
                if (input.equalsIgnoreCase("claim50")) {
                    if (board.isFiftyMoveRule()) {
                        System.out.println("Draw claimed by the 50-move rule.");
                        break;
                    }
                    System.out.println("The 50-move rule is not currently claimable.");
                    continue;
                }
                if (input.equalsIgnoreCase("claim3")) {
                    if (board.isThreefoldRepetition()) {
                        System.out.println("Draw claimed by threefold repetition.");
                        break;
                    }
                    System.out.println("Threefold repetition is not currently claimable.");
                    continue;
                }
                if (input.equalsIgnoreCase("moves")) {
                    printLegalMoves(board);
                    continue;
                }
                if (input.equalsIgnoreCase("eval")) {
                    System.out.println("Engine is searching...");

                    // Call the search tree instead of the static evaluator
                    SearchEngine.SearchResult result = engine.findBestMove(
                            board,
                            ENGINE_DEPTH,
                            ENGINE_TIME_MS
                    );

                    // Print the matched evaluation score
                    System.out.printf(
                            "Search Evaluation: %+.2f  [depth %d, nodes %,d]%n",
                            result.score() / 100.0,
                            result.depth(),
                            result.nodes()
                    );
                    continue;
                }

                Move move = parseMove(board, input);
                if (move == null) {
                    System.out.println("Invalid or illegal move. Use SAN such as Nbd2 when pieces are ambiguous.");
                    continue;
                }

                String san = board.formatMove(move);
                board.playMove(move);
                System.out.println("Played " + san);
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid move: " + e.getMessage());
            }
        }

        System.out.println("\nGame over.");
        System.out.println(board.getGameStatus());
        scanner.close();
    }

    /** Parse either SAN, long algebraic, or coordinate notation into one unique legal Move. */
    private static Move parseMove(Board board, String rawInput) {
        String input = normalizeSan(rawInput);
        if (input.isEmpty()) return null;

        List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

        // Castling.
        if (input.equals("O-O")) {
            return uniqueEndMove(legalMoves, 6);
        }
        if (input.equals("O-O-O")) {
            return uniqueEndMove(legalMoves, 2);
        }

        // Coordinate / long algebraic forms:
        // e2e4, e2-e4, e2 e4, e7e8Q,
        // c7xb8=Q, c7xb8Q, c7-b8=Q.
        Move coordinateMove = parseCoordinateMove(board, input, legalMoves);
        if (coordinateMove != null) return coordinateMove;

        // SAN: [piece][disambiguation][x]square[=promotion][+/#]
        boolean suppliedMate = input.endsWith("#");
        boolean suppliedCheck = input.endsWith("+");
        String core = input.replaceFirst("[+#]+$", "");

        char promotion = '\0';

        // Accept both =Q and Q promotion suffixes.
        int promotionIndex = core.indexOf('=');
        if (promotionIndex >= 0) {
            if (promotionIndex != core.length() - 2) return null;
            promotion = Character.toUpperCase(core.charAt(core.length() - 1));
            core = core.substring(0, promotionIndex);
        } else if (core.length() >= 3) {
            char last = Character.toUpperCase(core.charAt(core.length() - 1));
            if ((last == 'Q' || last == 'R' || last == 'B' || last == 'N')
                    && core.charAt(core.length() - 2) >= '1'
                    && core.charAt(core.length() - 2) <= '8') {
                promotion = last;
                core = core.substring(0, core.length() - 1);
            }
        }

        if (!core.matches("[KQRBN]?[a-h1-8]{0,2}x?[a-h][1-8]")) return null;

        String destinationText = core.substring(core.length() - 2);
        Position destination = parseSquare(destinationText);
        String prefix = core.substring(0, core.length() - 2);

        boolean captureSpecified = prefix.contains("x");
        prefix = prefix.replace("x", "");

        char pieceLetter = 'P';
        if (!prefix.isEmpty() && "KQRBN".indexOf(prefix.charAt(0)) >= 0) {
            pieceLetter = prefix.charAt(0);
            prefix = prefix.substring(1);
        }

        if (prefix.length() > 2) return null;
        String disambiguation = prefix;

        List<Move> matches = new ArrayList<>();
        for (Move move : legalMoves) {
            if (!move.getEnd().equals(destination)) continue;

            Piece piece = board.getPiece(move.getStart());
            if (piece == null || piece.getNotationSymbol() != pieceLetter) continue;

            boolean isCapture = move.isEnPassant() || board.getPiece(move.getEnd()) != null;
            // In coordinate notation, x is optional. If supplied, it must be correct.
            if (captureSpecified && !isCapture) continue;

            if (move.isPromotion()) {
                if (promotion == '\0') continue;
                if (move.getPromotionPiece().getNotationSymbol() != promotion) continue;
            } else if (promotion != '\0') {
                continue;
            }

            if (!matchesDisambiguation(move, disambiguation)) continue;
            matches.add(move);
        }

        if (matches.size() != 1) return null;

        Move selected = matches.get(0);
        String actual = board.formatMove(selected);
        if (suppliedMate && !actual.endsWith("#")) return null;
        if (suppliedCheck && !(actual.endsWith("+") || actual.endsWith("#"))) return null;

        return selected;
    }

    private static boolean matchesDisambiguation(Move move, String disambiguation) {
        if (disambiguation.isEmpty()) return true;

        char file = (char) ('a' + move.getStart().getColumn());
        char rank = (char) ('8' - move.getStart().getRow());
        if (disambiguation.length() == 1) {
            return disambiguation.charAt(0) == file || disambiguation.charAt(0) == rank;
        }
        return disambiguation.charAt(0) == file && disambiguation.charAt(1) == rank;
    }

    /** Parse long algebraic / coordinate notation, including captures and promotion. */
    private static Move parseCoordinateMove(Board board, String input, List<Move> legalMoves) {
        String compact = input.replace("-", "").replace(" ", "");

        // Optional x, optional = before promotion, optional promotion piece.
        if (!compact.matches("[a-hA-H][1-8]x?[a-hA-H][1-8](=?[QRBNqrbn])?")) {
            return null;
        }

        boolean captureSpecified = compact.charAt(2) == 'x' || compact.charAt(2) == 'X';
        int endIndex = captureSpecified ? 5 : 4;
        String startText = compact.substring(0, 2);
        String endText = compact.substring(captureSpecified ? 3 : 2, captureSpecified ? 5 : 4);

        char promotion = '\0';
        if (compact.length() > endIndex) {
            String suffix = compact.substring(endIndex).replace("=", "");
            if (suffix.length() != 1) return null;
            promotion = Character.toUpperCase(suffix.charAt(0));
        }

        Position start = parseSquare(startText);
        Position end = parseSquare(endText);

        for (Move move : legalMoves) {
            if (!move.getStart().equals(start) || !move.getEnd().equals(end)) continue;

            boolean isCapture = move.isEnPassant() || board.getPiece(move.getEnd()) != null;
            // x is optional in long/coordinate notation. If supplied, require a capture.
            if (captureSpecified && !isCapture) continue;

            if (move.isPromotion()) {
                if (promotion == 'Q' && move.getPromotionPiece() instanceof Queen) return move;
                if (promotion == 'R' && move.getPromotionPiece() instanceof Rook) return move;
                if (promotion == 'B' && move.getPromotionPiece() instanceof Bishop) return move;
                if (promotion == 'N' && move.getPromotionPiece() instanceof Knight) return move;
            } else if (promotion == '\0') {
                return move;
            }
        }
        return null;
    }

    private static Move uniqueEndMove(List<Move> legalMoves, int endColumn) {
        for (Move move : legalMoves) {
            if (move.isCastling() && move.getEnd().getColumn() == endColumn) return move;
        }
        return null;
    }

    private static String normalizeSan(String input) {
        String s = input.trim();
        s = s.replace('0', 'O');
        s = s.replace('−', '-');
        s = s.replace('–', '-');
        s = s.replace('—', '-');
        s = s.replaceAll("\\s+", "");
        return s;
    }

    private static void printLegalMoves(Board board) {
        List<Move> moves = board.getLegalMoves(board.isWhiteToMove());
        List<String> notation = new ArrayList<>();
        for (Move move : moves) notation.add(board.formatMove(move));
        notation.sort(String::compareTo);
        System.out.println("Legal moves:");
        System.out.println(String.join(" ", notation));
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("SAN examples:");
        System.out.println("  e4       pawn move");
        System.out.println("  Nf3      knight move");
        System.out.println("  Nbd2     specify the knight's file");
        System.out.println("  R1e2     specify the rook's rank");
        System.out.println("  Raxe2    specify file when capturing");
        System.out.println("  Bxe6+    capture with check");
        System.out.println("  Qh7#     checkmate");
        System.out.println("  O-O      kingside castle");
        System.out.println("  O-O-O    queenside castle");
        System.out.println("  e8=Q     promotion");
        System.out.println("\nDraw commands:");
        System.out.println("  claim50  claim a 50-move-rule draw when available");
        System.out.println("  claim3   claim a threefold-repetition draw when available");
        System.out.println("\nAlso accepted: e2e4, e2-e4, e2 e4, e7e8Q");
        System.out.println();
    }
}
