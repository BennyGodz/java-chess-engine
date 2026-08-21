package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StockfishDataGenerator {
  private static final String STOCKFISH_PATH = "C:\\Users\\Benny\\stockfish\\stockfish.exe";
  private static final String OUTPUT_FILE = "training_data.txt";
  private static final String OUTPUT_MOVES_FILE = "training_moves.txt";
  private static final int POSITION_COUNT = 750_000;
  private static final int STOCKFISH_DEPTH = 10;
  private static final int GAMES_PER_PLAYER = 100;
  private static final int MIN_PLIES = 8;
  private static final int POSITION_INTERVAL = 1;
  private static final Random RANDOM = new Random(12345);

  private static final List<String> PLAYERS =
      List.of(
          "Ediz_Gurel",
          "SindarovGM",
          "muisback",
          "GlasnostPerestroika",
          "Arkadiy_Khromaev",
          "VincentKeymer2004",
          "Savitar_f",
          "Dr_Tiger",
          "MonaLisa",
          "Polyclinical",
          "Unicorn7Love",
          "Kurald_Galain",
          "Anatolianchess",
          "canc3111",
          "Soup_Maktavish_t",
          "stanbad1",
          "bakingintheoven",
          "chessmem",
          "T34USSR",
          "KryptoChessClub");

  private static final int MAX_RETRIES_PER_PLAYER = 3;
  private static final long INITIAL_DELAY_MS = 600;
  private static final double DELAY_MULTIPLIER = 2.0;

  private static final ThreadLocal<Stockfish> stockfishPool =
      ThreadLocal.withInitial(
          () -> {
            try {
              return new Stockfish(STOCKFISH_PATH);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });

  public static void main(String[] args) {
    System.out.println("Starting Lichess -> Stockfish training generation (Parallel)...");
    Set<String> uniqueFens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    List<String> shuffledPlayers = new ArrayList<>(PLAYERS);
    Collections.shuffle(shuffledPlayers, RANDOM);

    PrintWriter movesPrintWriter = null;
    try {
      movesPrintWriter = new PrintWriter(new FileWriter(OUTPUT_MOVES_FILE));
    } catch (IOException e) {
      System.err.println("Could not create moves file: " + e.getMessage());
      movesPrintWriter = null;
    }

    for (String username : shuffledPlayers) {
      if (uniqueFens.size() >= POSITION_COUNT) break;
      System.out.println("Fetching games from: " + username);
      int retries = 0;
      long delayMs = INITIAL_DELAY_MS;
      boolean success = false;
      while (retries < MAX_RETRIES_PER_PLAYER && !success) {
        try {
          Thread.sleep(delayMs);
          fetchFensFromPlayer(username, uniqueFens, movesPrintWriter);
          success = true;
        } catch (IOException e) {
          if (e.getMessage().contains("429")) {
            System.err.println("Rate limited for " + username + ", retry " + (retries + 1) + "/" + MAX_RETRIES_PER_PLAYER);
            retries++;
            delayMs = (long) (INITIAL_DELAY_MS * Math.pow(DELAY_MULTIPLIER, retries));
          } else {
            System.err.println("Error processing " + username + ": " + e.getMessage());
            break;
          }
        } catch (Exception e) {
          System.err.println("Error processing " + username + ": " + e.getMessage());
          break;
        }
      }
      if (!success) {
        System.err.println("Skipping player " + username + " after " + MAX_RETRIES_PER_PLAYER + " retries");
      }
      System.out.println("Total FENs collected: " + uniqueFens.size());
    }

    if (movesPrintWriter != null) {
      movesPrintWriter.flush();
      movesPrintWriter.close();
    }
    System.out.println("Total FENs collected: " + uniqueFens.size());

    List<String> tempFensList = new ArrayList<>(uniqueFens);
    if (tempFensList.size() > POSITION_COUNT)
      tempFensList = tempFensList.subList(0, POSITION_COUNT);
    final List<String> fensList = tempFensList;
    System.out.println("Evaluating " + fensList.size() + " FENs in parallel...");

    AtomicInteger generated = new AtomicInteger(0);
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {
      fensList.parallelStream()
          .forEach(
              fen -> {
                try {
                  Stockfish stockfish = stockfishPool.get();
                  double evaluation = stockfish.evaluate(fen, STOCKFISH_DEPTH);
                  if (Double.isNaN(evaluation) || Double.isInfinite(evaluation)) return;
                  if (!fen.contains(" w ")) evaluation = -evaluation;
                  evaluation = Math.max(-10.0, Math.min(10.0, evaluation));
                  String line = fen + "|" + String.format(Locale.US, "%.2f", evaluation);
                  synchronized (writer) {
                    writer.write(line);
                    writer.newLine();
                    int count = generated.incrementAndGet();
                    if (count % 1000 == 0) {
                      System.out.println("Generated: " + count + " / " + fensList.size());
                    }
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                }
              });
      writer.flush();
      System.out.println("Finished generating training data. Saved to: " + OUTPUT_FILE);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void fetchFensFromPlayer(String username, Set<String> uniqueFens, PrintWriter movesPrintWriter)
      throws Exception {
    String urlString =
        "https://lichess.org/api/games/user/"
            + username
            + "?max="
            + GAMES_PER_PLAYER
            + "&pgnInJson=false&clocks=false&evals=false&opening=false";
    HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Accept", "application/x-chess-pgn");
    connection.setRequestProperty("User-Agent", "BugaBot/1.0 chess training");
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(60000);

    if (connection.getResponseCode() != 000)
      throw new IOException("Lichess returned HTTP " + connection.getResponseCode());
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder currentGame = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) {
          if (currentGame.length() > 0) {
            processGameForFens(currentGame.toString(), uniqueFens, movesPrintWriter);
            currentGame.setLength(0);
            if (uniqueFens.size() >= POSITION_COUNT) return;
          }
        } else {
          currentGame.append(line).append('\n');
        }
      }
      if (currentGame.length() > 0 && uniqueFens.size() < POSITION_COUNT) {
        processGameForFens(currentGame.toString(), uniqueFens, movesPrintWriter);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static void processGameForFens(String pgn, Set<String> uniqueFens, PrintWriter movesPrintWriter) throws Exception {
    try {
      List<String> moves = extractMoves(pgn);
      if (moves.size() < MIN_PLIES) return;
      Board board = new Board();
      String lastMoveSan = "";
      for (int ply = 0; ply < moves.size(); ply++) {
        Move move = findMoveFromSAN(board, moves.get(ply));
        if (move == null) return;
        board.playMove(move);
        int currentPly = ply + 1;
        if (currentPly >= MIN_PLIES && currentPly % POSITION_INTERVAL == 0) {
          if (uniqueFens.size() >= POSITION_COUNT) return;
          if (!board.getLegalMoves(board.isWhiteToMove()).isEmpty()) {
            uniqueFens.add(board.toFEN());
            if (movesPrintWriter != null) {
              movesPrintWriter.write(board.toFEN() + "|" + lastMoveSan);
              movesPrintWriter.println();
            }
          }
        }
        lastMoveSan = moves.get(ply);
      }
    } catch (Exception e) {
      // ignore bad games
    }
  }

  private static List<String> extractMoves(String pgn) {
    pgn =
        pgn.replaceAll("\\{[^}]*\\}", " ")
            .replaceAll("\\([^)]*\\)", " ")
            .replaceAll("\\$\\d+", " ");
    pgn = pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");
    pgn = pgn.replace("\r", " ").replace("\n", " ");
    String[] tokens = pgn.trim().split("\\s+");
    List<String> moves = new ArrayList<>();
    for (String token : tokens) {
      if (token.isEmpty()
          || token.equals("1-0")
          || token.equals("0-1")
          || token.equals("1/2-1/2")
          || token.equals("*")
          || token.matches("\\d+\\.{1,3}")) continue;
      token = token.replaceFirst("^\\d+\\.{1,3}", "");
      if (!token.isEmpty()) moves.add(token);
    }
    return moves;
  }

  private static Move findMoveFromSAN(Board board, String san) {
    String clean = san.replace("+", "").replace("#", "").replace("!", "").replace("?", "");
    boolean white = board.isWhiteToMove();
    List<Move> legalMoves = board.getLegalMoves(white);
    if (clean.equals("O-O") || clean.equals("0-0")) {
      for (Move move : legalMoves)
        if (move.isCastling() && move.getEnd().getColumn() == 6) return move;
      return null;
    }
    if (clean.equals("O-O-O") || clean.equals("0-0-0")) {
      for (Move move : legalMoves)
        if (move.isCastling() && move.getEnd().getColumn() == 2) return move;
      return null;
    }
    char promotion = '\0';
    int promotionIndex = clean.indexOf('=');
    if (promotionIndex >= 0) {
      if (promotionIndex + 1 < clean.length()) promotion = clean.charAt(promotionIndex + 1);
      clean = clean.substring(0, promotionIndex);
    }
    if (clean.length() < 2) return null;
    String destination = clean.substring(clean.length() - 2);
    char file = destination.charAt(0);
    char rank = destination.charAt(1);
    if (file < 'a' || file > 'h' || rank < '1' || rank > '8') return null;
    int targetColumn = file - 'a';
    int targetRow = 8 - (rank - '0');
    String prefix = clean.substring(0, clean.length() - 2);
    char pieceLetter = prefix.isEmpty() ? 'P' : prefix.charAt(0);
    boolean pawn = pieceLetter == 'P' || "abcdefgh".indexOf(pieceLetter) >= 0;
    if (pawn) {
      for (Move move : legalMoves) {
        if (move.getEnd().getRow() != targetRow || move.getEnd().getColumn() != targetColumn)
          continue;
        chess.pieces.Piece piece = board.getPiece(move.getStart());
        if (!(piece instanceof chess.pieces.Pawn)) continue;
        if (move.isPromotion()) {
          if (promotion == '\0') continue;
          chess.pieces.Piece promoted = move.getPromotionPiece();
          if (promotion == 'Q' && promoted instanceof chess.pieces.Queen) return move;
          if (promotion == 'R' && promoted instanceof chess.pieces.Rook) return move;
          if (promotion == 'B' && promoted instanceof chess.pieces.Bishop) return move;
          if (promotion == 'N' && promoted instanceof chess.pieces.Knight) return move;
          continue;
        }
        if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) == 'x') return move;
        if (!prefix.contains("x")) return move;
      }
      return null;
    }
    Class<?> pieceClass =
        switch (pieceLetter) {
          case 'N' -> chess.pieces.Knight.class;
          case 'B' -> chess.pieces.Bishop.class;
          case 'R' -> chess.pieces.Rook.class;
          case 'Q' -> chess.pieces.Queen.class;
          case 'K' -> chess.pieces.King.class;
          default -> null;
        };
    if (pieceClass == null) return null;
    String disambiguation = prefix.substring(1);
    boolean capture = disambiguation.contains("x");
    disambiguation = disambiguation.replace("x", "");
    Move found = null;
    for (Move move : legalMoves) {
      if (move.getEnd().getRow() != targetRow || move.getEnd().getColumn() != targetColumn)
        continue;
      chess.pieces.Piece piece = board.getPiece(move.getStart());
      if (piece == null || piece.getClass() != pieceClass) continue;
      boolean actualCapture = move.isEnPassant() || board.getPiece(move.getEnd()) != null;
      if (capture != actualCapture) continue;
      if (disambiguation.length() == 1
          && disambiguation.charAt(0) >= 'a'
          && disambiguation.charAt(0) <= 'h'
          && move.getStart().getColumn() != disambiguation.charAt(0) - 'a') continue;
      if (disambiguation.length() == 1
          && Character.isDigit(disambiguation.charAt(0))
          && 8 - move.getStart().getRow() != disambiguation.charAt(0) - '0') continue;
      if (disambiguation.length() == 2
          && (move.getStart().getColumn() != disambiguation.charAt(0) - 'a'
              || 8 - move.getStart().getRow() != disambiguation.charAt(1) - '0')) continue;
      if (found != null) return null;
      found = move;
    }
    return found;
  }
}