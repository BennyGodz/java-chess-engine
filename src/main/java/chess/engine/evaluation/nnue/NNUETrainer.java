package chess.engine.evaluation.nnue;

import chess.board.Board;
import java.io.*;
import java.util.*;

/**
 * Trains the NNUE network using Stockfish evaluations.
 *
 * <p>Training data format:
 *
 * <p>FEN|evaluation
 *
 * <p>Evaluation is expected to be in pawns and from White's perspective.
 *
 * <p>Example:
 *
 * <p>FEN|0.35
 *
 * <p>Architecture:
 *
 * <p>769 -> 64 -> 32 -> 1
 */
public class NNUETrainer {

  private static final String TRAINING_FILE = "training_data.txt";

  private static final String OUTPUT_FILE = "nnue_weights.bin";

  private static final String BEST_OUTPUT_FILE = "nnue_weights_best.bin";

  private static final int EPOCHS = 15;

  private static final int BATCH_SIZE = 128;

  private static final double LEARNING_RATE = 0.0003;

  /*
   * Stockfish pawn evaluations are converted into
   * a target between -1 and +1.
   *
   * 1 pawn:
   *
   * tanh(100 / 400)
   */
  private static final double TARGET_SCALE = 400.0;

  /*
   * Prevents extreme Stockfish values from
   * dominating training.
   */
  private static final double EVALUATION_CLAMP = 10.0;

  /*
   * Gradient clipping.
   */
  private static final double GRADIENT_CLIP = 5.0;

  /*
   * 90% training.
   * 10% validation.
   */
  private static final double VALIDATION_RATIO = 0.10;

  public static void main(String[] args) {

    System.out.println("Loading training data...");

    try {

      List<TrainingExample> examples = loadData(TRAINING_FILE);

      System.out.println("Loaded " + examples.size() + " positions.");

      if (examples.isEmpty()) {

        throw new IllegalStateException("No training data found.");
      }

      Collections.shuffle(examples, new Random(12345));

      int validationSize = Math.max(1, (int) (examples.size() * VALIDATION_RATIO));

      int trainingSize = examples.size() - validationSize;

      List<TrainingExample> trainingData = new ArrayList<>(examples.subList(0, trainingSize));

      List<TrainingExample> validationData =
          new ArrayList<>(examples.subList(trainingSize, examples.size()));

      System.out.println("Training positions: " + trainingData.size());

      System.out.println("Validation positions: " + validationData.size());

      NNUEWeights weights = NNUEWeights.random();

      train(weights, trainingData, validationData);

      /*
       * Save final weights.
       */
      weights.save(new File(OUTPUT_FILE));

      System.out.println();
      System.out.println("Training complete.");

      System.out.println("Saved: " + OUTPUT_FILE);

    } catch (Exception e) {

      e.printStackTrace();
    }
  }

  private static List<TrainingExample> loadData(String file) throws IOException {

    List<TrainingExample> data = new ArrayList<>();

    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {

      String line;

      while ((line = reader.readLine()) != null) {

        line = line.trim();

        if (line.isEmpty()) {
          continue;
        }

        int separator = line.lastIndexOf('|');

        if (separator <= 0) {
          continue;
        }

        String fen = line.substring(0, separator);

        String evaluationText = line.substring(separator + 1);

        try {

          double evaluation = Double.parseDouble(evaluationText);

          if (Double.isNaN(evaluation) || Double.isInfinite(evaluation)) {
            continue;
          }

          /*
           * Remove extreme values.
           *
           * Mate should be handled by search.
           */
          evaluation = Math.max(-EVALUATION_CLAMP, Math.min(EVALUATION_CLAMP, evaluation));

          Board board = new Board();

          board.loadFEN(fen);

          data.add(new TrainingExample(board, evaluation));

        } catch (Exception ignored) {

          /*
           * Skip malformed positions.
           */
        }
      }
    }

    return data;
  }

