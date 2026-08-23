package chess.engine.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SearchEngineTest {

  @Test
  void shortEndgameSearchReturnsAReasonedFallbackInsteadOfInfinity() {
    Board board = new Board();
    board.loadFEN("8/8/8/8/8/2k5/8/R3K3 w - - 0 1");
    SearchEngine.SearchResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(2), () -> new SearchEngine().findBestMove(board, 4, 1));

    assertNotNull(result.bestMove());
    assertTrue(Math.abs(result.score()) <= SearchEngine.MATE_SCORE);
  }

  @Test
  void repeatedPositionsAreNotCachedAndAreDiscouragedAtTheRoot() {
    Board board = new Board();
    board.playMove(new Move(new Position(7, 6), new Position(5, 5)));
    board.playMove(new Move(new Position(0, 6), new Position(2, 5)));
    board.playMove(new Move(new Position(5, 5), new Position(7, 6)));
    board.playMove(new Move(new Position(2, 5), new Position(0, 6)));

    assertEquals(2, board.getCurrentPositionRepetitionCount());
    assertFalse(SearchEngine.isTranspositionSafe(board));
    assertEquals(30, SearchEngine.repetitionPenalty(board));
  }
}
