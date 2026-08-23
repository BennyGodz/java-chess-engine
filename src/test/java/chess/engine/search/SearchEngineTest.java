package chess.engine.search;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import chess.board.Board;
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
}
