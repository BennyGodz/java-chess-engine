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

  @Test
  void safetyPassSavesQueenInExactBugaBotRepetitionPosition() {
    Board board = new Board();
    board.loadFEN("3Q1K2/p4P2/2p5/8/2k1q3/8/8/8 w - - 0 68");
    play(board, "d8e7 e4g4 f8e8 g4c8 e7d8 c8e6 e8f8 e6e4 d8e7");

    assertEquals(2, board.getCurrentPositionRepetitionCount());
    SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 6, 1);

    assertNotNull(result.bestMove());
    assertTrue(result.depth() >= 1);
    assertEquals(square("e4"), result.bestMove().getStart());
  }

  @Test
  void safetyPassSavesDirectlyHangingRookAtOneMillisecond() {
    Board board = new Board();
    board.loadFEN("7k/p7/8/4r3/8/8/4Q3/7K b - - 0 1");

    SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 6, 1);

    assertNotNull(result.bestMove());
    assertEquals(square("e5"), result.bestMove().getStart());
  }

  @Test
  void rookRemovesPromotedQueenInForcedSequenceFromGame() {
    Board board = new Board();
    board.loadFEN("2r4Q/p5Q1/2pkqP2/8/8/6P1/5P2/5K2 b - - 0 37");

    SearchEngine.SearchResult result = new SearchEngine().findBestMove(board, 8, 300);

    assertNotNull(result.bestMove());
    assertEquals(square("c8"), result.bestMove().getStart());
    assertEquals(square("h8"), result.bestMove().getEnd());
  }

  private static void play(Board board, String uciMoves) {
    for (String uci : uciMoves.split("\\s+")) {
      char promotion = uci.length() == 5 ? Character.toUpperCase(uci.charAt(4)) : 'Q';
      Move move =
          board.findLegalMove(square(uci.substring(0, 2)), square(uci.substring(2, 4)), promotion);
      assertNotNull(move, "Illegal test move: " + uci);
      board.playMove(move);
    }
  }

  private static Position square(String text) {
    return new Position(8 - (text.charAt(1) - '0'), text.charAt(0) - 'a');
  }
}
