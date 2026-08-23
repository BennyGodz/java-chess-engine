package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchEngineTest {

    @Test
    void completesSafetyPassAndSavesQueenInBugaBotGamePosition() {
        Board board = board("3Q1K2/p4P2/2p5/8/2k1q3/8/8/8 w - - 0 68");
        play(board, "d8e7 e4g4 f8e8 g4c8 e7d8 c8e6 e8f8 e6e4 d8e7");

        assertEquals(2, board.getCurrentPositionRepetitionCount(),
                "This is the second occurrence of the exact position from the game");

        SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 6, 1);

        assertNotNull(result.bestMove());
        assertTrue(result.depth() >= 1);
        assertEquals(square("e4"), result.bestMove().getStart(),
                "The attacked queen must move instead of returning an arbitrary pawn/king move");
    }

    @Test
    void savesDirectlyHangingRookEvenWhenNominalTimeIsOneMillisecond() {
        Board board = board("7k/p7/8/4r3/8/8/4Q3/7K b - - 0 1");

        SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 6, 1);

        assertNotNull(result.bestMove());
        assertEquals(square("e5"), result.bestMove().getStart(),
                "The mandatory tactical pass must not leave the rook on e5 for Qxe5");
    }

    @Test
    void findsImmediateCheckmate() {
        Board board = board("7k/8/5KQ1/8/8/8/8/8 w - - 0 1");

        SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 4, 100);
        Move move = result.bestMove();

        assertNotNull(move);
        assertEquals(square("g6"), move.getStart());
        assertEquals(square("g7"), move.getEnd());
        assertTrue(result.score() >= SearchEngine.MATE_SCORE - 2);
    }

    @Test
    void tradesRookForNewQueenInForcedPromotionSequenceFromGame() {
        Board board = board(
                "2r4Q/p5Q1/2pkqP2/8/8/6P1/5P2/5K2 b - - 0 37"
        );

        SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 8, 300);

        assertNotNull(result.bestMove());
        assertEquals(square("c8"), result.bestMove().getStart());
        assertEquals(square("h8"), result.bestMove().getEnd(),
                "The rook must remove the promoted queen before White keeps two queens");
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.loadFEN(fen);
        return board;
    }

    private static Position square(String text) {
        return new Position(8 - (text.charAt(1) - '0'), text.charAt(0) - 'a');
    }

    private static void play(Board board, String uciMoves) {
        for (String uci : uciMoves.split("\\s+")) {
            char promotion = uci.length() == 5
                    ? Character.toUpperCase(uci.charAt(4))
                    : 'Q';
            Move move = board.findLegalMove(
                    square(uci.substring(0, 2)),
                    square(uci.substring(2, 4)),
                    promotion
            );
            assertNotNull(move, "Illegal test move: " + uci);
            board.playMove(move);
        }
    }
}
