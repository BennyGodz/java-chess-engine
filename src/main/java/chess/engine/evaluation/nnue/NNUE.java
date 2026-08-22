package chess.engine.evaluation.nnue;

import chess.board.Board;
import java.io.File;
import java.io.IOException;

/**
 * Runs a trained neural network over a board position.
 *
 * <p>The network is trained to predict the game outcome from the side to move's perspective; this
 * class converts that output to White-perspective centipawns, which is what the rest of the engine
 * expects.
 */
public class NNUE {

  /** Output of tanh() in [-1, 1] scaled up to centipawns. */
  private static final int OUTPUT_SCALE = 400;

  /**
   * Reusable per-thread activation buffers. evaluate() runs at every search leaf, and allocating
   * several hundred doubles there shows up as pure GC pressure. ThreadLocal keeps instances safe
   * when engines run in parallel threads.
   */
  private final ThreadLocal<double[][]> scratch =
      ThreadLocal.withInitial(
          () ->
              new double[][] {
                new double[NNUEWeights.HIDDEN_SIZE],
                new double[NNUEWeights.SECOND_HIDDEN_SIZE],
                new double[NNUEWeights.THIRD_HIDDEN_SIZE]
              });

  private final NNUEWeights weights;
  private final NNUEFeatureExtractor extractor;

  public NNUE(NNUEWeights weights) {
    this.weights = weights;
    extractor = new NNUEFeatureExtractor();
  }

  /**
   * Evaluates the board through the network: features are extracted, pushed through three ReLU
   * hidden layers, squashed by tanh and scaled to centipawns from the side to move's perspective.
   */
  public int evaluate(Board board) {
    double[] features = extractor.extract(board);
    double[][] buffers = scratch.get();
    double[] hidden = buffers[0];
    double[] hidden2 = buffers[1];
    double[] hidden3 = buffers[2];

    for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {
      double sum = weights.hiddenBias[h];

      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++) {
        if (features[i] != 0.0) sum += features[i] * weights.inputWeights[i][h];
      }

      hidden[h] = relu(sum);
    }

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {
      double sum = weights.secondHiddenBias[h2];

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++)
        sum += hidden[h] * weights.hiddenWeights[h][h2];

      hidden2[h2] = relu(sum);
    }

    for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
      double sum = weights.thirdHiddenBias[h3];

      for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++)
        sum += hidden2[h2] * weights.secondHiddenWeights[h2][h3];

      hidden3[h3] = relu(sum);
    }

    double output = weights.outputBias;

    for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++)
      output += hidden3[h3] * weights.outputWeights[h3];

    return (int) Math.round(Math.tanh(output) * OUTPUT_SCALE);
  }

  /** Rectified linear unit used on all hidden layers. */
  private static double relu(double x) {
    return Math.max(0.0, x);
  }

  /** Returns the network's weights, e.g. for training or copying. */
  public NNUEWeights getWeights() {
    return weights;
  }

  /** Writes the current weights to a binary file. */
  public void save(File file) throws IOException {
    weights.save(file);
  }

  /** Loads a network from a binary weights file. */
  public static NNUE load(File file) throws IOException {
    return new NNUE(NNUEWeights.load(file));
  }
}
