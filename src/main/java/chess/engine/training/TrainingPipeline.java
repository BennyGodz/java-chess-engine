package chess.engine.training;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Long running self improvement loop for the chess NNUE.
 *
 * <p>The pipeline repeatedly:
 *
 * <p>1. Generates fresh self play games using the current best network.
 * 2. Trains the NNUE on the generated games.
 * 3. Uses GameTrainer's fixed validation set to determine whether the network improved.
 * 4. Preserves the all time best checkpoint.
 * 5. Starts another self play/training iteration.
 *
 * <p>Unlike the old fixed iteration approach, this version runs for a fixed wall clock duration.
 * The default is 8 hours, making it suitable for overnight training.
 *
 * <p>The timer covers the entire pipeline, including self play and training.
 *
 * <p>Usage:
 *
 * <pre>
 * TrainingPipeline
 * TrainingPipeline [hours]
 * TrainingPipeline [hours] [gamesPerIteration]
 * TrainingPipeline [hours] [gamesPerIteration] [timePerMoveMs]
 * TrainingPipeline [hours] [gamesPerIteration] [timePerMoveMs] [threads]
 * TrainingPipeline [hours] [gamesPerIteration] [timePerMoveMs] [threads] [epochs]
 * </pre>
 *
 * <p>Example:
 *
 * <pre>
 * TrainingPipeline 8 256 100 8 16
 * </pre>
 */
public final class TrainingPipeline {

  /**
   * Maximum wall clock time for one overnight run.
   *
   * <p>Default: 8 hours.
   */
  private static final double DEFAULT_RUNTIME_HOURS = 8.0;

  /**
   * Number of games generated before each training stage.
   */
  private static final int DEFAULT_GAMES_PER_ITERATION = SelfPlayGenerator.DEFAULT_GAMES;

  /**
   * Time allowed per move during self play.
   */
  private static final long DEFAULT_TIME_PER_MOVE_MS =
          SelfPlayGenerator.DEFAULT_TIME_PER_MOVE_MS;

  /**
   * Maximum number of epochs allowed during each GameTrainer invocation.
   *
   * <p>GameTrainer has its own validation based early stopping, so this is an upper bound rather
   * than a requirement to train for all epochs. Kept small deliberately: each pipeline iteration
   * adds fresh self-play games, and many short training stages over growing data generalize better
   * than one long stage that memorises a mostly-static dataset.
   */
  private static final int DEFAULT_EPOCHS = 16;

  /**
   * Minimum number of games allowed in each self play batch.
   */
  private static final int MIN_GAMES_PER_ITERATION = 100;

  /**
   * Protected best network.
   *
   * <p>This file should only be replaced when GameTrainer determines that validation performance
   * actually improved.
   */
  private static final String BEST_WEIGHTS_FILE = "nnue_weights_best.bin";

  /**
   * Working network produced during training.
   */
  private static final String WORKING_WEIGHTS_FILE = "nnue_weights.bin";

  /**
   * Training history written by GameTrainer.
   */
  private static final String TRAINING_HISTORY_FILE = "training_history.log";

