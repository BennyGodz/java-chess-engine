package chess.engine.evaluation.nnue;

import chess.board.Board;
import java.io.File;
import java.io.IOException;

/** Runs the trained network and returns side-to-move centipawns. */
public class NNUE {

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

      hidden[h] = Math.max(0.0, sum);
    }

    denseLayer(hidden, weights.hiddenWeights, weights.secondHiddenBias, hidden2);
    denseLayer(hidden2, weights.secondHiddenWeights, weights.thirdHiddenBias, hidden3);

    double output = weights.outputBias;

    for (int i = 0; i < hidden3.length; i++) output += hidden3[i] * weights.outputWeights[i];

    return (int) Math.round(Math.tanh(output) * OUTPUT_SCALE);
  }

  private static void denseLayer(
      double[] input, double[][] matrix, double[] bias, double[] output) {
    for (int j = 0; j < output.length; j++) {
      double sum = bias[j];
      for (int i = 0; i < input.length; i++) sum += input[i] * matrix[i][j];
      output[j] = Math.max(0.0, sum);
    }
  }

  public NNUEWeights getWeights() {
    return weights;
  }

  public void save(File file) throws IOException {
    weights.save(file);
  }

  public static NNUE load(File file) throws IOException {
    return new NNUE(NNUEWeights.load(file));
  }
}
