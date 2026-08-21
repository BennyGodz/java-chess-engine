package chess.engine.evaluation.nnue;

import chess.board.Board;
import chess.board.Position;
import chess.pieces.*;
import java.io.*;
import java.util.*;

public class NNUETrainer {

  private static final String TRAINING_FILE = "training_data.txt";
  private static final String OUTPUT_FILE = "nnue_weights.bin";
  private static final String BEST_OUTPUT_FILE = "nnue_weights_best.bin";
  private static final int EPOCHS = 5;  // Reduced from 8 for very fast training
  private static final int BATCH_SIZE = 4096;  // Largest batch for maximum throughput
  private static final double LEARNING_RATE = 0.005;  // Higher LR for fast initial learning
  private static final double TARGET_SCALE = 400.0;
  private static final double EVALUATION_CLAMP = 10.0;
  private static final double GRADIENT_CLIP = 2.0;  // Slightly higher clip
  private static final double VALIDATION_RATIO = 0.10;
  private static final double LR_DECAY = 0.1;  // More aggressive decay
  private static final int LR_PATIENCE = 1;  // Fast LR reduction
  private static final int PATIENCE_EPOCHS = 1;  // Early stop after 1 epoch no improvement

  public static void main(String[] args) {
    try {
      System.out.println("Loading training data...");
      List<TrainingExample> examples = loadData(TRAINING_FILE);
      System.out.println("Loaded " + examples.size() + " positions.");
      if (examples.isEmpty()) throw new IllegalStateException("No training data found.");

      Collections.shuffle(examples, new Random(12345));
      int validationSize = Math.max(1, (int)(examples.size() * VALIDATION_RATIO));
      int trainingSize = examples.size() - validationSize;
      List<TrainingExample> training = new ArrayList<>(examples.subList(0, trainingSize));
      List<TrainingExample> validation = new ArrayList<>(examples.subList(trainingSize, examples.size()));

      System.out.println("Training positions: " + training.size());
      System.out.println("Validation positions: " + validation.size());

      NNUEWeights weights = NNUEWeights.random();
      train(weights, training, validation);
      weights.save(new File(OUTPUT_FILE));
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
        if (line.isEmpty()) continue;

        // Support both old format: FEN|evaluation
        // And new format: FEN|move_san (move that led to this position)
        int separator = line.lastIndexOf('|');
        if (separator <= 0) continue;

        String fenPart = line.substring(0, separator);
        String rest = line.substring(separator + 1);

        double evaluation;
        try {
          evaluation = Double.parseDouble(rest);
        } catch (NumberFormatException e) {
          // New format: rest is move_san, not a number
          // For old format compatibility, skip if not a number
          continue;
        }

        if (!Double.isFinite(evaluation)) continue;
        evaluation = Math.max(-EVALUATION_CLAMP, Math.min(EVALUATION_CLAMP, evaluation));
        Board board = new Board();
        try {
          board.loadFEN(fenPart);
        } catch (Exception ignored) {
          continue;
        }
        data.add(new TrainingExample(board, evaluation));
      }
    }