  private static void train(
      NNUEWeights weights,
      List<TrainingExample> trainingData,
      List<TrainingExample> validationData) {

    NNUEFeatureExtractor extractor = new NNUEFeatureExtractor();

    Random random = new Random(12345);

    double bestValidationLoss = Double.POSITIVE_INFINITY;

    for (int epoch = 1; epoch <= EPOCHS; epoch++) {

      Collections.shuffle(trainingData, random);

      double totalLoss = 0.0;

      int processed = 0;

      for (int start = 0; start < trainingData.size(); start += BATCH_SIZE) {

        int end = Math.min(start + BATCH_SIZE, trainingData.size());

        Gradients gradients = new Gradients();

        double batchLoss = 0.0;

        for (int index = start; index < end; index++) {

          TrainingExample example = trainingData.get(index);

          double[] features = extractor.extract(example.board);

          ForwardResult forward = forward(weights, features);

          double target = normalizeTarget(example.evaluation);

          double error = forward.output - target;

          batchLoss += error * error;

          backward(weights, gradients, features, forward, target);
        }

        int batchSize = end - start;

        gradients.scale(1.0 / batchSize);

        gradients.clip(GRADIENT_CLIP);

        gradients.apply(weights, LEARNING_RATE);

        totalLoss += batchLoss;

        processed += batchSize;
      }

      double trainingLoss = totalLoss / trainingData.size();

      double validationLoss = calculateLoss(weights, validationData, extractor);

      System.out.printf(
          "Epoch %d/%d | train %.6f | validation %.6f | positions %d%n",
          epoch, EPOCHS, trainingLoss, validationLoss, processed);

      /*
       * Save the best validation network.
       */
      if (validationLoss < bestValidationLoss) {

        bestValidationLoss = validationLoss;

        try {

          weights.save(new File(BEST_OUTPUT_FILE));

          System.out.println("  New best network saved.");

        } catch (IOException e) {

          throw new RuntimeException("Could not save best weights.", e);
        }
      }
    }
  }

  private static double calculateLoss(
      NNUEWeights weights, List<TrainingExample> data, NNUEFeatureExtractor extractor) {

    double totalLoss = 0.0;

    for (TrainingExample example : data) {

      double[] features = extractor.extract(example.board);

      ForwardResult forward = forward(weights, features);

      double target = normalizeTarget(example.evaluation);

      double error = forward.output - target;

      totalLoss += error * error;
    }

    return totalLoss / data.size();
  }

  private static double normalizeTarget(double evaluation) {

    return Math.tanh(evaluation * 100.0 / TARGET_SCALE);
  }

  private static ForwardResult forward(NNUEWeights weights, double[] features) {

    double[] hidden = new double[NNUEWeights.HIDDEN_SIZE];

    /*
     * Input -> first hidden.
     *
     * Skip all zero features.
     */
    for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

      double sum = weights.hiddenBias[h];

      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++) {

        if (features[i] == 0.0) {
          continue;
        }

        sum += features[i] * weights.inputWeights[i][h];
      }

