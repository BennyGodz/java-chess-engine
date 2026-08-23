package chess.engine.training;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Alternates self-play generation and NNUE training for a fixed duration. */
public final class TrainingPipeline {

  private static final double DEFAULT_RUNTIME_HOURS = 12.0;
  private static final int DEFAULT_GAMES_PER_ITERATION = SelfPlayGenerator.DEFAULT_GAMES;
  private static final long DEFAULT_TIME_PER_MOVE_MS = SelfPlayGenerator.DEFAULT_TIME_PER_MOVE_MS;
  private static final int DEFAULT_EPOCHS = 16;
  private static final int MIN_GAMES_PER_ITERATION = 100;
  private static final String BEST_WEIGHTS_FILE = "nnue_weights_best.bin";
  private static final String WORKING_WEIGHTS_FILE = "nnue_weights.bin";
  private static final String TRAINING_HISTORY_FILE = "training_history.log";
  private static final String TRAINING_STATE_FILE = "training_state.txt";

  public static void main(String[] args) {
    double runtimeHours = args.length > 0 ? Double.parseDouble(args[0]) : DEFAULT_RUNTIME_HOURS;
    int gamesPerIteration =
        args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_GAMES_PER_ITERATION;
    long timePerMoveMs = args.length > 2 ? Long.parseLong(args[2]) : DEFAULT_TIME_PER_MOVE_MS;
    int threads =
        args.length > 3
            ? Integer.parseInt(args[3])
            : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    int epochs = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_EPOCHS;

    validateArguments(runtimeHours, gamesPerIteration, timePerMoveMs, threads, epochs);

    final long pipelineStartNanos = System.nanoTime();
    final long pipelineDeadlineNanos = pipelineStartNanos + hoursToNanos(runtimeHours);

    final long baseSeed = System.nanoTime();

    printConfiguration(runtimeHours, gamesPerIteration, timePerMoveMs, threads, epochs);
    System.out.println();
    System.out.printf("Pipeline runtime: %.2f hours%n", runtimeHours);

    int completedIterations = 0;

    while (System.nanoTime() < pipelineDeadlineNanos) {
      completedIterations++;

      System.out.println();
      System.out.println("========================================");
      System.out.println("ITERATION " + completedIterations);
      System.out.println("========================================");

      printRemainingTime(pipelineStartNanos, pipelineDeadlineNanos);

      if (!ensureNetworkExists()) {
        System.err.println("WARNING: no NNUE checkpoint was found.");
        System.err.println("Self play will use the engine's normal initialization.");
      } else {
        System.out.println("Self play will use the protected best network when available.");
        if (Files.isRegularFile(Path.of(BEST_WEIGHTS_FILE))) {
          System.out.println("  best checkpoint: " + BEST_WEIGHTS_FILE);
        }
        if (Files.isRegularFile(Path.of(WORKING_WEIGHTS_FILE))) {
          System.out.println("  working checkpoint: " + WORKING_WEIGHTS_FILE);
        }
      }

      try {
        long generationStart = System.nanoTime();
        long iterationSeed = mixSeed(baseSeed, completedIterations);
        System.out.println();
        System.out.println("Starting self play with seed " + iterationSeed);

        SelfPlayGenerator generator =
            new SelfPlayGenerator(gamesPerIteration, timePerMoveMs, iterationSeed, threads);
        generator.generate();

        long generationSeconds = (System.nanoTime() - generationStart) / 1_000_000_000L;
        System.out.println();
        System.out.printf(
            "Self play iteration %d complete in %d seconds.%n",
            completedIterations, generationSeconds);
      } catch (IOException | RuntimeException e) {
        System.err.println();
        System.err.println("Self play failed: " + e.getMessage());
        e.printStackTrace(System.err);
        break;
      }

      try {
        long trainingStart = System.nanoTime();
        System.out.println();
        System.out.println("Starting GameTrainer...");

        new GameTrainer(epochs, threads).run();

        long trainingSeconds = (System.nanoTime() - trainingStart) / 1_000_000_000L;
        System.out.println();
        System.out.printf(
            "Training iteration %d complete in %d seconds.%n",
            completedIterations, trainingSeconds);
      } catch (IOException | RuntimeException e) {
        System.err.println();
        System.err.println("Training failed: " + e.getMessage());
        e.printStackTrace(System.err);
        break;
      }

      double currentBestMse = readCurrentBestMse();
      System.out.println();
      System.out.println("Iteration " + completedIterations + " validation summary:");
      if (Double.isFinite(currentBestMse)) {
        System.out.printf("  Best checkpoint MSE on the current holdout: %.6f%n", currentBestMse);
        System.out.println(
            "  See the GameTrainer summary above for the before/after comparison on this"
                + " holdout.");
      } else {
        System.out.println("  No readable validation result found.");
      }

      if (Files.isRegularFile(Path.of(BEST_WEIGHTS_FILE))) {
        System.out.println("  Best checkpoint verified: " + BEST_WEIGHTS_FILE);
      } else {
        System.err.println("  WARNING: best checkpoint is missing: " + BEST_WEIGHTS_FILE);
      }

      printRemainingTime(pipelineStartNanos, pipelineDeadlineNanos);

      if (System.nanoTime() >= pipelineDeadlineNanos) {
        System.out.println();
        System.out.println("Training window reached.");
        break;
      }

      System.out.println();
      System.out.println("Beginning next self play iteration...");
    }

    long elapsedNanos = System.nanoTime() - pipelineStartNanos;
    double elapsedHours = elapsedNanos / 3_600_000_000_000.0;

    System.out.println();
    System.out.println("========================================");
    System.out.println("OVERNIGHT TRAINING COMPLETE");
    System.out.println("========================================");
    System.out.printf("Elapsed time       : %.2f hours%n", elapsedHours);
    System.out.println("Completed iterations: " + completedIterations);
    System.out.println("Best checkpoint    : " + BEST_WEIGHTS_FILE);
    System.out.println("Working weights    : " + WORKING_WEIGHTS_FILE);

    double finalBest = readCurrentBestMse();
    if (Double.isFinite(finalBest)) {
      System.out.printf("Latest holdout MSE : %.6f%n", finalBest);
    } else {
      System.out.println("Best validation MSE: unavailable");
    }

    System.out.println();
    System.out.println("The protected best checkpoint was left untouched");
    System.out.println("unless GameTrainer determined that validation");
    System.out.println("performance actually improved.");
  }

