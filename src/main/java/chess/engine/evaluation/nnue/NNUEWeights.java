package chess.engine.evaluation.nnue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * The weights of the NNUE network: 769 inputs, then hidden layers of 256, 256 and 128 ReLU units,
 * then a single tanh output. Provides random initialization (He-style) and binary save/load.
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

  private static final double EXPECTED_ACTIVE_INPUTS = 40.0;

  public NNUEWeights() {
    inputWeights = new double[INPUT_SIZE][HIDDEN_SIZE];
    hiddenBias = new double[HIDDEN_SIZE];
    hiddenWeights = new double[HIDDEN_SIZE][SECOND_HIDDEN_SIZE];
    secondHiddenBias = new double[SECOND_HIDDEN_SIZE];
    secondHiddenWeights = new double[SECOND_HIDDEN_SIZE][THIRD_HIDDEN_SIZE];
    thirdHiddenBias = new double[THIRD_HIDDEN_SIZE];
    outputWeights = new double[THIRD_HIDDEN_SIZE];
  }

  public static NNUEWeights random() {
    return random(12345L);
  }

  public static NNUEWeights random(long seed) {
    NNUEWeights weights = new NNUEWeights();
    Random random = new Random(seed);
    fillGaussian(weights.inputWeights, Math.sqrt(2.0 / EXPECTED_ACTIVE_INPUTS), random);
    fillGaussian(weights.hiddenWeights, Math.sqrt(2.0 / HIDDEN_SIZE), random);
    fillGaussian(weights.secondHiddenWeights, Math.sqrt(2.0 / SECOND_HIDDEN_SIZE), random);
    fillGaussian(weights.outputWeights, Math.sqrt(2.0 / THIRD_HIDDEN_SIZE), random);
    return weights;
  }

  public NNUEWeights copy() {
    NNUEWeights copy = new NNUEWeights();
    copy(inputWeights, copy.inputWeights);
    System.arraycopy(hiddenBias, 0, copy.hiddenBias, 0, hiddenBias.length);
    copy(hiddenWeights, copy.hiddenWeights);
    System.arraycopy(secondHiddenBias, 0, copy.secondHiddenBias, 0, secondHiddenBias.length);
    copy(secondHiddenWeights, copy.secondHiddenWeights);
    System.arraycopy(thirdHiddenBias, 0, copy.thirdHiddenBias, 0, thirdHiddenBias.length);
    System.arraycopy(outputWeights, 0, copy.outputWeights, 0, outputWeights.length);
    copy.outputBias = outputBias;
    return copy;
  }

  private static void copy(double[][] source, double[][] target) {
    for (int i = 0; i < source.length; i++) {
      System.arraycopy(source[i], 0, target[i], 0, source[i].length);
    }
  }

  private static void fillGaussian(double[][] values, double scale, Random random) {
    for (double[] row : values) fillGaussian(row, scale, random);
  }

  private static void fillGaussian(double[] values, double scale, Random random) {
    for (int i = 0; i < values.length; i++) values[i] = random.nextGaussian() * scale;
  }

  public void save(File file) throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      out.writeInt(INPUT_SIZE);
      out.writeInt(HIDDEN_SIZE);
      out.writeInt(SECOND_HIDDEN_SIZE);
      out.writeInt(THIRD_HIDDEN_SIZE);

      write(out, inputWeights);
      write(out, hiddenBias);
      write(out, hiddenWeights);
      write(out, secondHiddenBias);
      write(out, secondHiddenWeights);
      write(out, thirdHiddenBias);
      write(out, outputWeights);
      out.writeDouble(outputBias);
    }
  }

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
      read(in, weights.inputWeights);
      read(in, weights.hiddenBias);
      read(in, weights.hiddenWeights);
      read(in, weights.secondHiddenBias);
      read(in, weights.secondHiddenWeights);
      read(in, weights.thirdHiddenBias);
      read(in, weights.outputWeights);
      weights.outputBias = in.readDouble();
      return weights;
    }
  }

  private static void write(DataOutputStream out, double[][] values) throws IOException {
    for (double[] row : values) write(out, row);
  }

  private static void write(DataOutputStream out, double[] values) throws IOException {
    for (double value : values) out.writeDouble(value);
  }

  private static void read(DataInputStream in, double[][] values) throws IOException {
    for (double[] row : values) read(in, row);
  }

  private static void read(DataInputStream in, double[] values) throws IOException {
    for (int i = 0; i < values.length; i++) values[i] = in.readDouble();
  }
}
