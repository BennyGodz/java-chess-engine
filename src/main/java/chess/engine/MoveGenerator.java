package chess.engine;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.pieces.Piece;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns pseudo legal piece moves into legal moves.
 *
 * <p>A generated move is played on a copy of the board and kept only if it does not leave the
 * mover's own king in check.
 */
public class MoveGenerator {

  /**
   * Generates every legal move of the piece on {@code position}: each candidate is verified by
   * playing it on a board copy and rejecting the ones that leave the mover in check.
   */
  public List<Move> generateLegalMoves(Piece piece, Position position, Board board) {
    List<Move> legalMoves = new ArrayList<>();

    if (piece == null) {
      return legalMoves;
    }

    for (Move move : piece.generateMoves(position, board)) {
      Board copy = new Board(board);
      copy.makeMove(move);

      if (!copy.isInCheck(piece.isWhite())) {
        legalMoves.add(move);
      }
    }

    return legalMoves;
  }
}
