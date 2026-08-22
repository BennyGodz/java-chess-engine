package chess.engine.evaluation;

import chess.board.Board;
import chess.board.Position;
import chess.engine.evaluation.nnue.NNUEEvaluator;
import chess.pieces.Piece;

/**
 * Static position evaluation in centipawns from White's perspective.
 *
 * <p>Blends the NNUE network output with a raw material count, adds a small tempo bonus and
 * applies king-activity heuristics once the endgame is reached.
 */
public class Evaluator {

  private final NNUEEvaluator nnue;
  /** Below this much total material (both sides, no kings) endgame heuristics kick in. */
  private static final int ENDGAME_MATERIAL_THRESHOLD = 1500;

  public Evaluator() {
    nnue = new NNUEEvaluator();
  }

  /**
   * Evaluates the board: a weighted mix of NNUE score and material, plus tempo, plus endgame
   * bonuses when little material remains. Positive values favour White.
   */
  public int evaluate(Board board) {
    int nnueScore = nnue.evaluate(board);
    int materialScore = countMaterial(board, true) - countMaterial(board, false);

    int score = (nnueScore * 3 + materialScore) / 4;
    score += board.isWhiteToMove() ? 10 : -10;

    int totalMaterial = countMaterial(board, true) + countMaterial(board, false);
    if (totalMaterial < ENDGAME_MATERIAL_THRESHOLD)
      score += evaluateEndgame(board);

    return score;
  }

  /**
   * Endgame guidance for the winning side: push the losing king to the edge and keep the kings
   * close together, which makes converting an advantage easier.
   */
  private int evaluateEndgame(Board board) {
    int bonus = 0;
    Position whiteKing = board.findKing(true);
    Position blackKing = board.findKing(false);

    if (whiteKing == null || blackKing == null) return bonus;

    int whiteMat = countMaterial(board, true);
    int blackMat = countMaterial(board, false);

    if (whiteMat > blackMat + 300) {
      bonus += getKingEdgeBonus(blackKing) * 10;
      bonus += (14 - getDistance(whiteKing, blackKing)) * 4;
    } else if (blackMat > whiteMat + 300) {
      bonus -= getKingEdgeBonus(whiteKing) * 10;
      bonus -= (14 - getDistance(whiteKing, blackKing)) * 4;
    }

    return bonus;
  }

  /** Sums the material value of one side's pieces, excluding the king. */
  private int countMaterial(Board board, boolean white) {
    int material = 0;

    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        Piece p = board.getPiece(new Position(r, c));
        if (p != null && p.isWhite() == white && !(p instanceof King))
          material += p.getValue();
      }
    }

    return material;
  }

  /** Scores how close a square sits to the board edge; higher means closer to the rim. */
  private int getKingEdgeBonus(Position k) {
    int row = Math.max(k.getRow(), 7 - k.getRow());
    int col = Math.max(k.getColumn(), 7 - k.getColumn());
    return row + col;
  }

  /** Manhattan distance between two squares. */
  private int getDistance(Position a, Position b) {
    return Math.abs(a.getRow() - b.getRow()) + Math.abs(a.getColumn() - b.getColumn());
  }
}
