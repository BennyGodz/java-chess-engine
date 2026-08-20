package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Streams games from selected Lichess players and generates Stockfish training positions.
 *
 * <p>No Lichess database is downloaded.
 *
 * <p>Pipeline:
 *
 * <p>Lichess NDJSON game stream ↓ PGN moves ↓ Board positions ↓ Stockfish evaluation ↓
 * training_data.txt
 *
 * <p>Format:
 *
 * <p>FEN|evaluation
 *
 * <p>Evaluation is in pawns from White's perspective.
 */
public class StockfishDataGenerator {

  /*
   * Your Stockfish executable.
   */
  private static final String STOCKFISH_PATH = "C:\\Users\\Benny\\stockfish\\stockfish.exe";

  /*
   * Output training file.
   */
  private static final String OUTPUT_FILE = "training_data.txt";

  /*
   * Number of positions to generate.
   */
  private static final int POSITION_COUNT = 500_000;

  /*
   * Stockfish analysis depth.
   *
   * 16 is a reasonable starting point.
   * Increase later if you want stronger labels.
   */
  private static final int STOCKFISH_DEPTH = 16;

  /*
   * Number of games requested from each player.
   *
   * Lichess streams the games directly.
   */
  private static final int GAMES_PER_PLAYER = 100;

  /*
   * Only analyze positions after this many plies.
   *
   * This avoids filling the dataset with almost
   * identical starting positions.
   */
  private static final int MIN_PLIES = 8;

  /*
   * Analyze roughly every N plies.
   *
   * 2 means every move.
   * 4 means every second move.
   *
   * This helps create a diverse dataset while
   * keeping Stockfish generation manageable.
   */
  private static final int POSITION_INTERVAL = 2;

  /*
   * Fixed seed for reproducibility.
   */
  private static final Random RANDOM = new Random(12345);

  /*
   * Players supplied by you.
   *
   * These are used directly instead of trying to
   * query the Lichess leaderboard API.
   */
  private static final List<String> PLAYERS =
      List.of(

          // Bullet / top players
          "Ediz_Gurel",
          "KnightCheckShadow",
          "GlasnostPerestroika",
          "muisback",
          "SindarovGM",
          "Arkadiy_Khromaev",
          "chess-art-us",
          "SergioOliva64",
          "VincentKeymer2004",
          "Zhigalko_Sergei",

          // Blitz
          "cutemouse83",
          "aspiringstar",
          "Vladimirovich9000",
          "Savitar_f",
          "Dr_Tiger",
          "mraquariyaz67",
          "athena-pallada",
          "Polyclinical",
          "Yarebore",

          // Rapid
          "Unicorn7Love",
          "Kurald_Galain",
          "Anatolianchess",
          "canc3111",
          "Soup_Maktavish_t",
          "stanbad1",
          "bakingintheoven",
          "chessmem",
          "T34USSR",
          "OX528354",

          // Classical
          "KryptoChessClub",
          "Vlad_Lazarev79",
          "gek76",
          "Kayrosas",
          "Tem7702",
          "ChessTheory64",
          "MW1966",
          "alesha_kiselev",
          "Mikevs");

