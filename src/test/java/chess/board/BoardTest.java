package chess.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

  @Test
  void zobristHashDistinguishesPieceColours() {
    Board whiteQueen = new Board();
    whiteQueen.loadFEN("7k/8/8/8/8/8/6K1/Q7 w - - 0 1");
    Board blackQueen = new Board();
    blackQueen.loadFEN("7K/8/8/8/8/8/6k1/q7 w - - 0 1");

    assertNotEquals(whiteQueen.getZobristKey(), blackQueen.getZobristKey());
  }

  @Test
  void nullMoveDoesNotChangeRealGameClocksOrCreateRepetition() {
    Board board = new Board();
    int halfmoveClock = board.getHalfmoveClock();
    int fullmoveNumber = board.getFullmoveNumber();

    board.makeNullMove();

    assertEquals(halfmoveClock, board.getHalfmoveClock());
    assertEquals(fullmoveNumber, board.getFullmoveNumber());
    assertEquals(0, board.getCurrentPositionRepetitionCount());
  }

  private static long countPositions(Board board, int depth) {
    if (depth == 0) return 1;
    long positions = 0;
    for (Move move : board.getLegalMoves(board.isWhiteToMove())) {
      Board child = board.copyAndPlayMoveForSearch(move);
      positions += countPositions(child, depth - 1);
    }
    return positions;
  }
}
