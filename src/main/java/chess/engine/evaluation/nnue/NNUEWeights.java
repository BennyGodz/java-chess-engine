package chess.engine.evaluation.nnue;

import java.io.*;

/**
 * The weights of the NNUE network.
 *
 * <p>Architecture: 769 inputs, then hidden layers of 256, 256 and 128 ReLU units, then a single
 * tanh output. Provides random initialization (He-style) and binary save/load.
 */
public class NNUEWeights {

  public static final int INPUT_SIZE = 769;
  public static final int HIDDEN_SIZE = 256;
  public static final int SECOND_HIDDEN_SIZE = 256;
  public static final int THIRD_HIDDEN_SIZE = 128;

  public final double[][] inputWeights;
  public final double[] hiddenBias;
  public final double[][] hiddenWeights;
  public final double[] secondHiddenBias;
  public final double[][] secondHiddenWeights;
  public final double[] thirdHiddenBias;
  public final double[] outputWeights;
  public double outputBias;

  /**
   * Expected number of non-zero inputs per position (~32 pieces plus flags and structural inputs).
   * First-layer variance depends on ACTIVE inputs, not on INPUT_SIZE; scaling by the dense fan-in
   * leaves first-layer pre-activations so small that many ReLU units are born dead.
   */
  private static final double EXPECTED_ACTIVE_INPUTS = 40.0;

  /** Creates zero-initialized weights of the fixed architecture. */
  public NNUEWeights() {
    inputWeights = new double[INPUT_SIZE][HIDDEN_SIZE];
    hiddenBias = new double[HIDDEN_SIZE];
    hiddenWeights = new double[HIDDEN_SIZE][SECOND_HIDDEN_SIZE];
    secondHiddenBias = new double[SECOND_HIDDEN_SIZE];
    secondHiddenWeights = new double[SECOND_HIDDEN_SIZE][THIRD_HIDDEN_SIZE];
    thirdHiddenBias = new double[THIRD_HIDDEN_SIZE];
    outputWeights = new double[THIRD_HIDDEN_SIZE];
  }

  /** Creates randomly initialized weights with a fixed seed (deterministic, for fallbacks/tests). */
  public static NNUEWeights random() {
    return random(12345L);
  }

  /** Creates randomly initialized weights using He initialization scaled to input sparsity. */
  public static NNUEWeights random(long seed) {
    NNUEWeights weights = new NNUEWeights();
    java.util.Random random = new java.util.Random(seed);

    double inputScale = Math.sqrt(2.0 / EXPECTED_ACTIVE_INPUTS);
    double hiddenScale = Math.sqrt(2.0 / HIDDEN_SIZE);
    double secondHiddenScale = Math.sqrt(2.0 / SECOND_HIDDEN_SIZE);
    double thirdHiddenScale = Math.sqrt(2.0 / THIRD_HIDDEN_SIZE);

    for (int i = 0; i < INPUT_SIZE; i++) {
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        weights.inputWeights[i][h] = random.nextGaussian() * inputScale;
      }
    }

    for (int h = 0; h < HIDDEN_SIZE; h++) {
      weights.hiddenBias[h] = 0.0;
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        weights.hiddenWeights[h][h2] = random.nextGaussian() * hiddenScale;
      }
    }

    for (int h = 0; h < SECOND_HIDDEN_SIZE; h++) {
      weights.secondHiddenBias[h] = 0.0;
      for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
        weights.secondHiddenWeights[h][h2] = random.nextGaussian() * secondHiddenScale;
      }
    }

    for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
      weights.thirdHiddenBias[h2] = 0.0;
      weights.outputWeights[h2] = random.nextGaussian() * thirdHiddenScale;
    }

    weights.outputBias = 0.0;
    return weights;
  }

  /** Writes all weights to a binary file, preceded by the layer sizes as a format check. */
  public void save(File file) throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      out.writeInt(INPUT_SIZE);
      out.writeInt(HIDDEN_SIZE);
      out.writeInt(SECOND_HIDDEN_SIZE);
      out.writeInt(THIRD_HIDDEN_SIZE);

      for (int i = 0; i < INPUT_SIZE; i++) {
        for (int h = 0; h < HIDDEN_SIZE; h++) {
          out.writeDouble(inputWeights[i][h]);
        }
      }
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        out.writeDouble(hiddenBias[h]);
      }
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
          out.writeDouble(hiddenWeights[h][h2]);
        }
      }
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        out.writeDouble(secondHiddenBias[h2]);
      }
      for (int h = 0; h < SECOND_HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
          out.writeDouble(secondHiddenWeights[h][h2]);
        }
      }
      for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
        out.writeDouble(thirdHiddenBias[h2]);
      }
      for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
        out.writeDouble(outputWeights[h2]);
      }
      out.writeDouble(outputBias);
    }
  }

  /** Reads weights written by {@link #save(File)}, verifying the architecture matches. */
  public static NNUEWeights load(File file) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      int inputSize = in.readInt();
      int hiddenSize = in.readInt();
      int secondHiddenSize = in.readInt();
      int thirdHiddenSize = in.readInt();
      if (inputSize != INPUT_SIZE
          || hiddenSize != HIDDEN_SIZE
          || secondHiddenSize != SECOND_HIDDEN_SIZE
          || thirdHiddenSize != THIRD_HIDDEN_SIZE) {
        throw new IOException("Incompatible NNUE network architecture.");
      }
      NNUEWeights weights = new NNUEWeights();
      for (int i = 0; i < INPUT_SIZE; i++) {
        for (int h = 0; h < HIDDEN_SIZE; h++) {
          weights.inputWeights[i][h] = in.readDouble();
        }
      }
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        weights.hiddenBias[h] = in.readDouble();
      }
      for (int h = 0; h < HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
          weights.hiddenWeights[h][h2] = in.readDouble();
        }
      }
      for (int h2 = 0; h2 < SECOND_HIDDEN_SIZE; h2++) {
        weights.secondHiddenBias[h2] = in.readDouble();
      }
      for (int h = 0; h < SECOND_HIDDEN_SIZE; h++) {
        for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
          weights.secondHiddenWeights[h][h2] = in.readDouble();
        }
      }
      for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
        weights.thirdHiddenBias[h2] = in.readDouble();
      }
      for (int h2 = 0; h2 < THIRD_HIDDEN_SIZE; h2++) {
        weights.outputWeights[h2] = in.readDouble();
      }
      weights.outputBias = in.readDouble();
      return weights;
    }
  }
}
