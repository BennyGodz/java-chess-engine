package chess.engine.evaluation.nnue;

import java.io.*;

/**
 * Stores the weights used by the NNUE network.
 *
 * <p>Architecture:
 *
 * <p>769 inputs ↓ 64 hidden neurons ↓ 32 hidden neurons ↓ 1 output
 */
public class NNUEWeights {

  public static final int INPUT_SIZE = 769;

  public static final int HIDDEN_SIZE = 64;

  public static final int SECOND_HIDDEN_SIZE = 32;

  public final double[][] inputWeights;

  public final double[] hiddenBias;

  public final double[][] hiddenWeights;

  public final double[] secondHiddenBias;

  public final double[] outputWeights;

  public double outputBias;

  public NNUEWeights() {

    inputWeights = new double[INPUT_SIZE][HIDDEN_SIZE];

    hiddenBias = new double[HIDDEN_SIZE];

    hiddenWeights = new double[HIDDEN_SIZE][SECOND_HIDDEN_SIZE];

    secondHiddenBias = new double[SECOND_HIDDEN_SIZE];

    outputWeights = new double[SECOND_HIDDEN_SIZE];
  }

  /** Create randomly initialized weights. */
  public static NNUEWeights random() {

    NNUEWeights weights = new NNUEWeights();

    java.util.Random random = new java.util.Random(12345);

    double inputScale = Math.sqrt(2.0 / INPUT_SIZE);

    double hiddenScale = Math.sqrt(2.0 / HIDDEN_SIZE);

    double secondHiddenScale = Math.sqrt(2.0 / SECOND_HIDDEN_SIZE);

    /*
     * Input -> first hidden.
     */
    for (int i = 0; i < INPUT_SIZE; i++) {
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        weights.inputWeights[i][h] = random.nextGaussian() * inputScale;
      }
    }

    /*
     * First hidden layer.
     */
    for (int h = 0; h < HIDDEN_SIZE; h++) {
      weights.hiddenBias[h] = 0.0;
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        weights.hiddenWeights[h][h2] = random.nextGaussian() * hiddenScale;
      }
    }

    /*
     * Second hidden -> output.
     */
    for (int h = 0; h < SECOND_HIDDEN_SIZE; h++) {
      weights.secondHiddenBias[h] = 0.0;
      weights.outputWeights[h] = random.nextGaussian() * secondHiddenScale;
    }

    weights.outputBias = 0.0;

    return weights;
  }

  /** Save weights to a binary file. */
  public void save(File file) throws IOException {

    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
        /* * Header. */
      out.writeInt(INPUT_SIZE);
      out.writeInt(HIDDEN_SIZE);
      out.writeInt(SECOND_HIDDEN_SIZE); /* * Input -> hidden. */
      for (int i = 0; i < INPUT_SIZE; i++) {
        for (int h = 0; h < HIDDEN_SIZE; h++) {
          out.writeDouble(inputWeights[i][h]);
        }
      } /* * First hidden biases. */
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        out.writeDouble(hiddenBias[h]);
      } /* * Hidden -> second hidden. */
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
          out.writeDouble(hiddenWeights[h][h2]);
        }
      } /* * Second hidden biases. */
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        out.writeDouble(secondHiddenBias[h2]);
      } /* * Output weights. */
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        out.writeDouble(outputWeights[h2]);
      } /* * Output bias. */
      out.writeDouble(outputBias);
    }
  }

  /** Load weights from a binary file. */
  public static NNUEWeights load(File file) throws IOException {

    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      int inputSize = in.readInt();
      int hiddenSize = in.readInt();
      int secondHiddenSize = in.readInt();
      if (inputSize != INPUT_SIZE
          || hiddenSize != HIDDEN_SIZE
          || secondHiddenSize != SECOND_HIDDEN_SIZE) {
        throw new IOException("Incompatible NNUE network.");
      }
      NNUEWeights weights = new NNUEWeights(); /* * Input -> hidden. */
      for (int i = 0; i < INPUT_SIZE; i++) {
        for (int h = 0; h < HIDDEN_SIZE; h++) {
          weights.inputWeights[i][h] = in.readDouble();
        }
      } /* * First hidden biases. */
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        weights.hiddenBias[h] = in.readDouble();
      } /* * Hidden -> second hidden. */
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
          weights.hiddenWeights[h][h2] = in.readDouble();
        }
      } /* * Second hidden biases. */
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        weights.secondHiddenBias[h2] = in.readDouble();
      } /* * Output weights. */
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        weights.outputWeights[h2] = in.readDouble();
      }
      weights.outputBias = in.readDouble();
      return weights;
    }
  }
}