  private static void validateArguments(
      double runtimeHours, int gamesPerIteration, long timePerMoveMs, int threads, int epochs) {
    if (!Double.isFinite(runtimeHours) || runtimeHours <= 0.0) {
      throw new IllegalArgumentException("runtimeHours must be > 0");
    }
    if (runtimeHours > 168.0) {
      throw new IllegalArgumentException("runtimeHours must be <= 168 hours");
    }
    if (gamesPerIteration < MIN_GAMES_PER_ITERATION) {
      throw new IllegalArgumentException(
          "gamesPerIteration must be at least " + MIN_GAMES_PER_ITERATION);
    }
    if (timePerMoveMs <= 0) {
      throw new IllegalArgumentException("timePerMoveMs must be > 0");
    }
    if (threads <= 0) {
      throw new IllegalArgumentException("threads must be > 0");
    }
    if (epochs <= 0) {
      throw new IllegalArgumentException("epochs must be > 0");
    }
  }

  private static long hoursToNanos(double hours) {
    double nanos = hours * 3_600_000_000_000.0;
    if (nanos >= Long.MAX_VALUE) {
      throw new IllegalArgumentException("Runtime is too large.");
    }
    return Math.max(1L, (long) nanos);
  }

  private static void printConfiguration(
      double runtimeHours, int gamesPerIteration, long timePerMoveMs, int threads, int epochs) {
    System.out.println();
    System.out.println("==============================================");
    System.out.println("        NNUE OVERNIGHT TRAINING PIPELINE");
    System.out.println("==============================================");
    System.out.printf("Runtime                 : %.2f hours%n", runtimeHours);
    System.out.println("Games / iteration       : " + gamesPerIteration);
    System.out.println("Time / move             : " + timePerMoveMs + " ms");
    System.out.println("Training threads        : " + threads);
    System.out.println("Maximum epochs          : " + epochs);
    System.out.println("Best network            : " + BEST_WEIGHTS_FILE);
    System.out.println("Working network         : " + WORKING_WEIGHTS_FILE);
    System.out.println("==============================================");
  }

  private static void printRemainingTime(long startNanos, long deadlineNanos) {
    long now = System.nanoTime();
    long remaining = Math.max(0, deadlineNanos - now);
    long elapsed = now - startNanos;
    double elapsedHours = elapsed / 3_600_000_000_000.0;
    double remainingHours = remaining / 3_600_000_000_000.0;
    System.out.printf("Elapsed: %.2fh | Remaining: %.2fh%n", elapsedHours, remainingHours);
  }

  private static long mixSeed(long baseSeed, int iteration) {
    long z = baseSeed + 0x9E3779B97F4A7C15L * iteration;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static boolean ensureNetworkExists() {
    return Files.isRegularFile(Path.of(BEST_WEIGHTS_FILE))
        || Files.isRegularFile(Path.of(WORKING_WEIGHTS_FILE));
  }

  private static double readCurrentBestMse() {
    Path state = Path.of(TRAINING_STATE_FILE);
    if (Files.isRegularFile(state)) {
      Properties properties = new Properties();
      try (InputStream input = Files.newInputStream(state)) {
        properties.load(input);
        double value = Double.parseDouble(properties.getProperty("best.validation.mse", "NaN"));
        if (Double.isFinite(value)) return value;
      } catch (IOException | NumberFormatException e) {
        System.err.println("Could not read training state: " + e.getMessage());
      }
    }

    Path history = Path.of(TRAINING_HISTORY_FILE);
    if (!Files.isRegularFile(history)) {
      return Double.NaN;
    }

    try {
      List<String> lines = Files.readAllLines(history);
      for (int i = lines.size() - 1; i >= 0; i--) {
        String line = lines.get(i);
        double value =
            line.contains("RUN DONE")
                ? parseMetric(line, "allTime ")
                : parseMetric(line, "valMse ");
        if (Double.isFinite(value)) return value;
      }
    } catch (IOException e) {
      System.err.println("Could not read training history: " + e.getMessage());
      return Double.NaN;
    }

    return Double.NaN;
  }

  static double parseMetric(String line, String marker) {
    int markerIndex = line.indexOf(marker);
    if (markerIndex < 0) return Double.NaN;

    int start = markerIndex + marker.length();
    int end = start;
    while (end < line.length()) {
      char c = line.charAt(end);
      if (!(Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+')) {
        break;
      }
      end++;
    }
    if (end == start) return Double.NaN;

    try {
      return Double.parseDouble(line.substring(start, end));
    } catch (NumberFormatException ignored) {
      return Double.NaN;
    }
  }

  private TrainingPipeline() {}
}
