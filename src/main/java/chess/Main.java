package chess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.opening.OpeningManager;
import chess.engine.search.SearchEngine;
import chess.engine.training.SanMoveParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Console chess UI with SAN input and output. The engine checks book moves before playing them and
 * abandons the opening if an opponent's deviation makes the book move unsafe.
 */
public class Main {

  /* Blitz settings for the normal search. */
  private static final int ENGINE_DEPTH = 9;
  private static final long ENGINE_TIME_MS = 1000;

  /* Short search used only to verify that a book move is still safe. */
  private static final int OPENING_CHECK_DEPTH = 5;
  private static final long OPENING_CHECK_TIME_MS = 150;

  /* A book move worse than this many centipawns is rejected. */
  private static final int MAX_OPENING_LOSS_CP = 50;

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
    System.out.println(
        "SAN input is supported: " + "e4, Nf3, Nbd2, R1e2, " + "Bxe6+, O-O, O-O-O, e8=Q");
    System.out.println("You can also enter coordinate moves " + "such as e2e4 or e2 e4.");
    System.out.println();
    System.out.println("Choose your color:");
    System.out.println("1. White");
    System.out.println("2. Black");
    System.out.print("> ");

    String choiceLine = scanner.nextLine().trim();
    boolean playerIsWhite;
    if (choiceLine.equals("1")) {
      playerIsWhite = true;
    } else if (choiceLine.equals("2")) {
      playerIsWhite = false;
    } else {
      System.out.println("Invalid choice.");
      scanner.close();
      return;
    }

    System.out.println("\nYou are playing " + (playerIsWhite ? "White" : "Black") + ".");

    boolean engineIsWhite = !playerIsWhite;
    Board board = new Board();
    SearchEngine engine = new SearchEngine();
    OpeningManager openingManager = new OpeningManager();