  public static void main(String[] args) {

    /*
     * Argument order:
     *
     * [hours]
     * [gamesPerIteration]
     * [timePerMoveMs]
     * [threads]
     * [epochs]
     */
    double runtimeHours =
            args.length > 0 ? Double.parseDouble(args[0]) : DEFAULT_RUNTIME_HOURS;

    int gamesPerIteration =
            args.length > 1
                    ? Integer.parseInt(args[1])
                    : DEFAULT_GAMES_PER_ITERATION;

    long timePerMoveMs =
            args.length > 2
                    ? Long.parseLong(args[2])
                    : DEFAULT_TIME_PER_MOVE_MS;

    int threads =
            args.length > 3
                    ? Integer.parseInt(args[3])
                    : Math.max(
                    1,
                    Runtime.getRuntime().availableProcessors() / 2);

    int epochs =
            args.length > 4
                    ? Integer.parseInt(args[4])
                    : DEFAULT_EPOCHS;

    validateArguments(
            runtimeHours,
            gamesPerIteration,
            timePerMoveMs,
            threads,
            epochs);

    /*
     * Convert hours into nanoseconds once.
     *
     * System.nanoTime() is appropriate for measuring elapsed time because it is monotonic and
     * does not jump if the system clock changes.
     */
    final long runtimeNanos =
            hoursToNanos(runtimeHours);

    final long pipelineStartNanos =
            System.nanoTime();

    final long pipelineDeadlineNanos =
            pipelineStartNanos + runtimeNanos;

    /*
     * One root seed for the complete overnight run.
     *
     * Every iteration gets a different mixed seed so that self play does not repeatedly generate
     * the same games.
     */
    final long baseSeed = System.nanoTime();

    printConfiguration(
            runtimeHours,
            gamesPerIteration,
            timePerMoveMs,
            threads,
            epochs);

    System.out.println();
    System.out.printf(
            "Pipeline runtime: %.2f hours%n",
            runtimeHours);

    double previousBestMse =
            readBestMseFromHistory();

    int completedIterations = 0;

    while (true) {

      /*
       * Never start another complete iteration if the requested runtime has already elapsed.
       */
      if (System.nanoTime() >= pipelineDeadlineNanos) {
        break;
      }

      completedIterations++;

      System.out.println();
      System.out.println("========================================");
      System.out.println(
              "ITERATION " + completedIterations);
      System.out.println("========================================");

      printRemainingTime(
              pipelineStartNanos,
              pipelineDeadlineNanos);

      /*
       * Verify that a trained network exists before self play.
       *
       * The first run can still fall back to the project's normal initialization if neither
       * checkpoint exists.
       */
      if (!ensureNetworkExists()) {

        System.err.println(
                "WARNING: no NNUE checkpoint was found.");

        System.err.println(
                "Self play will use the engine's normal initialization.");
      } else {

        System.out.println(
                "Self play will use the protected best network when available.");

        if (Files.isRegularFile(Path.of(BEST_WEIGHTS_FILE))) {
          System.out.println(
                  "  best checkpoint: "
                          + BEST_WEIGHTS_FILE);
        }

        if (Files.isRegularFile(Path.of(WORKING_WEIGHTS_FILE))) {
          System.out.println(
                  "  working checkpoint: "
                          + WORKING_WEIGHTS_FILE);
        }
      }

      /*
       * ============================================================
       * SELF PLAY
       * ============================================================
       */

      /*
       * Before starting self play, check the deadline again.
       */
      if (System.nanoTime() >= pipelineDeadlineNanos) {
        break;
      }

      try {

        long generationStart =
                System.nanoTime();

        long iterationSeed =
                mixSeed(
                        baseSeed,
                        completedIterations);

        System.out.println();
        System.out.println(
                "Starting self play with seed "
                        + iterationSeed);

        SelfPlayGenerator generator =
                new SelfPlayGenerator(
                        gamesPerIteration,
                        timePerMoveMs,
                        iterationSeed,
                        threads);

        generator.generate();

        long generationSeconds =
                (System.nanoTime()
                        - generationStart)
                        / 1_000_000_000L;

        System.out.println();
        System.out.println(
                "Self play iteration "
                        + completedIterations
                        + " complete in "
                        + generationSeconds
                        + " seconds.");

      } catch (IOException e) {

        System.err.println();
        System.err.println(
                "Self play failed: "
                        + e.getMessage());

        e.printStackTrace(System.err);

        break;

      } catch (RuntimeException e) {

        System.err.println();
        System.err.println(
                "Self play failed: "
                        + e.getMessage());

        e.printStackTrace(System.err);

        break;
      }

      /*
       * ============================================================
       * TRAINING
       * ============================================================
       */

      /*
       * The GameTrainer invocation itself can take a substantial amount of time.
       *
       * We deliberately do not kill it when the deadline passes in the middle of training.
       * Interrupting training could leave a partially written network.
       *
       * Instead:
       *
       *   if training begins before the deadline,
       *   let that training stage finish,
       *   then stop before starting another iteration.
       */
      try {

        long trainingStart =
                System.nanoTime();

        System.out.println();
        System.out.println(
                "Starting GameTrainer...");

        new GameTrainer(epochs).run();

        long trainingSeconds =
                (System.nanoTime()
                        - trainingStart)
                        / 1_000_000_000L;

        System.out.println();
        System.out.println(
                "Training iteration "
                        + completedIterations
                        + " complete in "
                        + trainingSeconds
                        + " seconds.");

      } catch (IOException e) {

        System.err.println();
        System.err.println(
                "Training failed: "
                        + e.getMessage());

        e.printStackTrace(System.err);

        break;

      } catch (RuntimeException e) {

        System.err.println();
        System.err.println(
                "Training failed: "
                        + e.getMessage());

        e.printStackTrace(System.err);

        break;
      }

      /*
       * ============================================================
       * VALIDATION / ITERATION REPORT
       * ============================================================
       */

      double currentBestMse =
              readBestMseFromHistory();

      System.out.println();
      System.out.println(
              "Iteration "
                      + completedIterations
                      + " validation summary:");

      if (Double.isFinite(currentBestMse)) {

        if (!Double.isFinite(previousBestMse)) {

          System.out.printf(
                  "  First tracked best validation MSE: %.6f%n",
                  currentBestMse);

        } else if (
                currentBestMse
                        < previousBestMse - 1.0e-9) {

          System.out.printf(
                  "  VALIDATION IMPROVED: %.6f -> %.6f%n",
                  previousBestMse,
                  currentBestMse);

        } else {

          System.out.printf(
                  "  Validation best unchanged: %.6f%n",
                  currentBestMse);
        }

        /*
         * Since this is an all time best value, it should never increase.
         */
        previousBestMse =
                Double.isFinite(previousBestMse)
                        ? Math.min(
                        previousBestMse,
                        currentBestMse)
                        : currentBestMse;

      } else {

        System.out.println(
                "  No readable validation result found.");
      }

      /*
       * ============================================================
       * CHECKPOINT PROTECTION
       * ============================================================
       */

      if (Files.isRegularFile(
              Path.of(BEST_WEIGHTS_FILE))) {

        System.out.println(
                "  Best checkpoint verified: "
                        + BEST_WEIGHTS_FILE);

      } else {

        System.err.println(
                "  WARNING: best checkpoint is missing: "
                        + BEST_WEIGHTS_FILE);
      }

      printRemainingTime(
              pipelineStartNanos,
              pipelineDeadlineNanos);

      /*
       * If the eight hour window has expired while GameTrainer was running,
       * stop now rather than beginning another self play batch.
       */
      if (System.nanoTime()
              >= pipelineDeadlineNanos) {

        System.out.println();
        System.out.println(
                "Eight hour training window reached.");

        break;
      }

      System.out.println();
      System.out.println(
              "Beginning next self play iteration...");
    }

    /*
     * ================================================================
     * FINAL REPORT
     * ================================================================
     */

    long elapsedNanos =
            System.nanoTime()
                    - pipelineStartNanos;

    double elapsedHours =
            elapsedNanos / 3_600_000_000_000.0;

    System.out.println();
    System.out.println("========================================");
    System.out.println("OVERNIGHT TRAINING COMPLETE");
    System.out.println("========================================");

    System.out.printf(
            "Elapsed time       : %.2f hours%n",
            elapsedHours);

    System.out.println(
            "Completed iterations: "
                    + completedIterations);

    System.out.println(
            "Best checkpoint    : "
                    + BEST_WEIGHTS_FILE);

    System.out.println(
            "Working weights    : "
                    + WORKING_WEIGHTS_FILE);

    double finalBest =
            readBestMseFromHistory();

    if (Double.isFinite(finalBest)) {

      System.out.printf(
              "Best validation MSE: %.6f%n",
              finalBest);

    } else {

      System.out.println(
              "Best validation MSE: unavailable");
    }

    System.out.println();
    System.out.println(
            "The protected best checkpoint was left untouched");
    System.out.println(
            "unless GameTrainer determined that validation");
    System.out.println(
            "performance actually improved.");
  }

