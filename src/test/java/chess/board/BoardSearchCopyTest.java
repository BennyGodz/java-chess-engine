package chess.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BoardSearchCopyTest {

    @Test
    void lightweightSearchCopiesPreserveLegalMoveCounts() {
        Board board = new Board();

        assertEquals(20, perft(board, 1));
        assertEquals(400, perft(board, 2));
        assertEquals(8_902, perft(board, 3));
    }

    @Test
    void searchKeyIncludesSideToMoveAndDrawClock() {
        Board whiteToMove = board("8/8/8/8/8/8/4K3/7k w - - 0 1");
        Board blackToMove = board("8/8/8/8/8/8/4K3/7k b - - 0 1");
        Board laterDrawClock = board("8/8/8/8/8/8/4K3/7k w - - 99 1");

        assertNotEquals(whiteToMove.getSearchKey(), blackToMove.getSearchKey());
        assertNotEquals(whiteToMove.getSearchKey(), laterDrawClock.getSearchKey());
    }

    private static long perft(Board board, int depth) {
        if (depth == 0) return 1;

        long nodes = 0;
        for (Move move : board.getLegalMoves(board.isWhiteToMove())) {
            nodes += perft(board.copyAndPlayMoveForSearch(move), depth - 1);
        }
        return nodes;
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.loadFEN(fen);
        return board;
    }
}
