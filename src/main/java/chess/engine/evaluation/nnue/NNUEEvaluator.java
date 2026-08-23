package chess.engine.evaluation.nnue;

import chess.board.Board;
import java.io.File;
import java.io.IOException;

/** Converts the network's side-to-move score to White-perspective centipawns. */
public class NNUEEvaluator {

  private static final String[] WEIGHT_CANDIDATES = {
    "nnue_weights_best.bin", "nnue_weights.bin", "nnue.weights"
  };

  private final NNUE nnue;

  public NNUEEvaluator() {
    this(loadDefaultWeights());
  }

  public NNUEEvaluator(File weightsFile) {
    try {
      this.nnue = NNUE.load(weightsFile);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load NNUE weights: " + weightsFile.getPath(), e);
    }
  }

  public NNUEEvaluator(NNUE nnue) {
    this.nnue = nnue;
  }

  public int evaluate(Board board) {
    int sideToMoveScore = nnue.evaluate(board);
    return board.isWhiteToMove() ? sideToMoveScore : -sideToMoveScore;
  }

  /**
   * Tries the known weight filenames in the project root; when none can be loaded, falls back to a
   * zero-output network with a warning. The engine's material evaluation remains active, while a
   * missing model can never inject random scores into move selection.
   */
  private static NNUE loadDefaultWeights() {
    for (String name : WEIGHT_CANDIDATES) {
      File file = new File(name);
      if (!file.isFile()) continue;
      try {
        System.out.println("Loaded NNUE weights: " + file.getPath());
        return NNUE.load(file);
      } catch (IOException ignored) {
      }
    }
    System.err.println(
        "WARNING: No NNUE weights found. Using material evaluation only — run GameTrainer first.");
    return new NNUE(new NNUEWeights());
  }
}