  /**
   * Validates command line configuration.
   */
  private static void validateArguments(
          double runtimeHours,
          int gamesPerIteration,
          long timePerMoveMs,
          int threads,
          int epochs) {

    if (!Double.isFinite(runtimeHours)
            || runtimeHours <= 0.0) {

      throw new IllegalArgumentException(
              "runtimeHours must be > 0");
    }

    if (runtimeHours > 168.0) {

      throw new IllegalArgumentException(
              "runtimeHours must be <= 168 hours");
    }

    if (gamesPerIteration
            < MIN_GAMES_PER_ITERATION) {

      throw new IllegalArgumentException(
              "gamesPerIteration must be at least "
                      + MIN_GAMES_PER_ITERATION);
    }

    if (timePerMoveMs <= 0) {

      throw new IllegalArgumentException(
              "timePerMoveMs must be > 0");
    }

    if (threads <= 0) {

      throw new IllegalArgumentException(
              "threads must be > 0");
    }

    if (epochs <= 0) {

      throw new IllegalArgumentException(
              "epochs must be > 0");
    }
  }

  /**
   * Converts hours into nanoseconds while checking for overflow.
   */
  private static long hoursToNanos(
          double hours) {

    double nanos =
            hours * 3_600_000_000_000.0;

    if (nanos >= Long.MAX_VALUE) {

      throw new IllegalArgumentException(
              "Runtime is too large.");
    }

    return Math.max(
            1L,
            (long) nanos);
  }