      hidden[h] = relu(sum);
    }

    /*
     * First hidden -> second hidden.
     */
    double[] hidden2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

      double sum = weights.secondHiddenBias[h2];

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

        sum += hidden[h] * weights.hiddenWeights[h][h2];
      }

      hidden2[h2] = relu(sum);
    }

    /*
     * Second hidden -> output.
     */
    double rawOutput = weights.outputBias;

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

      rawOutput += hidden2[h2] * weights.outputWeights[h2];
    }

    double output = Math.tanh(rawOutput);

    return new ForwardResult(hidden, hidden2, rawOutput, output);
  }

  private static void backward(
      NNUEWeights weights,
      Gradients gradients,
      double[] features,
      ForwardResult forward,
      double target) {

    /*
     * MSE:
     *
     * (output - target)^2
     */
    double outputError = 2.0 * (forward.output - target);

    /*
     * tanh derivative:
     *
     * 1 - tanh(x)^2
     */
    double tanhDerivative = 1.0 - forward.output * forward.output;

    double rawOutputGradient = outputError * tanhDerivative;

    gradients.outputBias += rawOutputGradient;

    /*
     * Output weights.
     */
    double[] hidden2Gradient = new double[NNUEWeights.SECOND_HIDDEN_SIZE];

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

      gradients.outputWeights[h2] += rawOutputGradient * forward.hidden2[h2];

      hidden2Gradient[h2] = rawOutputGradient * weights.outputWeights[h2];
    }

    /*
     * Second hidden layer.
     */
    double[] hiddenGradient = new double[NNUEWeights.HIDDEN_SIZE];

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

      double gradient = hidden2Gradient[h2];

      /*
       * ReLU derivative.
       */
      if (forward.hidden2[h2] <= 0.0) {
        gradient = 0.0;
      }

      gradients.secondHiddenBias[h2] += gradient;

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

        gradients.hiddenWeights[h][h2] += gradient * forward.hidden[h];

        hiddenGradient[h] += gradient * weights.hiddenWeights[h][h2];
      }
    }

    /*
     * First hidden layer.
     */
    for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

      double gradient = hiddenGradient[h];

      /*
       * ReLU derivative.
       */
      if (forward.hidden[h] <= 0.0) {
        gradient = 0.0;
      }

      gradients.hiddenBias[h] += gradient;

      /*
       * Only update weights corresponding
       * to active features.
       */
      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++) {

        if (features[i] == 0.0) {
          continue;
        }

        gradients.inputWeights[i][h] += gradient * features[i];
      }
    }
  }

  private static double relu(double value) {

    return Math.max(0.0, value);
  }

  private record TrainingExample(Board board, double evaluation) {}

  private record ForwardResult(
      double[] hidden, double[] hidden2, double rawOutput, double output) {}

  private static class Gradients {

    double[][] inputWeights;

    double[] hiddenBias;

    double[][] hiddenWeights;

    double[] secondHiddenBias;

    double[] outputWeights;

    double outputBias;

    Gradients() {

      inputWeights = new double[NNUEWeights.INPUT_SIZE][NNUEWeights.HIDDEN_SIZE];

      hiddenBias = new double[NNUEWeights.HIDDEN_SIZE];

      hiddenWeights = new double[NNUEWeights.HIDDEN_SIZE][NNUEWeights.SECOND_HIDDEN_SIZE];

      secondHiddenBias = new double[NNUEWeights.SECOND_HIDDEN_SIZE];

      outputWeights = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    }

    void scale(double factor) {

      for (int i = 0; i < inputWeights.length; i++) {

        for (int h = 0; h < inputWeights[i].length; h++) {

          inputWeights[i][h] *= factor;
        }
      }

      for (int h = 0; h < hiddenBias.length; h++) {

        hiddenBias[h] *= factor;
      }

      for (int h = 0; h < hiddenWeights.length; h++) {

        for (int h2 = 0; h2 < hiddenWeights[h].length; h2++) {

          hiddenWeights[h][h2] *= factor;
        }
      }

      for (int h2 = 0; h2 < secondHiddenBias.length; h2++) {

        secondHiddenBias[h2] *= factor;
      }

      for (int h2 = 0; h2 < outputWeights.length; h2++) {

        outputWeights[h2] *= factor;
      }

      outputBias *= factor;
    }

    void clip(double limit) {

      for (int i = 0; i < inputWeights.length; i++) {

        for (int h = 0; h < inputWeights[i].length; h++) {

          inputWeights[i][h] = clamp(inputWeights[i][h], -limit, limit);
        }
      }

      for (int h = 0; h < hiddenBias.length; h++) {

        hiddenBias[h] = clamp(hiddenBias[h], -limit, limit);
      }

      for (int h = 0; h < hiddenWeights.length; h++) {

        for (int h2 = 0; h2 < hiddenWeights[h].length; h2++) {

          hiddenWeights[h][h2] = clamp(hiddenWeights[h][h2], -limit, limit);
        }
      }

      for (int h2 = 0; h2 < secondHiddenBias.length; h2++) {

        secondHiddenBias[h2] = clamp(secondHiddenBias[h2], -limit, limit);
      }

      for (int h2 = 0; h2 < outputWeights.length; h2++) {

        outputWeights[h2] = clamp(outputWeights[h2], -limit, limit);
      }

      outputBias = clamp(outputBias, -limit, limit);
    }

    void apply(NNUEWeights weights, double learningRate) {

      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++) {

        for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

          weights.inputWeights[i][h] -= learningRate * inputWeights[i][h];
        }
      }

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

        weights.hiddenBias[h] -= learningRate * hiddenBias[h];
      }

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {

        for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

          weights.hiddenWeights[h][h2] -= learningRate * hiddenWeights[h][h2];
        }
      }

      for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {

        weights.secondHiddenBias[h2] -= learningRate * secondHiddenBias[h2];

        weights.outputWeights[h2] -= learningRate * outputWeights[h2];
      }

      weights.outputBias -= learningRate * outputBias;
    }

    private static double clamp(double value, double min, double max) {

      return Math.max(min, Math.min(max, value));
    }
  }
}
