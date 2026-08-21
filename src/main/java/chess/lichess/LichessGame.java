package chess.lichess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.opening.OpeningManager;
import chess.engine.search.SearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class LichessGame {

  private final String token;
  private final String gameId;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final SearchEngine engine;
  private final LichessClient lichessClient;
  private final OpeningManager openingManager;
  private Board board;
  private boolean botIsWhite;
  private boolean gameStarted = false;

  // Adaptive time management parameters
  private static final int MIN_SEARCH_DEPTH = 8;
  private static final int MAX_SEARCH_DEPTH = 64;
  private long wtimeMs;
  private long btimeMs;
  private long wincMs;
  private long bincMs;

  public LichessGame(String token, String gameId) {
    this.token = token;
    this.gameId = gameId;
    this.httpClient = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
    this.engine = new SearchEngine();
    this.lichessClient = new LichessClient(token);
    this.openingManager = new OpeningManager(engine);
    this.board = new Board();
  }

  public void stream() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://lichess.org/api/bot/game/stream/" + gameId))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    if (response.statusCode() != 200) {
      throw new IOException("Game stream failed. HTTP " + response.statusCode());
    }

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode event = mapper.readTree(line);
        handleEvent(event);
      }
    }
  }

  private void handleEvent(JsonNode event) {
    String type = event.has("type") ? event.get("type").asText() : "";
    System.out.println("\nGAME EVENT: " + type);

    switch (type) {
      case "gameFull" -> handleGameFull(event);
      case "gameState" -> handleGameState(event);
      case "chatLine" -> System.out.println("Chat: " + event);
      case "opponentGone" -> System.out.println("Opponent gone: " + event.get("gone").asBoolean());
      default -> System.out.println(event.toPrettyString());
    }
  }

  private void handleGameFull(JsonNode event) {
    gameStarted = true;
    JsonNode white = event.get("white");
    JsonNode black = event.get("black");
    String whiteId = white.has("id") ? white.get("id").asText() : "";
    String myId = getMyBotId();
    botIsWhite = whiteId.equalsIgnoreCase(myId);

    System.out.println("Bot color: " + (botIsWhite ? "White" : "Black"));

    JsonNode state = event.get("state");
    updateClock(state);

    String initialFen = event.get("initialFen").asText();
    System.out.println("Initial FEN: " + initialFen);

    board = new Board();
    if (!initialFen.equals("startpos")) {
      board.loadFEN(initialFen);
    }

    String moves = (state != null && state.has("moves")) ? state.get("moves").asText() : "";
    if (!moves.isBlank()) {
      rebuildBoardFromMoves(moves);
    }

    System.out.println("\nGAME STARTED!");
    System.out.println("Game ID: " + gameId);
    System.out.println("Opening: " + openingManager.getOpeningName());
    board.printBoard(botIsWhite);

    if (board.isWhiteToMove() == botIsWhite) {
      makeEngineMove();
    }
  }

  private void handleGameState(JsonNode event) {
    String moves = event.has("moves") ? event.get("moves").asText() : "";
    String status = event.has("status") ? event.get("status").asText() : "";
    updateClock(event);

    System.out.println("Moves: " + moves);
    System.out.println("Status: " + status);

    if (!status.equals("started")) {
      System.out.println("Game finished: " + status);
      return;
    }

    rebuildBoardFromMoves(moves);
    System.out.println();
    board.printBoard(botIsWhite);
    System.out.println("Opening: " + openingManager.getOpeningName());

    if (board.isWhiteToMove() == botIsWhite) {
      makeEngineMove();
    } else {
      System.out.println("Waiting for opponent...");
    }
  }

  private void updateClock(JsonNode node) {
    if (node != null && node.has("wtime")) {
      wtimeMs = node.get("wtime").asLong();
      btimeMs = node.get("btime").asLong();
      wincMs = node.get("winc").asLong();
      bincMs = node.get("binc").asLong();
    }
  }

  private long calculateSearchTime() {
    long myTime = botIsWhite ? wtimeMs : btimeMs;
    long myInc = botIsWhite ? wincMs : bincMs;

    if (myTime <= 0) return 100;  // Safety default if no time remaining

    // Spend 1/40 of remaining time, with increment factor
    long targetTime = myTime / 40 + (myInc * 3 / 4);

    // Reasonable caps: minimum 100ms, maximum 5 seconds for normal play
    // For very long time controls (10+ min), allow up to 30 seconds
    long maxAllowed = myTime > 600000 ? 30000L : 5000L;

    return Math.max(100, Math.min(targetTime, maxAllowed));
  }

  private void rebuildBoardFromMoves(String moves) {
    board = new Board();
    openingManager.reset();

    if (moves == null || moves.isBlank()) return;

    String[] moveList = moves.trim().split("\\s+");
    for (String uci : moveList) {
      Move move = findMoveFromUci(board, uci);
      if (move == null) {
        throw new IllegalStateException(
            "Could not find legal move for UCI: " + uci + "\nBoard FEN: " + board.toFEN());
      }
      String san = board.formatMove(move);
      openingManager.recordMove(san);
      board.playMove(move);
    }
  }

  private void playUciMove(Board board, String uci) {
    if (uci.length() < 4) throw new IllegalArgumentException("Invalid UCI move: " + uci);
    String from = uci.substring(0, 2);
    String to = uci.substring(2, 4);
    char promotion = uci.length() >= 5 ? Character.toUpperCase(uci.charAt(4)) : 'Q';

    Position start = algebraicToPosition(from);
    Position end = algebraicToPosition(to);
    Move move = board.findLegalMove(start, end, promotion);

    if (move == null)
      throw new IllegalStateException(
          "Could not find legal move for UCI: " + uci + "\nBoard FEN: " + board.toFEN());
    board.playMove(move);
  }

  private Position algebraicToPosition(String square) {
    int column = square.charAt(0) - 'a';
    int row = 8 - Character.getNumericValue(square.charAt(1));
    return new Position(row, column);
  }

  private void makeEngineMove() {
    System.out.println("\n================================");
    System.out.println("ENGINE THINKING...");
    System.out.println("================================");
    System.out.println("Position: " + board.toFEN());

    if (board.isWhiteToMove() != botIsWhite) {
      System.out.println("ERROR: It is not the engine's turn.");
      return;
    }

    if (openingManager.isOpeningActive()) {
      Move bookMove = openingManager.getOpeningMove(board);
      if (bookMove != null) {
        System.out.println("Opening book move: " + board.formatMove(bookMove));
        sendMoveToLichess(bookMove);
        return;
      }
      System.out.println("Opening book ended.");
    }

    System.out.println("Using normal engine search.");
    long searchTimeMs = calculateSearchTime();
    System.out.println("Allocated search time: " + searchTimeMs + "ms");

    SearchEngine.SearchResult result = engine.findBestMove(board, MAX_SEARCH_DEPTH, searchTimeMs);
    Move bestMove = result.bestMove();

    if (bestMove == null) {
      System.out.println("Engine found no legal move.");
      return;
    }

    System.out.printf("Engine search depth: %d%n", result.depth());
    System.out.printf("Nodes: %,d%n", result.nodes());
    System.out.printf("Score: %+.2f%n", result.score() / 100.0);

    sendMoveToLichess(bestMove);
  }

  private void sendMoveToLichess(Move move) {
    String san = board.formatMove(move);
    String uci = moveToUci(move);

    System.out.println("Engine move: " + san);
    System.out.println("UCI move: " + uci);

    try {
      lichessClient.makeMove(gameId, uci);
    } catch (Exception e) {
      System.err.println("Failed to send engine move:");
      e.printStackTrace();
    }
  }

  private Move findEquivalentMove(Board board, Move original) {
    for (Move move : board.getLegalMoves(board.isWhiteToMove())) {
      if (!move.getStart().equals(original.getStart())) continue;
      if (!move.getEnd().equals(original.getEnd())) continue;
      if (move.isPromotion() != original.isPromotion()) continue;
      if (move.isPromotion()
          && move.getPromotionPiece().getNotationSymbol()
              != original.getPromotionPiece().getNotationSymbol()) continue;
      return move;
    }
    throw new IllegalStateException("Could not recreate opening move.");
  }

  private String moveToUci(Move move) {
    String uci = move.getStart().toAlgebraic() + move.getEnd().toAlgebraic();
    if (move.isPromotion()) {
      char promotionSymbol = move.getPromotionPiece().getNotationSymbol();
      uci += Character.toLowerCase(promotionSymbol);
    }
    return uci;
  }

  private String getMyBotId() {
    return "bugabot";
  }

  public void stream(Consumer<JsonNode> eventConsumer) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://lichess.org/api/bot/game/stream/" + gameId))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    if (response.statusCode() != 200) {
      throw new IOException("Game stream failed. HTTP " + response.statusCode());
    }

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode event = mapper.readTree(line);
        eventConsumer.accept(event);
      }
    }
  }

  private Move findMoveFromUci(Board board, String uci) {
    if (uci == null || uci.length() < 4) return null;
    String from = uci.substring(0, 2);
    String to = uci.substring(2, 4);
    char promotion = uci.length() >= 5 ? Character.toUpperCase(uci.charAt(4)) : 'Q';
    return board.findLegalMove(algebraicToPosition(from), algebraicToPosition(to), promotion);
  }
}
