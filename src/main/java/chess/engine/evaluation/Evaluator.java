package chess.engine.evaluation;

import chess.board.Board;
import chess.board.Position;
import chess.engine.evaluation.nnue.NNUEEvaluator;
import chess.pieces.King;
import chess.pieces.Piece;

public class Evaluator {

  private final NNUEEvaluator nnue;
  private static final int ENDGAME_MATERIAL_THRESHOLD = 1500;

  public Evaluator() {
    nnue = new NNUEEvaluator();
  }

  public int evaluate(Board board) {
    int score = nnue.evaluate(board);

    // Tempo bonus
    score += board.isWhiteToMove() ? 10 : -10;

    int totalMaterial = countMaterial(board, true) + countMaterial(board, false);
    if (totalMaterial < ENDGAME_MATERIAL_THRESHOLD) {
      score += evaluateEndgame(board);
    }

    return score;
  }

  private int evaluateEndgame(Board board) {
    int bonus = 0;
    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);

    if (whiteKing != null && blackKing != null) {
      int whiteMat = countMaterial(board, true);
      int blackMat = countMaterial(board, false);

      if (whiteMat > blackMat + 300) {
        bonus += getKingEdgeBonus(blackKing) * 10;
        bonus += (14 - getDistance(whiteKing, blackKing)) * 4;
      } else if (blackMat > whiteMat + 300) {
        bonus -= getKingEdgeBonus(whiteKing) * 10;
        bonus -= (14 - getDistance(whiteKing, blackKing)) * 4;
      }
    }
    return bonus;
  }

  private int countMaterial(Board board, boolean white) {
    int material = 0;
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        Piece p = board.getPiece(new Position(r, c));
        if (p != null && p.isWhite() == white && !(p instanceof King)) {
          material += p.getValue();
        }
      }
    }
    return material;
  }

  private int getKingEdgeBonus(Position k) {
    int centerDistRow = Math.max(k.getRow(), 7 - k.getRow());
    int centerDistCol = Math.max(k.getColumn(), 7 - k.getColumn());
    return centerDistRow + centerDistCol;
  }

  private int getDistance(Position a, Position b) {
    return Math.abs(a.getRow() - b.getRow()) + Math.abs(a.getColumn() - b.getColumn());
  }
}
