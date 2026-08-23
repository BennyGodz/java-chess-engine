package chess.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoardTest {

  @Test
  void startingPositionAndCopyStayEquivalent() {
    Board board = new Board();
    Board copy = new Board(board);

    assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", board.toFEN());
    assertEquals(20, board.getLegalMoves(true).size());
    assertEquals(board.toFEN(), copy.toFEN());
    assertEquals(board.getZobristKey(), copy.getZobristKey());
  }

  @Test
  void bothWhiteCastlesRemainLegalOnAnEmptyBackRank() {
    Board board = new Board();
    board.loadFEN("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

    assertEquals(2, board.getLegalMoves(true).stream().filter(Move::isCastling).count());
  }

  @Test
  void startingPositionMatchesDepthThreePerft() {
    assertEquals(8_902, countPositions(new Board(), 3));
  }

  private static long countPositions(Board board, int depth) {
    if (depth == 0) return 1;
    long positions = 0;
    for (Move move : board.getLegalMoves(board.isWhiteToMove())) {
      Board child = new Board(board);
      child.playMove(move);
      positions += countPositions(child, depth - 1);
    }
    return positions;
  }
}
