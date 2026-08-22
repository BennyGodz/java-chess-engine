package chess.engine.evaluation.nnue;

import chess.board.Board;
import java.io.File;
import java.io.IOException;

/**
 * Bridges the NNUE network into the engine's evaluation.
 *
 * <p>The network is trained to predict outcomes from the SIDE TO MOVE's perspective (see
 * GameTrainer), but the evaluator and search work in WHITE-perspective centipawns and negate for
 * Black themselves. This class performs that sign conversion.
 */
public class NNUEEvaluator {

  /** Weight files tried in order, relative to the working directory. */
  private static final String[] WEIGHT_CANDIDATES = {
    "nnue_weights_best.bin", "nnue_weights.bin", "nnue.weights"
  };

  private final NNUE nnue;

  /** Creates an evaluator, loading the default weights file if one exists. */
  public NNUEEvaluator() {
    this(loadDefaultWeights());
  }

  /** Creates an evaluator from a specific weights file. */
  public NNUEEvaluator(File weightsFile) {
    try {
      this.nnue = NNUE.load(weightsFile);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load NNUE weights: " + weightsFile.getPath(), e);
    }
  }

  /** Creates an evaluator around an already loaded network. */
  public NNUEEvaluator(NNUE nnue) {
    this.nnue = nnue;
  }

  /** Evaluates in centipawns from White's perspective. */
  public int evaluate(Board board) {
    int sideToMoveScore = nnue.evaluate(board);
    return board.isWhiteToMove() ? sideToMoveScore : -sideToMoveScore;
  }

  /**
   * Tries the known weight filenames in the project root; when none can be loaded, falls back to a
   * randomly initialized network with a warning.
   */
  private static NNUE loadDefaultWeights() {
    for (String name : WEIGHT_CANDIDATES) {
      File file = new File(name);
      if (!file.isFile()) continue;
      try {
        System.out.println("Loaded NNUE weights: " + file.getPath());
        return NNUE.load(file);
      } catch (IOException ignored) {
        // Try the next candidate.
      }
    }
    System.err.println(
        "WARNING: No NNUE weights found. Using random weights — run GameTrainer first.");
    return new NNUE(NNUEWeights.random());
  }
}