    while (true) {
      board.printBoard(playerIsWhite);
      // getGameStatus reports automatic game endings.
      System.out.println("Move " + board.getFullmoveNumber() + ": " + board.getGameStatus());
      boolean side = board.isWhiteToMove();
      if (board.isCheckmate(side)
          || board.isStalemate(side)
          || board.isSeventyFiveMoveRule()
          || board.isFivefoldRepetition()
          || board.isInsufficientMaterial()) {
        break;
      }
      // Engine's turn.
      if (side == engineIsWhite) {
        makeEngineMove(board, openingManager, engine);
        continue;
      }
      // Human's turn.
      System.out.println("Enter SAN " + "(e.g. e4, Nf3, Nbd2, " + "R1e2, Bxe6+, O-O, e8=Q)");
      System.out.println("Commands: moves, fen, help, " + "claim50, claim3, eval, quit");
      System.out.print("> ");
      String input = scanner.nextLine().trim();
      if (input.equalsIgnoreCase("quit")) {
        break;
      }
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
            System.out.println("Draw claimed by the " + "50-move rule.");
            break;
          }
          System.out.println("The 50-move rule is " + "not currently claimable.");
          continue;
        }
        if (input.equalsIgnoreCase("claim3")) {
          if (board.isThreefoldRepetition()) {
            System.out.println("Draw claimed by " + "threefold repetition.");
            break;
          }
          System.out.println("Threefold repetition is " + "not currently claimable.");
          continue;
        }
        if (input.equalsIgnoreCase("moves")) {
          printLegalMoves(board);
          continue;
        }
        if (input.equalsIgnoreCase("eval")) {
          System.out.println("Engine is searching...");
          SearchEngine.SearchResult result =
              engine.findBestMove(board, ENGINE_DEPTH, ENGINE_TIME_MS);
          System.out.printf(
              "Search Evaluation: %+.2f " + "[depth %d, nodes %,d]%n",
              result.score() / 100.0, result.depth(), result.nodes());
          continue;
        }
        Move move = parseMove(board, input);
        if (move == null) {
          System.out.println("Invalid or illegal move.");
          continue;
        }
        String san = board.formatMove(move);
        // The OpeningManager itself decides whether the current position can continue the opening.
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

  /**
   * Makes an engine move. If a book move exists, both the position and the position after the book
   * move are searched; the book move is played only when it is not significantly worse than the
   * engine's own choice. This prevents opponent deviations from forcing bad opening moves.
   */
  private static void makeEngineMove(
      Board board, OpeningManager openingManager, SearchEngine engine) {
    System.out.println();
    System.out.println("================================");
    System.out.println("ENGINE THINKING...");
    System.out.println("================================");
    System.out.println("Position: " + board.toFEN());

    Move bookMove = null;
    if (openingManager.isOpeningActive()) {
      bookMove = openingManager.getOpeningMove(board);
    }

    // No book move: use the normal search.
    if (bookMove == null) {
      SearchEngine.SearchResult result = engine.findBestMove(board, ENGINE_DEPTH, ENGINE_TIME_MS);
      Move bestMove = result.bestMove();
      if (bestMove == null) {
        System.out.println("Engine found no legal move.");
        return;
      }
      playEngineMove(board, bestMove, result);
      return;
    }

    System.out.println("Book move candidate: " + board.formatMove(bookMove));

    // Search the current position to get the engine's own best move.
    SearchEngine.SearchResult normalResult =
        engine.findBestMove(board, OPENING_CHECK_DEPTH, OPENING_CHECK_TIME_MS);
    Move normalBestMove = normalResult.bestMove();
    if (normalBestMove == null) {
      // Something unusual happened; fall back to the book move.
      playBookMove(board, openingManager, bookMove);
      return;
    }

    // Search the position after the book move on a copy so the real board is not modified.
    Board bookBoard = new Board(board);
    bookBoard.playMove(findEquivalentMove(bookBoard, bookMove));

    // The score is from the opponent's perspective, so negate it.
    SearchEngine.SearchResult bookResult =
        engine.findBestMove(bookBoard, OPENING_CHECK_DEPTH, OPENING_CHECK_TIME_MS);
    int normalScore = normalResult.score();
    int bookScore = -bookResult.score();
    int difference = normalScore - bookScore;

    System.out.println("Opening safety check:");
    System.out.printf("Normal engine: %+.2f%n", normalScore / 100.0);
    System.out.printf("Book move:     %+.2f%n", bookScore / 100.0);
    System.out.printf("Difference:    %.2f%n", difference / 100.0);

    if (difference <= MAX_OPENING_LOSS_CP) {
      playBookMove(board, openingManager, bookMove);
      return;
    }

    // The book move is too bad; abandon the opening permanently.
    System.out.println();
    System.out.println("BOOK MOVE REJECTED.");
    System.out.println("Opponent's move made the " + "opening move unsafe.");
    openingManager.disable();
    playEngineMove(board, normalBestMove, normalResult);
  }

  /** Plays the given book move and advances the opening manager. */
  private static void playBookMove(Board board, OpeningManager openingManager, Move bookMove) {
    String san = board.formatMove(bookMove);
    board.playMove(bookMove);
    openingManager.advance();
    System.out.println();
    System.out.println("PLAYING BOOK MOVE");
    System.out.println("Move: " + san);
    System.out.println("Opening: " + openingManager.getOpeningName());
  }

  /** Finds the equivalent move on another board. */
  private static Move findEquivalentMove(Board board, Move original) {
    char promotion =
        original.isPromotion() ? original.getPromotionPiece().getNotationSymbol() : '\0';
    Move move = board.findLegalMove(original.getStart(), original.getEnd(), promotion);
    if (move != null) return move;
    throw new IllegalStateException("Could not recreate opening move.");
  }

  /** Plays a normal engine move and prints its statistics. */
  private static void playEngineMove(Board board, Move move, SearchEngine.SearchResult result) {
    String san = board.formatMove(move);
    board.playMove(move);
    System.out.printf(
        "Engine plays %s  " + "[depth %d, nodes %,d, score %+.2f]%n%n",
        san, result.depth(), result.nodes(), result.score() / 100.0);
  }

  /** Parses SAN, long algebraic, or coordinate notation. */
  private static Move parseMove(Board board, String rawInput) {
    String input = normalizeSan(rawInput);
    if (input.isEmpty()) return null;
    Move coordinateMove = parseCoordinateMove(board, input);
    return coordinateMove != null ? coordinateMove : SanMoveParser.parse(board, input);
  }

  /** Parses coordinate notation such as e2e4, e7e8Q or g1f3. */
  private static Move parseCoordinateMove(Board board, String input) {
    String compact = input.replace("-", "").replace(" ", "");
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
      if (suffix.length() != 1) {
        return null;
      }
      promotion = Character.toUpperCase(suffix.charAt(0));
    }

    Move move = board.findLegalMove(parseSquare(startText), parseSquare(endText), promotion);
    boolean capture = move != null && (move.isEnPassant() || board.getPiece(move.getEnd()) != null);
    return captureSpecified && !capture ? null : move;
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
    for (Move move : moves) {
      notation.add(board.formatMove(move));
    }
    notation.sort(String::compareTo);
    System.out.println("Legal moves:");
    System.out.println(String.join(" ", notation));
  }

  private static void printHelp() {
    System.out.println();
    System.out.println("SAN examples:");
    System.out.println("  e4       pawn move");
    System.out.println("  Nf3      knight move");
    System.out.println("  Nbd2     specify knight file");
    System.out.println("  R1e2     specify rook rank");
    System.out.println("  Bxe6+    capture with check");
    System.out.println("  Qh7#     checkmate");
    System.out.println("  O-O      kingside castle");
    System.out.println("  O-O-O    queenside castle");
    System.out.println("  e8=Q     promotion");
    System.out.println();
    System.out.println("Draw commands:");
    System.out.println("  claim50");
    System.out.println("  claim3");
    System.out.println();
    System.out.println("Also accepted:");
    System.out.println("  e2e4");
    System.out.println("  e2-e4");
    System.out.println("  e2 e4");
    System.out.println("  e7e8Q");
    System.out.println();
  }
}
