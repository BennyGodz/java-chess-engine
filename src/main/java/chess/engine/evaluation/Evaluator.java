package chess.engine.evaluation;

import chess.board.Board;
import chess.board.Position;
import chess.engine.evaluation.nnue.NNUEEvaluator;
import chess.pieces.King;
import chess.pieces.Piece;

/** Static evaluation in centipawns from White's perspective. */
public class Evaluator {

  private final NNUEEvaluator nnue;

  private static final int ENDGAME_MATERIAL_THRESHOLD = 1500;

  public Evaluator() {
    nnue = new NNUEEvaluator();
  }

  public int evaluate(Board board) {
    int[] material = countMaterial(board);
    int score = (nnue.evaluate(board) * 3 + material[0] - material[1]) / 4;
    score += board.isWhiteToMove() ? 10 : -10;
    if (material[0] + material[1] < ENDGAME_MATERIAL_THRESHOLD) {
      score += evaluateEndgame(board, material[0], material[1]);
    }
    return score;
  }

  private int evaluateEndgame(Board board, int whiteMaterial, int blackMaterial) {
    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);
    if (whiteKing == null || blackKing == null) return 0;
    int kingDistance = 14 - getDistance(whiteKing, blackKing);
    if (whiteMaterial > blackMaterial + 300) {
      return getKingEdgeBonus(blackKing) * 10 + kingDistance * 4;
    }
    if (blackMaterial > whiteMaterial + 300) {
      return -getKingEdgeBonus(whiteKing) * 10 - kingDistance * 4;
    }
    return 0;
  }

  private int[] countMaterial(Board board) {
    int[] material = new int[2];
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        Piece piece = board.getPiece(new Position(r, c));
        if (piece != null && !(piece instanceof King)) {
          material[piece.isWhite() ? 0 : 1] += piece.getValue();
        }
      }
    }
    return material;
  }

  private int getKingEdgeBonus(Position k) {
    return Math.max(k.getRow(), 7 - k.getRow()) + Math.max(k.getColumn(), 7 - k.getColumn());
  }

  private int getDistance(Position a, Position b) {
    return Math.abs(a.getRow() - b.getRow()) + Math.abs(a.getColumn() - b.getColumn());
  }
}