  /**
   * Prints the configuration at startup.
   */
  private static void printConfiguration(
          double runtimeHours,
          int gamesPerIteration,
          long timePerMoveMs,
          int threads,
          int epochs) {

    System.out.println();
    System.out.println(
            "==============================================");

    System.out.println(
            "        NNUE OVERNIGHT TRAINING PIPELINE");

    System.out.println(
            "==============================================");

    System.out.printf(
            "Runtime                 : %.2f hours%n",
            runtimeHours);

    System.out.println(
            "Games / iteration       : "
                    + gamesPerIteration);

    System.out.println(
            "Time / move             : "
                    + timePerMoveMs
                    + " ms");

    System.out.println(
            "Training threads        : "
                    + threads);

    System.out.println(
            "Maximum epochs          : "
                    + epochs);

    System.out.println(
            "Best network            : "
                    + BEST_WEIGHTS_FILE);

    System.out.println(
            "Working network         : "
                    + WORKING_WEIGHTS_FILE);

    System.out.println(
            "==============================================");
  }

  /**
   * Prints the amount of time remaining in the overnight run.
   */
  private static void printRemainingTime(
          long startNanos,
          long deadlineNanos) {

    long now =
            System.nanoTime();

    long remaining =
            deadlineNanos - now;

    long elapsed =
            now - startNanos;

    if (remaining < 0) {
      remaining = 0;
    }

    double elapsedHours =
            elapsed / 3_600_000_000_000.0;

    double remainingHours =
            remaining / 3_600_000_000_000.0;

    System.out.printf(
            "Elapsed: %.2fh | Remaining: %.2fh%n",
            elapsedHours,
            remainingHours);
  }

  /**
   * Generates a different high quality seed for each iteration.
   *
   * <p>This uses a SplitMix style mixing function rather than simply adding the iteration number
   * to the original seed.
   */
  private static long mixSeed(
          long baseSeed,
          int iteration) {

    long z =
            baseSeed
                    + 0x9E3779B97F4A7C15L
                    * iteration;

    z =
            (z ^ (z >>> 30))
                    * 0xBF58476D1CE4E5B9L;

    z =
            (z ^ (z >>> 27))
                    * 0x94D049BB133111EBL;

    return z ^ (z >>> 31);
  }

  /**
   * Checks whether a trained checkpoint already exists.
   */
  private static boolean ensureNetworkExists() {

    return Files.isRegularFile(
            Path.of(BEST_WEIGHTS_FILE))

            || Files.isRegularFile(
            Path.of(WORKING_WEIGHTS_FILE));
  }

  /**
   * Reads the best validation MSE recorded in training_history.log.
   *
   * <p>This is informational only. GameTrainer remains responsible for actual checkpoint selection.
   */
  private static double readBestMseFromHistory() {

    Path history =
            Path.of(TRAINING_HISTORY_FILE);

    if (!Files.isRegularFile(history)) {
      return Double.NaN;
    }

    double best =
            Double.POSITIVE_INFINITY;

    try {

      for (String line :
              Files.readAllLines(history)) {

        /*
         * GameTrainer records successful best checkpoints with "NEW BEST".
         */
        int marker =
                line.indexOf("NEW BEST");

        if (marker < 0) {
          continue;
        }

        /*
         * Expected format contains:
         *
         * valMse 0.xxxxx
         */
        int mseMarker =
                line.indexOf(
                        "valMse ",
                        marker);

        if (mseMarker < 0) {
          continue;
        }

        int start =
                mseMarker
                        + "valMse ".length();

        int end = start;

        while (end < line.length()) {

          char c =
                  line.charAt(end);

          if (!(Character.isDigit(c)
                  || c == '.'
                  || c == 'e'
                  || c == 'E'
                  || c == '-'
                  || c == '+')) {

            break;
          }

          end++;
        }

        if (end <= start) {
          continue;
        }

        try {

          double value =
                  Double.parseDouble(
                          line.substring(
                                  start,
                                  end));

          if (Double.isFinite(value)) {

            best =
                    Math.min(
                            best,
                            value);
          }

        } catch (NumberFormatException ignored) {
          /*
           * Ignore malformed historical entries.
           */
        }
      }

    } catch (IOException e) {

      System.err.println(
              "Could not read training history: "
                      + e.getMessage());

      return Double.NaN;
    }

    return Double.isFinite(best)
            ? best
            : Double.NaN;
  }

  private TrainingPipeline() {}
}