  public static void main(String[] args) {

    System.out.println("Starting Lichess → Stockfish training generation...");

    System.out.println();

    System.out.println("Players: " + PLAYERS.size());

    System.out.println("Target positions: " + POSITION_COUNT);

    System.out.println("Stockfish depth: " + STOCKFISH_DEPTH);

    System.out.println("Games per player: " + GAMES_PER_PLAYER);

    System.out.println();

    try (Stockfish stockfish = new Stockfish(STOCKFISH_PATH);
        BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {

      int generated = 0;

      /*
       * Shuffle the players so that the dataset
       * does not always start with the same player.
       */
      List<String> shuffledPlayers = new ArrayList<>(PLAYERS);

      Collections.shuffle(shuffledPlayers, RANDOM);

      for (String username : shuffledPlayers) {

        if (generated >= POSITION_COUNT) {
          break;
        }

        System.out.println();
        System.out.println("Streaming games from: " + username);

        try {

          generated = processPlayer(username, stockfish, writer, generated);

        } catch (Exception e) {

          System.err.println("Could not process " + username + ": " + e.getMessage());
        }

        System.out.println("Total generated: " + generated + " / " + POSITION_COUNT);
      }

      writer.flush();

      System.out.println();
      System.out.println("Finished generating training data.");

      System.out.println("Generated positions: " + generated);

      System.out.println("Saved to: " + OUTPUT_FILE);

    } catch (Exception e) {

      System.err.println("Data generation failed:");

      e.printStackTrace();
    }
  }

  /** Streams games from one Lichess player. */
  private static int processPlayer(
      String username, Stockfish stockfish, BufferedWriter writer, int generated) throws Exception {

    /*
     * Lichess API endpoint.
     *
     * We request PGN directly.
     *
     * The response is streamed, so the entire
     * database is never downloaded.
     */
    String urlString =
        "https://lichess.org/api/games/user/"
            + username
            + "?max="
            + GAMES_PER_PLAYER
            + "&pgnInJson=false"
            + "&clocks=false"
            + "&evals=false"
            + "&opening=false";

    URL url = new URL(urlString);

    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

    connection.setRequestMethod("GET");

    connection.setRequestProperty("Accept", "application/x-chess-pgn");

    connection.setRequestProperty("User-Agent", "BugaBot/1.0 chess training");

    connection.setConnectTimeout(15000);

    connection.setReadTimeout(60000);

    int responseCode = connection.getResponseCode();

    if (responseCode != 200) {

      throw new IOException("Lichess returned HTTP " + responseCode);
    }

    try (InputStream input = connection.getInputStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {

      StringBuilder currentGame = new StringBuilder();

      String line;

      while ((line = reader.readLine()) != null) {

        /*
         * A blank line after movetext can
         * separate PGN games.
         */
        if (line.trim().isEmpty()) {

          if (currentGame.length() > 0) {

            generated = processGame(currentGame.toString(), stockfish, writer, generated);

            currentGame.setLength(0);

            if (generated >= POSITION_COUNT) {
              break;
            }
          }

        } else {

          currentGame.append(line).append('\n');
        }
      }

      /*
       * Process final game if the stream
       * did not end with a blank line.
       */
      if (currentGame.length() > 0 && generated < POSITION_COUNT) {

        generated = processGame(currentGame.toString(), stockfish, writer, generated);
      }
    }

    connection.disconnect();

    return generated;
  }

  /** Processes one PGN game. */
  private static int processGame(
      String pgn, Stockfish stockfish, BufferedWriter writer, int generated) {

    try {

      List<String> moves = extractMoves(pgn);

      if (moves.size() < MIN_PLIES) {
        return generated;
      }

      Board board = new Board();

      /*
       * Play through the game.
       */
      for (int ply = 0; ply < moves.size(); ply++) {

        String san = moves.get(ply);

        /*
         * Convert SAN to an actual legal Move.
         */
        Move move = findMoveFromSAN(board, san);

        if (move == null) {

          /*
           * If our parser cannot understand
           * the game, abandon this game.
           */
          return generated;
        }

        board.playMove(move);

        int currentPly = ply + 1;

        /*
         * Save positions at the requested
         * interval.
         */
        if (currentPly >= MIN_PLIES && currentPly % POSITION_INTERVAL == 0) {

          if (generated >= POSITION_COUNT) {
            return generated;
          }

          /*
           * Don't train on terminal positions.
           */
          boolean side = board.isWhiteToMove();

          if (board.getLegalMoves(side).isEmpty()) {
            continue;
          }

          String fen = board.toFEN();

          double evaluation = stockfish.evaluate(fen, STOCKFISH_DEPTH);

          if (Double.isNaN(evaluation) || Double.isInfinite(evaluation)) {
            continue;
          }

          /*
           * Stockfish normally returns the
           * evaluation from the side to move.
           *
           * Convert it to White's perspective.
           */
          if (!side) {
            evaluation = -evaluation;
          }

          /*
           * Keep evaluations within a
           * reasonable training range.
           */
          evaluation = Math.max(-10.0, Math.min(10.0, evaluation));

          writer.write(fen + "|" + evaluation);

          writer.newLine();

          generated++;

          if (generated % 100 == 0) {

            writer.flush();

            System.out.println("Generated " + generated + " / " + POSITION_COUNT);
          }
        }
      }

    } catch (Exception ignored) {

      /*
       * One malformed game should not stop
       * the entire training process.
       */
    }

    return generated;
  }

  /** Extracts SAN moves from a PGN. */
  private static List<String> extractMoves(String pgn) {

    /*
     * Remove PGN comments.
     */
    pgn = pgn.replaceAll("\\{[^}]*}", " ");

    /*
     * Remove variations.
     *
     * This repeatedly removes (...) blocks.
     */
    String previous;

    do {

      previous = pgn;

      pgn = pgn.replaceAll("\\([^()]*\\)", " ");

    } while (!pgn.equals(previous));

    /*
     * Remove NAGs such as $1.
     */
    pgn = pgn.replaceAll("\\$\\d+", " ");

    /*
     * Remove headers.
     */
    pgn = pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");

    /*
     * Normalize whitespace.
     */
    pgn = pgn.replace("\r", " ").replace("\n", " ");

    String[] tokens = pgn.trim().split("\\s+");

    List<String> moves = new ArrayList<>();

    for (String token : tokens) {

      if (token.isEmpty()) {
        continue;
      }

      /*
       * Game result.
       */
      if (token.equals("1-0")
          || token.equals("0-1")
          || token.equals("1/2-1/2")
          || token.equals("*")) {
        continue;
      }

      /*
       * Move numbers:
       *
       * 1.
       * 1...
       * 25.
       */
      if (token.matches("\\d+\\.{1,3}")) {
        continue;
      }

      /*
       * Occasionally a move number is attached
       * directly to the SAN.
       *
       * Example:
       *
       * 1.e4
       */
      token = token.replaceFirst("^\\d+\\.{1,3}", "");

      if (token.isEmpty()) {
        continue;
      }

      moves.add(token);
    }

    return moves;
  }

  /**
   * Finds the legal Move represented by SAN.
   *
   * <p>This uses your existing Board legal move generation rather than implementing chess move
   * legality again.
   */
  private static Move findMoveFromSAN(Board board, String san) {

    /*
     * Remove check/checkmate markers.
     */
    String clean = san.replace("+", "").replace("#", "").replace("!", "").replace("?", "");

    /*
     * Castling.
     */
    if (clean.equals("O-O") || clean.equals("0-0")) {

      boolean white = board.isWhiteToMove();

      List<Move> moves = board.getLegalMoves(white);

      for (Move move : moves) {

        if (move.isCastling() && move.getEnd().getColumn() == 6) {
          return move;
        }
      }

      return null;
    }

    if (clean.equals("O-O-O") || clean.equals("0-0-0")) {

      boolean white = board.isWhiteToMove();

      List<Move> moves = board.getLegalMoves(white);

      for (Move move : moves) {

        if (move.isCastling() && move.getEnd().getColumn() == 2) {
          return move;
        }
      }

      return null;
    }

    /*
     * Promotion.
     *
     * Example:
     *
     * e8=Q
     */
    char promotion = '\0';

    int promotionIndex = clean.indexOf('=');

    if (promotionIndex >= 0) {

      if (promotionIndex + 1 < clean.length()) {

        promotion = clean.charAt(promotionIndex + 1);
      }

      clean = clean.substring(0, promotionIndex);
    }

    /*
     * Destination is always the final two
     * characters of a normal SAN move.
     */
    if (clean.length() < 2) {
      return null;
    }

    String destination = clean.substring(clean.length() - 2);

    char file = destination.charAt(0);

    char rank = destination.charAt(1);

    if (file < 'a' || file > 'h' || rank < '1' || rank > '8') {
      return null;
    }

    int targetColumn = file - 'a';

    int targetRow = 8 - (rank - '0');

    /*
     * Everything before the destination tells
     * us the piece type and disambiguation.
     */
    String prefix = clean.substring(0, clean.length() - 2);

    char pieceLetter = prefix.isEmpty() ? 'P' : prefix.charAt(0);

    boolean pawn = pieceLetter == 'P' || "abcdefgh".indexOf(pieceLetter) >= 0;

    if (pawn) {

      /*
       * Pawn SAN:
       *
       * e4
       * exd5
       *
       * Find the legal pawn that reaches
       * the destination.
       */
      boolean white = board.isWhiteToMove();

      List<Move> moves = board.getLegalMoves(white);

      for (Move move : moves) {

        if (move.getEnd().getRow() != targetRow || move.getEnd().getColumn() != targetColumn) {
          continue;
        }

        chess.pieces.Piece piece = board.getPiece(move.getStart());

        if (!(piece instanceof chess.pieces.Pawn)) {
          continue;
        }

        if (move.isPromotion()) {

          if (promotion == '\0') {
            continue;
          }

          chess.pieces.Piece promoted = move.getPromotionPiece();

          if (promotion == 'Q' && promoted instanceof chess.pieces.Queen) {
            return move;
          }

          if (promotion == 'R' && promoted instanceof chess.pieces.Rook) {
            return move;
          }

          if (promotion == 'B' && promoted instanceof chess.pieces.Bishop) {
            return move;
          }

          if (promotion == 'N' && promoted instanceof chess.pieces.Knight) {
            return move;
          }

          continue;
        }

        /*
         * If SAN contains a capture file,
         * verify it.
         */
        if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) == 'x') {
          return move;
        }

        if (!prefix.contains("x")) {
          return move;
        }
      }

      return null;
    }

    /*
     * Piece move.
     */
    Class<?> pieceClass;

    switch (pieceLetter) {
      case 'N' -> pieceClass = chess.pieces.Knight.class;

      case 'B' -> pieceClass = chess.pieces.Bishop.class;

      case 'R' -> pieceClass = chess.pieces.Rook.class;

      case 'Q' -> pieceClass = chess.pieces.Queen.class;

      case 'K' -> pieceClass = chess.pieces.King.class;

      default -> pieceClass = null;
    }

    if (pieceClass == null) {
      return null;
    }

    /*
     * Remaining prefix contains:
     *
     * N
     * Nf
     * N1
     * Nfx
     * N1x
     */
    String disambiguation = prefix.substring(1);

    boolean capture = disambiguation.contains("x");

    disambiguation = disambiguation.replace("x", "");

    boolean white = board.isWhiteToMove();

    List<Move> legalMoves = board.getLegalMoves(white);

    Move found = null;

    for (Move move : legalMoves) {

      if (move.getEnd().getRow() != targetRow || move.getEnd().getColumn() != targetColumn) {
        continue;
      }

      chess.pieces.Piece piece = board.getPiece(move.getStart());

      if (piece == null || piece.getClass() != pieceClass) {
        continue;
      }

      /*
       * Check whether this move is a capture.
       */
      boolean actualCapture = move.isEnPassant() || board.getPiece(move.getEnd()) != null;

      if (capture != actualCapture) {
        continue;
      }

      /*
       * File disambiguation.
       */
      if (disambiguation.length() == 1
          && disambiguation.charAt(0) >= 'a'
          && disambiguation.charAt(0) <= 'h') {

        int sourceFile = disambiguation.charAt(0) - 'a';

        if (move.getStart().getColumn() != sourceFile) {
          continue;
        }
      }

      /*
       * Rank disambiguation.
       */
      if (disambiguation.length() == 1 && Character.isDigit(disambiguation.charAt(0))) {

        int sourceRank = disambiguation.charAt(0) - '0';

        int actualRank = 8 - move.getStart().getRow();

        if (actualRank != sourceRank) {
          continue;
        }
      }

      /*
       * Both file and rank.
       */
      if (disambiguation.length() == 2) {

        char sourceFile = disambiguation.charAt(0);

        char sourceRank = disambiguation.charAt(1);

        if (sourceFile < 'a' || sourceFile > 'h' || sourceRank < '1' || sourceRank > '8') {
          continue;
        }

        int expectedFile = sourceFile - 'a';

        int expectedRank = sourceRank - '0';

        if (move.getStart().getColumn() != expectedFile
            || 8 - move.getStart().getRow() != expectedRank) {
          continue;
        }
      }

      /*
       * We found the legal move matching SAN.
       */
      if (found != null) {

        /*
         * Ambiguous SAN should never happen
         * in a valid PGN because SAN should
         * contain enough disambiguation.
         */
        return null;
      }

      found = move;
    }

    return found;
  }
}