    return data;
  }

  private static void train(NNUEWeights weights, List<TrainingExample> training, List<TrainingExample> validation) {
    NNUEFeatureExtractor extractor = new NNUEFeatureExtractor();
    Random random = new Random(12345);
    double bestLoss = Double.POSITIVE_INFINITY;
    double patienceCounter = 0;
    double currentLearningRate = LEARNING_RATE;

    for (int epoch = 1; epoch <= EPOCHS; epoch++) {
      Collections.shuffle(training, random);
      double totalLoss = 0;
      int processed = 0;
      double totalMAE = 0;

      long epochStart = System.nanoTime();

      for (int start = 0; start < training.size(); start += BATCH_SIZE) {
        int end = Math.min(start + BATCH_SIZE, training.size());
        Gradients gradients = new Gradients();
        double batchLoss = 0;

        for (int index = start; index < end; index++) {
          TrainingExample e = training.get(index);
          double[] features = extractor.extract(e.board);
          ForwardResult forward = forward(weights, features);
          double target = normalizeTarget(e.evaluation);
          double error = forward.output - target;
          batchLoss += error * error;
          totalMAE += Math.abs(error);
          backward(weights, gradients, features, forward, target);
        }

        int size = end - start;
        gradients.scale(1.0 / size);
        gradients.clip(GRADIENT_CLIP);
        gradients.apply(weights, currentLearningRate);
        totalLoss += batchLoss;
        processed += size;
      }

      double trainingLoss = totalLoss / training.size();
      double trainingMAE = totalMAE / processed;
      double validationLoss = calculateLoss(weights, validation, extractor);
      double validationMAE = calculateMAE(weights, validation, extractor);

      long epochTimeMs = (System.nanoTime() - epochStart) / 1_000_000;
      if (epoch % 5 == 1) {
        System.out.printf("Epoch %d/%d | train %.6f (MAE: %.6f) | validation %.6f (MAE: %.6f) | lr %.6f | positions %d | time %dms%n",
                epoch, EPOCHS, trainingLoss, trainingMAE, validationLoss, validationMAE, currentLearningRate, processed, epochTimeMs);
      }

      if (validationLoss < bestLoss) {
        bestLoss = validationLoss;
        try {
          weights.save(new File(BEST_OUTPUT_FILE));
          System.out.println("  New best network saved.");
        } catch (IOException e) {
          throw new RuntimeException("Could not save best weights.", e);
        }
        patienceCounter = 0;
      } else {
        patienceCounter++;
        if (patienceCounter >= PATIENCE_EPOCHS) {
          System.out.println("  Early stopping triggered.");
          break;
        }
        if (patienceCounter % LR_PATIENCE == 0) {
          currentLearningRate *= LR_DECAY;
          System.out.println("  Learning rate reduced to " + currentLearningRate);
        }
      }
    }
  }

  private static double calculateLoss(NNUEWeights weights, List<TrainingExample> data, NNUEFeatureExtractor extractor) {
    double loss = 0;

    for (TrainingExample e : data) {
      double[] features = extractor.extract(e.board);
      ForwardResult f = forward(weights, features);
      double error = f.output - normalizeTarget(e.evaluation);
      loss += error * error;
    }

    return loss / data.size();
  }

  private static double calculateMAE(NNUEWeights weights, List<TrainingExample> data, NNUEFeatureExtractor extractor) {
    double totalAbsError = 0;

    for (TrainingExample e : data) {
      double[] features = extractor.extract(e.board);
      ForwardResult f = forward(weights, features);
      double error = f.output - normalizeTarget(e.evaluation);
      totalAbsError += Math.abs(error);
    }

    return totalAbsError / data.size();
  }

  private static double normalizeTarget(double evaluation) {
    return Math.tanh(evaluation * 100.0 / TARGET_SCALE);
  }

  private static ForwardResult forward(NNUEWeights w, double[] features) {
    double[] hidden = new double[NNUEWeights.HIDDEN_SIZE];

    for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {
      double sum = w.hiddenBias[h];
      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++)
        if (features[i] != 0) sum += features[i] * w.inputWeights[i][h];
      hidden[h] = relu(sum);
    }

    double[] hidden2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];

    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {
      double sum = w.secondHiddenBias[h2];
      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++)
        sum += hidden[h] * w.hiddenWeights[h][h2];
      hidden2[h2] = relu(sum);
    }

    double[] hidden3 = new double[NNUEWeights.THIRD_HIDDEN_SIZE];

    for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
      double sum = w.thirdHiddenBias[h3];
      for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++)
        sum += hidden2[h2] * w.secondHiddenWeights[h2][h3];
      hidden3[h3] = relu(sum);
    }

    double raw = w.outputBias;
    for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++)
      raw += hidden3[h3] * w.outputWeights[h3];

    return new ForwardResult(hidden, hidden2, hidden3, raw, Math.tanh(raw));
  }

  private static void backward(NNUEWeights w, Gradients g, double[] features, ForwardResult f, double target) {
    double outputError = 2.0 * (f.output - target);
    double d_output = outputError * (1.0 - f.output * f.output);

    // --- Output -> thirdHidden layer ---
    for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
      g.outputWeights[h3] += d_output * f.hidden3[h3];
    }
    g.outputBias += d_output;

    // --- ThirdHidden -> secondHidden (backprop through secondHiddenWeights) ---
    // Accumulate gradients for secondHiddenWeights and secondHiddenBias
    for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {
      for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
        if (f.hidden3[h3] > 0.0) {
          g.secondHiddenWeights[h2][h3] += d_output * f.hidden2[h2];
        }
      }
      // Compute dloss/dsecondHiddenBias[h2]
      double dsecondHiddenBias = 0.0;
      for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
        if (f.hidden3[h3] > 0.0) {
          dsecondHiddenBias += d_output * w.outputWeights[h3] * w.secondHiddenWeights[h2][h3];
        }
      }
      // Multiply by ReLU' of hidden2 (hidden2[h2] > 0 ? 1.0 : 0.0)
      if (f.hidden2[h2] > 0.0) {
        g.secondHiddenBias[h2] += dsecondHiddenBias;
      }

      // Compute dloss/hiddenWeights[h][h2] contribution from third layer path
      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {
        if (f.hidden2[h2] > 0.0) {
          g.hiddenWeights[h][h2] += dsecondHiddenBias * f.hidden[h];
        }
      }
    }

    // --- SecondHidden -> hidden layer ---
    double[] hiddenGradient = new double[NNUEWeights.HIDDEN_SIZE];

    for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++) {
      double dhidden = 0.0;
      for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {
        if (f.hidden2[h2] > 0.0) {
          // contribution from secondHiddenBias path
          double dsecond = 0.0;
          for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
            if (f.hidden3[h3] > 0.0) {
              dsecond += d_output * w.outputWeights[h3] * w.secondHiddenWeights[h2][h3];
            }
          }
          if (f.hidden2[h2] > 0.0) {
            dhidden += dsecond * w.hiddenWeights[h][h2];
          }
        }
      }
      // Also add direct contribution from secondHiddenBias that was already accumulated
      // Actually the hiddenWeights gradient was already added above in the loop
      // Now compute dloss/dhiddenBias[h]
      g.hiddenBias[h] += (f.hidden[h] > 0.0 ? dhidden : 0.0);

      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++)
        if (features[i] != 0.0) g.inputWeights[i][h] += (f.hidden[h] > 0.0 ? dhidden : 0.0) * features[i];
    }
  }

  private static double relu(double x) {
    return Math.max(0, x);
  }

  private record TrainingExample(Board board, double evaluation) {}
  private record ForwardResult(double[] hidden, double[] hidden2, double[] hidden3, double rawOutput, double output) {}

  private static class Gradients {
    double[][] inputWeights = new double[NNUEWeights.INPUT_SIZE][NNUEWeights.HIDDEN_SIZE];
    double[] hiddenBias = new double[NNUEWeights.HIDDEN_SIZE];
    double[][] hiddenWeights = new double[NNUEWeights.HIDDEN_SIZE][NNUEWeights.SECOND_HIDDEN_SIZE];
    double[] secondHiddenBias = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    double[] thirdHiddenBias = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    double[][] secondHiddenWeights = new double[NNUEWeights.SECOND_HIDDEN_SIZE][NNUEWeights.THIRD_HIDDEN_SIZE];
    double[] outputWeights = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    double outputBias;

    void scale(double factor) {
      for (int i = 0; i < inputWeights.length; i++)
        for (int h = 0; h < inputWeights[i].length; h++)
          inputWeights[i][h] *= factor;

      for (int h = 0; h < hiddenBias.length; h++) hiddenBias[h] *= factor;

      for (int h = 0; h < hiddenWeights.length; h++)
        for (int h2 = 0; h2 < hiddenWeights[h].length; h2++)
          hiddenWeights[h][h2] *= factor;

      for (int h2 = 0; h2 < secondHiddenBias.length; h2++) {
        secondHiddenBias[h2] *= factor;
      }

      for (int h3 = 0; h3 < thirdHiddenBias.length; h3++) {
        thirdHiddenBias[h3] *= factor;
      }

      for (int h2 = 0; h2 < NNUEWeights.THIRD_HIDDEN_SIZE; h2++) {
        outputWeights[h2] *= factor;
      }

      outputBias *= factor;
    }

    void clip(double limit) {
      for (int i = 0; i < inputWeights.length; i++)
        for (int h = 0; h < inputWeights[i].length; h++)
          inputWeights[i][h] = clamp(inputWeights[i][h], -limit, limit);

      for (int h = 0; h < hiddenBias.length; h++)
        hiddenBias[h] = clamp(hiddenBias[h], -limit, limit);

      for (int h = 0; h < hiddenWeights.length; h++)
        for (int h2 = 0; h2 < hiddenWeights[h].length; h2++)
          hiddenWeights[h][h2] = clamp(hiddenWeights[h][h2], -limit, limit);

      for (int h2 = 0; h2 < secondHiddenBias.length; h2++) {
        secondHiddenBias[h2] = clamp(secondHiddenBias[h2], -limit, limit);
      }

      for (int h3 = 0; h3 < thirdHiddenBias.length; h3++) {
        thirdHiddenBias[h3] = clamp(thirdHiddenBias[h3], -limit, limit);
      }

      for (int h2 = 0; h2 < NNUEWeights.THIRD_HIDDEN_SIZE; h2++) {
        outputWeights[h2] = clamp(outputWeights[h2], -limit, limit);
      }

      outputBias = clamp(outputBias, -limit, limit);
    }

    void apply(NNUEWeights w, double rate) {
      for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++)
        for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++)
          w.inputWeights[i][h] -= rate * inputWeights[i][h];

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++)
        w.hiddenBias[h] -= rate * hiddenBias[h];

      for (int h = 0; h < NNUEWeights.HIDDEN_SIZE; h++)
        for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++)
          w.hiddenWeights[h][h2] -= rate * hiddenWeights[h][h2];

      for (int h2 = 0; h2 < NNUEWeights.SECOND_HIDDEN_SIZE; h2++) {
        w.secondHiddenBias[h2] -= rate * secondHiddenBias[h2];
        for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
          w.secondHiddenWeights[h2][h3] -= rate * secondHiddenWeights[h2][h3];
        }
      }

      for (int h3 = 0; h3 < NNUEWeights.THIRD_HIDDEN_SIZE; h3++) {
        w.thirdHiddenBias[h3] -= rate * thirdHiddenBias[h3];
      }

      for (int h2 = 0; h2 < NNUEWeights.THIRD_HIDDEN_SIZE; h2++) {
        w.outputWeights[h2] -= rate * outputWeights[h2];
      }

      w.outputBias -= rate * outputBias;
    }

    private static double clamp(double x, double min, double max) {
      return Math.max(min, Math.min(max, x));
    }
  }
}