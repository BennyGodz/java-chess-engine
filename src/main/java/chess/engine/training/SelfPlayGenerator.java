package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import chess.engine.opening.OpeningBook;
import chess.engine.search.SearchEngine;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Generates annotated self-play games for {@link GameTrainer}. */
public final class SelfPlayGenerator {

  static final String GAMES_DIR = "games" + File.separator + "selfplay";

  static final int DEFAULT_GAMES = 256;
  static final long DEFAULT_TIME_PER_MOVE_MS = 200;

  private static final int SEARCH_DEPTH = 7;
  private static final int MAX_PLIES = 240;
  private static final int OPENING_PLIES = 10;

  private static final int EVAL_COMMENT_CLAMP_CP = 900;
  private static final int ADJUDICATION_PLY = 140;
  private static final int ADJUDICATION_THRESHOLD_CP = 400;

  private final int targetGames;
  private final long timePerMoveMs;
  private final int threads;
  private final long seed;

  public SelfPlayGenerator(int targetGames, long timePerMoveMs, long seed) {
    this(
        targetGames,
        timePerMoveMs,
        seed,
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
  }

  public SelfPlayGenerator(int targetGames, long timePerMoveMs, long seed, int threads) {
    this.targetGames = Math.max(1, targetGames);
    this.timePerMoveMs = Math.max(20, timePerMoveMs);
    this.seed = seed;
    this.threads = Math.max(1, threads);
  }

  public static void main(String[] args) {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_GAMES;
    long timeMs = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_TIME_PER_MOVE_MS;
    int threads =
        args.length > 2
            ? Integer.parseInt(args[2])
            : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    SelfPlayGenerator generator = new SelfPlayGenerator(games, timeMs, System.nanoTime(), threads);
    try {
      List<File> written = generator.generate();
      System.out.println("Wrote " + written.size() + " game file(s) to " + GAMES_DIR);
    } catch (IOException e) {
      System.err.println("Self-play generation failed: " + e.getMessage());
      System.exit(1);
    }
  }

  public List<File> generate() throws IOException {
    File dir = new File(GAMES_DIR);
    Files.createDirectories(dir.toPath());

    String fileName =
        String.format(
            java.util.Locale.US,
            "selfplay_%s_%d.pgn",
            LocalDate.now(),
            System.currentTimeMillis() % 1_000_000);
    File outputFile = new File(dir, fileName);

    System.out.printf(
        "Generating %d self-play games on %d thread(s) (%d ms per move)...%n",
        targetGames, threads, timePerMoveMs);
    long startNanos = System.nanoTime();

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    AtomicInteger completed = new AtomicInteger();
    AtomicInteger wins = new AtomicInteger();
    AtomicInteger draws = new AtomicInteger();
    AtomicInteger losses = new AtomicInteger();

    OpeningBook openingBook = new OpeningBook();
    List<List<String>> lines = openingBook.getOpenings();

    List<Future<String>> futures = new ArrayList<>();
    for (int gameIndex = 0; gameIndex < targetGames; gameIndex++) {
      Random gameRandom = new Random(seed + gameIndex * 7919L);
      List<String> openingLine = lines.get(gameRandom.nextInt(lines.size()));

      futures.add(
          executor.submit(
              () -> {
                String pgn = playAndRecord(openingLine);
                int done = completed.incrementAndGet();
                synchronized (System.out) {
                  System.out.printf("Game %d/%d finished.%n", done, targetGames);
                }
                return pgn;
              }));
    }

    try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outputFile.toPath()))) {
      for (Future<String> future : futures) {
        try {
          String pgn = future.get();
          out.print(pgn);
          switch (resultOf(pgn)) {
            case "1-0" -> wins.incrementAndGet();
            case "0-1" -> losses.incrementAndGet();
            default -> draws.incrementAndGet();
          }
        } catch (Exception e) {
          System.err.println("A self-play game failed: " + e.getMessage());
        }
      }
    } finally {
      executor.shutdown();
    }

    long elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000;
    System.out.printf(
        "Self-play done in %ds. White wins %d | draws %d | Black wins %d%n",
        elapsedSec, wins.get(), draws.get(), losses.get());
    return List.of(outputFile);
  }

  private String playAndRecord(List<String> openingLine) {
    SearchEngine engine = new SearchEngine();
    Board board = new Board();
    List<String> sanMoves = new ArrayList<>();
    List<Double> moveScores = new ArrayList<>();
    int openingPlies = Math.min(openingLine.size(), OPENING_PLIES);

    String result;
    String termination;

    while (true) {
      boolean whiteToMove = board.isWhiteToMove();

      if (board.isCheckmate(whiteToMove)) {
        result = whiteToMove ? "0-1" : "1-0";
        termination = "checkmate";
        break;
      }
      if (board.isStalemate(whiteToMove) || board.isAutomaticDraw()) {
        result = "1/2-1/2";
        termination = "automatic draw";
        break;
      }

      int ply = sanMoves.size();
      if (ply >= ADJUDICATION_PLY) {
        int materialDiff = materialCount(board, true) - materialCount(board, false);
        if (Math.abs(materialDiff) >= ADJUDICATION_THRESHOLD_CP) {
          result = materialDiff > 0 ? "1-0" : "0-1";
          termination = "material adjudication";
          break;
        }
      }
      if (ply >= MAX_PLIES) {
        result = "1/2-1/2";
        termination = "ply limit";
        break;
      }

      SelectedMove selected = selectMove(board, engine, ply, openingLine, openingPlies);
      if (selected == null || selected.move() == null) {
        result = "1/2-1/2";
        termination = "no legal move";
        break;
      }

      moveScores.add(selected.rootScoreCp());
      sanMoves.add(board.formatMove(selected.move()));
      board.playMove(selected.move());
    }

    return toPgn(sanMoves, moveScores, result, termination);
  }

  private record SelectedMove(Move move, Double rootScoreCp) {}

  private SelectedMove selectMove(
      Board board, SearchEngine engine, int ply, List<String> openingLine, int openingPlies) {

    if (ply < openingPlies) {
      Move bookMove = SanMoveParser.parse(board, openingLine.get(ply));
      if (bookMove != null) return new SelectedMove(bookMove, null);
    }

    SearchEngine.SearchResult searchResult =
        engine.findBestMove(board, SEARCH_DEPTH, timePerMoveMs);
    if (searchResult.bestMove() == null) return new SelectedMove(null, null);

    int score = Math.clamp(searchResult.score(), -EVAL_COMMENT_CLAMP_CP, EVAL_COMMENT_CLAMP_CP);
    return new SelectedMove(searchResult.bestMove(), (double) score);
  }

  private static int materialCount(Board board, boolean white) {
    int material = 0;
    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        chess.pieces.Piece piece = board.getPiece(new chess.board.Position(row, col));
        if (piece != null && piece.isWhite() == white && !(piece instanceof chess.pieces.King)) {
          material += piece.getValue();
        }
      }
    }
    return material;
  }

  private static String resultOf(String pgn) {
    int index = pgn.indexOf("[Result \"");
    if (index < 0) return "*";
    int start = index + "[Result \"".length();
    int end = pgn.indexOf('"', start);
    return end > start ? pgn.substring(start, end) : "*";
  }

  private static String toPgn(
      List<String> sanMoves, List<Double> moveScores, String result, String termination) {
    StringBuilder pgn = new StringBuilder();
    LocalDate now = LocalDate.now();
    String date =
        String.format(
            java.util.Locale.US,
            "%04d.%02d.%02d",
            now.getYear(),
            now.getMonthValue(),
            now.getDayOfMonth());

    pgn.append("[Event \"Java Chess Engine self-play\"]\n");
    pgn.append("[Site \"local\"]\n");
    pgn.append("[Date \"").append(date).append("\"]\n");
    pgn.append("[White \"Engine\"]\n");
    pgn.append("[Black \"Engine\"]\n");
    pgn.append("[Result \"").append(result).append("\"]\n");
    pgn.append("[Termination \"").append(termination).append("\"]\n\n");

    for (int i = 0; i < sanMoves.size(); i++) {
      if (i % 2 == 0) {
        pgn.append(i / 2 + 1).append(". ");
      }
      pgn.append(sanMoves.get(i));
      if (moveScores.get(i) != null) {
        pgn.append(" { ev ").append(moveScores.get(i).intValue()).append(" }");
      }
      pgn.append(' ');
    }
    pgn.append(result).append("\n\n");
    return pgn.toString();
  }
}
