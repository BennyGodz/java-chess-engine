package chess.lichess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.search.SearchEngine;
import chess.pieces.Piece;

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

    private Board board;

    private boolean botIsWhite;

    private boolean gameStarted = false;

    /*
     * Adjust these later once we test engine strength.
     */
    private static final int SEARCH_DEPTH = 3;
    private static final long SEARCH_TIME_MS = 100;

    public LichessGame(
            String token,
            String gameId
    ) {
        this.token = token;
        this.gameId = gameId;

        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();

        this.engine = new SearchEngine();
        this.lichessClient = new LichessClient(token);

        this.board = new Board();
    }

    /**
     * Connect to the Lichess game event stream.
     */
    public void stream() throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://lichess.org/api/bot/game/stream/"
                                + gameId
                ))
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Game stream failed. HTTP "
                            + response.statusCode()
            );
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     response.body(),
                                     StandardCharsets.UTF_8
                             ))) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isBlank()) {
                    continue;
                }

                JsonNode event = mapper.readTree(line);

                handleEvent(event);
            }
        }
    }

    /**
     * Handle every event received from the game stream.
     */
    private void handleEvent(JsonNode event) {

        String type = event.has("type")
                ? event.get("type").asText()
                : "";

        System.out.println();
        System.out.println("GAME EVENT: " + type);

        switch (type) {

            case "gameFull":
                handleGameFull(event);
                break;

            case "gameState":
                handleGameState(event);
                break;

            case "chatLine":
                System.out.println(
                        "Chat: " + event
                );
                break;

            case "opponentGone":
                System.out.println(
                        "Opponent gone: "
                                + event.get("gone").asBoolean()
                );
                break;

            default:
                System.out.println(
                        event.toPrettyString()
                );
        }
    }

    /**
     * First event received when joining the game.
     */
    private void handleGameFull(JsonNode event) {

        gameStarted = true;

        JsonNode white = event.get("white");
        JsonNode black = event.get("black");

        String whiteId = white.has("id")
                ? white.get("id").asText()
                : "";

        String blackId = black.has("id")
                ? black.get("id").asText()
                : "";

        String myId = getMyBotId();

        botIsWhite = whiteId.equalsIgnoreCase(myId);

        System.out.println(
                "Bot color: "
                        + (botIsWhite ? "White" : "Black")
        );

        String initialFen =
                event.get("initialFen").asText();

        System.out.println(
                "Initial FEN: " + initialFen
        );

        /*
         * Load starting position.
         */
        if (initialFen.equals("startpos")) {
            board = new Board();
        } else {
            board = new Board();
            board.loadFEN(initialFen);
        }

        /*
         * gameFull contains a state object with the moves
         * that have already happened.
         */
        JsonNode state = event.get("state");

        if (state != null && state.has("moves")) {

            String moves = state.get("moves").asText();

            if (!moves.isBlank()) {
                rebuildBoardFromMoves(moves);
            }
        }

        System.out.println();
        System.out.println("GAME STARTED!");
        System.out.println(
                "Game ID: " + gameId
        );

        board.printBoard(botIsWhite);

        /*
         * If BugaBot is White, it may need to make
         * the first move immediately.
         */
        if (board.isWhiteToMove() == botIsWhite) {
            makeEngineMove();
        }
    }

    /**
     * Handle subsequent gameState events.
     */
    private void handleGameState(JsonNode event) {

        String moves = event.has("moves")
                ? event.get("moves").asText()
                : "";

        String status = event.has("status")
                ? event.get("status").asText()
                : "";

        System.out.println(
                "Moves: " + moves
        );

        System.out.println(
                "Status: " + status
        );

        /*
         * These indicate the game has ended.
         */
        if (!status.equals("started")) {

            System.out.println(
                    "Game finished: " + status
            );

            return;
        }

        /*
         * Rebuild our Board from the complete move list.
         */
        rebuildBoardFromMoves(moves);

        System.out.println();
        board.printBoard(botIsWhite);

        /*
         * Check whether it is our turn.
         */
        if (board.isWhiteToMove() == botIsWhite) {
            makeEngineMove();
        } else {
            System.out.println(
                    "Waiting for opponent..."
            );
        }
    }

    /**
     * Reconstruct the current chess position from
     * Lichess's UCI move list.
     */
    private void rebuildBoardFromMoves(String moves) {

        board = new Board();

        if (moves == null || moves.isBlank()) {
            return;
        }

        String[] moveList =
                moves.trim().split("\\s+");

        for (String uci : moveList) {
            playUciMove(board, uci);
        }
    }

    /**
     * Apply one UCI move to our Board.
     *
     * Example:
     *
     * e2e4
     * e7e5
     * g1f3
     * e7e8q
     */
    private void playUciMove(
            Board board,
            String uci
    ) {

        if (uci.length() < 4) {
            throw new IllegalArgumentException(
                    "Invalid UCI move: " + uci
            );
        }

        String from = uci.substring(0, 2);
        String to = uci.substring(2, 4);

        char promotion = 'Q';

        if (uci.length() >= 5) {
            promotion =
                    Character.toUpperCase(
                            uci.charAt(4)
                    );
        }

        Position start =
                algebraicToPosition(from);

        Position end =
                algebraicToPosition(to);

        Move move =
                board.findLegalMove(
                        start,
                        end,
                        promotion
                );

        if (move == null) {
            throw new IllegalStateException(
                    "Could not find legal move for UCI: "
                            + uci
                            + "\nBoard FEN: "
                            + board.toFEN()
            );
        }

        board.playMove(move);
    }

    /**
     * Convert "e2" into your Board coordinates.
     */
    private Position algebraicToPosition(
            String square
    ) {

        int column =
                square.charAt(0) - 'a';

        int row =
                8 - Character.getNumericValue(
                        square.charAt(1)
                );

        return new Position(row, column);
    }

    /**
     * Ask the actual chess engine for a move.
     */
    private void makeEngineMove() {

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
                "Position: " + board.toFEN()
        );

        boolean engineWhite =
                board.isWhiteToMove();

        if (engineWhite != botIsWhite) {
            System.out.println(
                    "ERROR: It is not the engine's turn."
            );
            return;
        }

        SearchEngine.SearchResult result =
                engine.findBestMove(
                        board,
                        SEARCH_DEPTH,
                        SEARCH_TIME_MS
                );

        Move bestMove = result.bestMove();

        if (bestMove == null) {
            System.out.println(
                    "Engine found no legal move."
            );
            return;
        }

        String san =
                board.formatMove(bestMove);

        String uci =
                moveToUci(bestMove);

        System.out.println(
                "Engine move: " + san
        );

        System.out.println(
                "UCI move: " + uci
        );

        System.out.println(
                "Depth: " + result.depth()
        );

        System.out.println(
                "Nodes: " + result.nodes()
        );

        System.out.println(
                "Score: " + result.score()
        );

        /*
         * Send the move to Lichess.
         */
        try {

            lichessClient.makeMove(
                    gameId,
                    uci
            );

            /*
             * Do NOT call board.playMove() here.
             *
             * Lichess will send a new gameState event
             * containing the move. That event will rebuild
             * the Board.
             */

        } catch (Exception e) {

            System.err.println(
                    "Failed to send engine move:"
            );

            e.printStackTrace();
        }
    }

    /**
     * Convert your Move object into Lichess UCI notation.
     *
     * e2 -> e4 becomes:
     *
     * e2e4
     *
     * Promotion:
     *
     * e7 -> e8 = queen
     *
     * becomes:
     *
     * e7e8q
     */
    private String moveToUci(Move move) {

        String from =
                move.getStart().toAlgebraic();

        String to =
                move.getEnd().toAlgebraic();

        String uci =
                from + to;

        if (move.isPromotion()) {

            Piece promotionPiece =
                    move.getPromotionPiece();

            char promotion;

            if (promotionPiece.getNotationSymbol() == 'Q') {
                promotion = 'q';
            } else if (promotionPiece.getNotationSymbol() == 'R') {
                promotion = 'r';
            } else if (promotionPiece.getNotationSymbol() == 'B') {
                promotion = 'b';
            } else if (promotionPiece.getNotationSymbol() == 'N') {
                promotion = 'n';
            } else {
                throw new IllegalStateException(
                        "Unknown promotion piece."
                );
            }

            uci += promotion;
        }

        return uci;
    }

    /**
     * Get the bot's own Lichess username.
     *
     * This assumes the account is BugaBot.
     */
    private String getMyBotId() {
        return "bugabot";
    }

    /**
     * Optional callback version if your existing code uses
     * LichessGame.stream(Consumer<JsonNode>).
     */
    public void stream(
            Consumer<JsonNode> eventConsumer
    ) throws IOException, InterruptedException {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                "https://lichess.org/api/bot/game/stream/"
                                        + gameId
                        ))
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .GET()
                        .build();

        HttpResponse<InputStream> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Game stream failed. HTTP "
                            + response.statusCode()
            );
        }

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     response.body(),
                                     StandardCharsets.UTF_8
                             ))) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isBlank()) {
                    continue;
                }

                JsonNode event =
                        mapper.readTree(line);

                eventConsumer.accept(event);
            }
        }
    }
}