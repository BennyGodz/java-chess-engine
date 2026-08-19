package chess.lichess;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.opening.OpeningManager;
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

    private final OpeningManager openingManager;

    private Board board;

    private boolean botIsWhite;

    private boolean gameStarted = false;

    /*
     * Main blitz search.
     */
    private static final int SEARCH_DEPTH = 9;
    private static final long SEARCH_TIME_MS = 1000;

    /*
     * Fast opening safety check.
     */
    private static final int OPENING_CHECK_DEPTH = 4;
    private static final long OPENING_CHECK_TIME_MS = 250;

    /*
     * Maximum amount of evaluation the opening
     * move is allowed to lose.
     *
     * 50 centipawns = 0.50 pawns.
     */
    private static final int MAX_OPENING_LOSS_CP = 40;

    public LichessGame(
            String token,
            String gameId
    ) {

        this.token = token;
        this.gameId = gameId;

        this.httpClient =
                HttpClient.newHttpClient();

        this.mapper =
                new ObjectMapper();

        this.engine =
                new SearchEngine();

        this.lichessClient =
                new LichessClient(token);

        this.openingManager =
                new OpeningManager();

        this.board =
                new Board();
    }

    /**
     * Connect to Lichess game stream.
     */
    public void stream()
            throws IOException, InterruptedException {

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

                handleEvent(event);
            }
        }
    }

    /**
     * Handle Lichess events.
     */
    private void handleEvent(
            JsonNode event
    ) {

        String type =
                event.has("type")
                        ? event.get("type").asText()
                        : "";

        System.out.println();
        System.out.println(
                "GAME EVENT: " + type
        );

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
                                + event.get("gone")
                                .asBoolean()
                );

                break;

            default:

                System.out.println(
                        event.toPrettyString()
                );
        }
    }

    /**
     * Initial game event.
     */
    private void handleGameFull(
            JsonNode event
    ) {

        gameStarted = true;

        JsonNode white =
                event.get("white");

        JsonNode black =
                event.get("black");

        String whiteId =
                white.has("id")
                        ? white.get("id").asText()
                        : "";

        String blackId =
                black.has("id")
                        ? black.get("id").asText()
                        : "";

        String myId =
                getMyBotId();

        botIsWhite =
                whiteId.equalsIgnoreCase(myId);

        System.out.println(
                "Bot color: "
                        + (botIsWhite
                        ? "White"
                        : "Black")
        );

        String initialFen =
                event.get("initialFen").asText();

        System.out.println(
                "Initial FEN: "
                        + initialFen
        );

        /*
         * Create the starting board.
         */
        board =
                new Board();

        if (!initialFen.equals("startpos")) {

            board.loadFEN(
                    initialFen
            );
        }

        JsonNode state =
                event.get("state");

        String moves = "";

        if (state != null
                && state.has("moves")) {

            moves =
                    state.get("moves").asText();

            if (!moves.isBlank()) {

                rebuildBoardFromMoves(
                        moves
                );
            }
        }

        System.out.println();
        System.out.println(
                "GAME STARTED!"
        );

        System.out.println(
                "Game ID: "
                        + gameId
        );

        System.out.println(
                "Opening: "
                        + openingManager
                        .getOpeningName()
        );

        board.printBoard(
                botIsWhite
        );

        /*
         * If it is our turn, make the move.
         *
         * This works for White and Black.
         */
        if (board.isWhiteToMove()
                == botIsWhite) {

            makeEngineMove();
        }
    }

    /**
     * Handle later game states.
     */
    private void handleGameState(
            JsonNode event
    ) {

        String moves =
                event.has("moves")
                        ? event.get("moves").asText()
                        : "";

        String status =
                event.has("status")
                        ? event.get("status").asText()
                        : "";

        System.out.println(
                "Moves: " + moves
        );

        System.out.println(
                "Status: " + status
        );

        if (!status.equals("started")) {

            System.out.println(
                    "Game finished: "
                            + status
            );

            return;
        }

        /*
         * Always reconstruct the board from
         * Lichess's complete move list.
         */
        rebuildBoardFromMoves(
                moves
        );

        System.out.println();

        board.printBoard(
                botIsWhite
        );

        System.out.println(
                "Opening: "
                        + openingManager
                        .getOpeningName()
        );

        /*
         * Only move when it is our turn.
         */
        if (board.isWhiteToMove()
                == botIsWhite) {

            makeEngineMove();

        } else {

            System.out.println(
                    "Waiting for opponent..."
            );
        }
    }

    /**
     * Rebuild board from Lichess UCI moves.
     */
    private void rebuildBoardFromMoves(
            String moves
    ) {

        board =
                new Board();

        if (moves == null
                || moves.isBlank()) {

            return;
        }

        String[] moveList =
                moves.trim()
                        .split("\\s+");

        for (String uci : moveList) {

            playUciMove(
                    board,
                    uci
            );
        }
    }

    /**
     * Apply a Lichess UCI move.
     */
    private void playUciMove(
            Board board,
            String uci
    ) {

        if (uci.length() < 4) {

            throw new IllegalArgumentException(
                    "Invalid UCI move: "
                            + uci
            );
        }

        String from =
                uci.substring(0, 2);

        String to =
                uci.substring(2, 4);

        char promotion = 'Q';

        if (uci.length() >= 5) {

            promotion =
                    Character.toUpperCase(
                            uci.charAt(4)
                    );
        }

        Position start =
                algebraicToPosition(
                        from
                );

        Position end =
                algebraicToPosition(
                        to
                );

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
     * Convert e2 into Board coordinates.
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

        return new Position(
                row,
                column
        );
    }

    /**
     * Choose and send an engine move.
     *
     * The opening book is treated as a suggestion,
     * NOT as an absolute command.
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
                "Position: "
                        + board.toFEN()
        );

        if (board.isWhiteToMove()
                != botIsWhite) {

            System.out.println(
                    "ERROR: It is not "
                            + "the engine's turn."
            );

            return;
        }

        /*
         * Ask opening manager for a candidate.
         */
        Move bookMove = null;

        if (openingManager.isOpeningActive()) {

            bookMove =
                    openingManager.getOpeningMove(
                            board
                    );
        }

        /*
         * If there is no book move,
         * just search normally.
         */
        if (bookMove == null) {

            playNormalEngineMove();

            return;
        }

        System.out.println(
                "Book candidate: "
                        + board.formatMove(
                        bookMove
                )
        );

        /*
         * Search current position.
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

            sendMoveToLichess(
                    bookMove
            );

            openingManager.advance();

            return;
        }

        /*
         * Copy the board.
         */
        Board bookBoard =
                new Board();

        bookBoard.loadFEN(
                board.toFEN()
        );

        /*
         * Find equivalent book move on the copy.
         */
        Move copiedBookMove =
                findEquivalentMove(
                        bookBoard,
                        bookMove
                );

        /*
         * Play the book move only on the
         * temporary board.
         */
        bookBoard.playMove(
                copiedBookMove
        );

        /*
         * Search after the book move.
         *
         * This score is from the opponent's
         * perspective.
         */
        SearchEngine.SearchResult bookResult =
                engine.findBestMove(
                        bookBoard,
                        OPENING_CHECK_DEPTH,
                        OPENING_CHECK_TIME_MS
                );

        /*
         * Convert back to our perspective.
         */
        int normalScore =
                normalResult.score();

        int bookScore =
                -bookResult.score();

        int difference =
                normalScore - bookScore;

        System.out.printf(
                "Normal: %+.2f%n",
                normalScore / 100.0
        );

        System.out.printf(
                "Book:   %+.2f%n",
                bookScore / 100.0
        );

        System.out.printf(
                "Loss:   %.2f%n",
                difference / 100.0
        );

        /*
         * Safe enough.
         */
        if (difference
                <= MAX_OPENING_LOSS_CP) {

            System.out.println(
                    "BOOK MOVE ACCEPTED"
            );

            sendMoveToLichess(
                    bookMove
            );

            openingManager.advance();

            return;
        }

        /*
         * Book move is dangerous.
         */
        System.out.println();
        System.out.println(
                "BOOK MOVE REJECTED"
        );

        System.out.println(
                "Opponent's position made "
                        + "the opening move unsafe."
        );

        openingManager.disable();

        /*
         * Use the best move found by the
         * safety search immediately.
         */
        sendMoveToLichess(
                normalBestMove
        );
    }

    /**
     * Normal full engine search.
     */
    private void playNormalEngineMove() {

        System.out.println(
                "Using normal engine search."
        );

        SearchEngine.SearchResult result =
                engine.findBestMove(
                        board,
                        SEARCH_DEPTH,
                        SEARCH_TIME_MS
                );

        Move bestMove =
                result.bestMove();

        if (bestMove == null) {

            System.out.println(
                    "Engine found no legal move."
            );

            return;
        }

        System.out.printf(
                "Engine search depth: %d%n",
                result.depth()
        );

        System.out.printf(
                "Nodes: %,d%n",
                result.nodes()
        );

        System.out.printf(
                "Score: %+.2f%n",
                result.score() / 100.0
        );

        sendMoveToLichess(
                bestMove
        );
    }

    /**
     * Send a move to Lichess.
     */
    private void sendMoveToLichess(
            Move move
    ) {

        String san =
                board.formatMove(move);

        String uci =
                moveToUci(move);

        System.out.println(
                "Engine move: "
                        + san
        );

        System.out.println(
                "UCI move: "
                        + uci
        );

        try {

            lichessClient.makeMove(
                    gameId,
                    uci
            );

        } catch (Exception e) {

            System.err.println(
                    "Failed to send engine move:"
            );

            e.printStackTrace();
        }
    }

    /**
     * Find equivalent move on another board.
     */
    private Move findEquivalentMove(
            Board board,
            Move original
    ) {

        for (Move move :
                board.getLegalMoves(
                        board.isWhiteToMove()
                )) {

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
     * Convert Move to UCI.
     */
    private String moveToUci(
            Move move
    ) {

        String from =
                move.getStart()
                        .toAlgebraic();

        String to =
                move.getEnd()
                        .toAlgebraic();

        String uci =
                from + to;

        if (move.isPromotion()) {

            Piece promotionPiece =
                    move.getPromotionPiece();

            char promotion;

            if (promotionPiece
                    .getNotationSymbol() == 'Q') {

                promotion = 'q';

            } else if (promotionPiece
                    .getNotationSymbol() == 'R') {

                promotion = 'r';

            } else if (promotionPiece
                    .getNotationSymbol() == 'B') {

                promotion = 'b';

            } else if (promotionPiece
                    .getNotationSymbol() == 'N') {

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
     * Bot username.
     */
    private String getMyBotId() {
        return "bugabot";
    }

    /**
     * Callback stream.
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