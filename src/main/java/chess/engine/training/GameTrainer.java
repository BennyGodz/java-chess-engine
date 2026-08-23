package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.nnue.NNUEFeatureExtractor;
import chess.engine.evaluation.nnue.NNUEWeights;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/** Trains the NNUE from PGN games and optional self-play evaluation comments. */
public final class GameTrainer {

  static final String GAMES_ROOT = System.getProperty("games.root", "games");

  private static final String OUTPUT_FILE = "nnue_weights.bin";

  private static final String BEST_WEIGHTS_FILE = "nnue_weights_best.bin";
  private static final String STATE_FILE = "training_state.txt";
  private static final String HISTORY_FILE = "training_history.log";
  private static final int VALIDATION_MAX_EXAMPLES = 200_000;

  private static final DateTimeFormatter TIMESTAMP_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final int DEFAULT_EPOCHS = 60;

  private static final int BATCH_SIZE = 1024;
  private static final double LEARNING_RATE = 1.5e-4;
  private static final double MIN_LEARNING_RATE = 2.0e-6;
  private static final double ADAM_BETA1 = 0.9;
  private static final double ADAM_BETA2 = 0.999;
  private static final double ADAM_EPS = 1.0e-8;

  private static final double WEIGHT_DECAY = 0.10;
  private static final double WARM_START_LR_FRACTION = 0.5;

  private static final double GRAD_CLIP_NORM = 2.0;
  private static final double LR_DECAY = 0.65;

  private static final int VALIDATE_EVERY_BATCHES = 50;
  private static final int LR_PATIENCE_EPOCHS = 2;
  private static final int EARLY_STOP_PATIENCE_EPOCHS = 8;
  private static final int MIN_LR_PATIENCE = 20;
  private static final int MIN_EARLY_STOP_PATIENCE = 80;
  private static final double DIVERGENCE_MARGIN_RELATIVE = 0.05;
  private static final double MAX_EVAL_MSE_REGRESSION = 0.02;
  private static final int DIVERGENCE_CHECKPOINTS = 4;
  private static final int DIVERGENCE_COOLDOWN = 12;
  private static final double MIN_VALIDATION_IMPROVEMENT = 2e-5;
  private static final double VALIDATION_RATIO = 0.10;

  private static final int MIN_PLY = 6;
  private static final long SEED = 12345L;
  private static final int LOG_EVERY_BATCHES = 50;

  private static final int POSITIONS_PER_GAME_CAP = 64;
  private static final int MAX_SELFPLAY_FILES = 64;
  private static final int MAX_EXAMPLES = 3_000_000;
  private static final int EVALUATION_EXAMPLES_PER_RESULT_ONLY = 4;
  private static final int MIN_SELFPLAY_EVALUATIONS = 8;
  private static final int MIN_SELFPLAY_EVALUATION_DEPTH = 4;
  private static final double MIN_SELFPLAY_EVALUATION_COVERAGE = 0.15;
  private static final double TEMPORAL_BASE_WEIGHT = 0.10;
  private static final double TEMPORAL_FUTURE_WEIGHT = 0.90;
  private static final double TEMPORAL_DECAY_PLIES = 36.0;
  private static final double EVAL_TARGET_SCALE_CP = 350.0;
  private static final double MAX_USABLE_EVAL_CP = 950.0;
  private static final double RESULT_MIX = 0.05;
  private static final double RESULT_ONLY_ROW_WEIGHT = 0.15;
  private static final double LABEL_SHRINKAGE_PRIOR = 0.05;
  private static final double DRAW_EXAMPLE_WEIGHT = 0.20;
  private static final double DROPOUT_RATE = 0.10;
  private static final int MAX_CHUNKS = 16;

  private final int epochs;
  private final Random random;
  private final int threads;

  public GameTrainer(int epochs) {
    this(epochs, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
  }

  public GameTrainer(int epochs, int threads) {
    this.epochs = Math.max(1, epochs);
    this.random = new Random(SEED);
    this.threads = Math.max(1, Math.min(MAX_CHUNKS, threads));
  }

  public static void main(String[] args) {
    int epochsArg = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_EPOCHS;
    try {
      new GameTrainer(epochsArg).run();
    } catch (IOException e) {
      System.err.println("Training failed: " + e.getMessage());
      System.exit(1);
    }
  }

  public void run() throws IOException {
    List<File> pgnFiles = collectPgnFiles();
    if (pgnFiles.isEmpty()) {
      throw new IOException("No PGN files found under " + GAMES_ROOT + "/.");
    }
    System.out.println("Found " + pgnFiles.size() + " PGN file(s).");

    List<ReplayableGame> games = loadGames(pgnFiles);
    System.out.println(
        "Parsed "
            + games.size()
            + " usable game(s) ("
            + malformedGames
            + " malformed, "
            + rejectedSelfPlayGames
            + " low-quality self-play skipped).");

    if (games.size() < 8) {
      throw new IOException("Not enough usable games to train (need at least 8).");
    }

    List<ReplayableGame> trainingGames = new ArrayList<>();
    List<ReplayableGame> validationGames = new ArrayList<>();
    for (ReplayableGame game : games) {
      if (isValidationGame(game)) validationGames.add(game);
      else trainingGames.add(game);
    }
    if (validationGames.isEmpty()) {
      validationGames.add(trainingGames.remove(trainingGames.size() - 1));
    }

    sortGamesDeterministically(trainingGames);
    sortGamesDeterministically(validationGames);

    NNUEFeatureExtractor extractor = new NNUEFeatureExtractor();

    long startNanos = System.nanoTime();

    ValidationSet validationSet = buildValidationSet(validationGames, extractor);
    List<SparseExample> validation = validationSet.examples();

    List<SparseExample> training =
        balanceTrainingExamples(
            buildExamples(trainingGames, extractor, "train", true, validationSet.keys()));
    long loadSec = (System.nanoTime() - startNanos) / 1_000_000_000;

    if (training.isEmpty() || validation.isEmpty()) {
      throw new IOException("No trainable positions could be built from the parsed games.");
    }

    System.out.println(
        "Precomputed "
            + training.size()
            + " training example(s) (deduplicated; augmented twins included); validation has "
            + validation.size()
            + " current holdout position(s) in "
            + loadSec
            + "s.");
    System.out.println("Training on " + threads + " worker thread(s).");

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      train(executor, training, validation, games.size());
    } finally {
      executor.shutdown();
    }
  }

  private int malformedGames;
  private int rejectedSelfPlayGames;
  private boolean warmStarted;

  private record ReplayableGame(
      List<String> sanMoves,
      double[] evalCp,
      int[] evalDepth,
      double whiteOutcome,
      boolean selfPlay) {
    double evalCpAt(int ply) {
      return evalCp != null && ply < evalCp.length ? evalCp[ply] : Double.NaN;
    }

    int evalDepthAt(int ply) {
      return evalDepth != null && ply < evalDepth.length ? evalDepth[ply] : 0;
    }

    boolean hasEvaluations() {
      if (evalCp == null) return false;
      for (int ply = 0; ply < evalCp.length; ply++) {
        if (isUsableEvaluation(evalCp[ply])
            && (!selfPlay || evalDepthAt(ply) >= MIN_SELFPLAY_EVALUATION_DEPTH)) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean isValidationGame(ReplayableGame game) {
    int bucket = Math.floorMod(gameKey(game).hashCode(), 100);
    return bucket < (int) Math.round(VALIDATION_RATIO * 100);
  }

  private static String gameKey(ReplayableGame game) {
    return String.join(" ", game.sanMoves()) + " ";
  }

  private static void sortGamesDeterministically(List<ReplayableGame> games) {
    games.sort(Comparator.comparing(GameTrainer::gameKey));
  }

  private List<File> collectPgnFiles() throws IOException {
    Path root = Path.of(GAMES_ROOT);
    if (!Files.isDirectory(root)) return List.of();
    try (Stream<Path> stream = Files.walk(root)) {
      List<File> files =
          stream
              .filter(p -> p.toString().toLowerCase().endsWith(".pgn"))
              .map(Path::toFile)
              .toList();
      List<File> selfPlay =
          files.stream()
              .filter(GameTrainer::isSelfPlayFile)
              .sorted(
                  Comparator.comparingLong(File::lastModified)
                      .reversed()
                      .thenComparing(File::getPath))
              .limit(MAX_SELFPLAY_FILES)
              .toList();
      long selfPlayFiles = files.stream().filter(GameTrainer::isSelfPlayFile).count();
      if (selfPlayFiles > selfPlay.size()) {
        System.out.printf(
            "Using the newest %d/%d self-play PGN batches; older teacher labels are stale.%n",
            selfPlay.size(), selfPlayFiles);
      }
      return Stream.concat(files.stream().filter(file -> !isSelfPlayFile(file)), selfPlay.stream())
          .toList();
    }
  }

  private static boolean isSelfPlayFile(File file) {
    return file.toPath().toString().replace('\\', '/').contains("/selfplay/");
  }

  private List<ReplayableGame> loadGames(List<File> pgnFiles) throws IOException {
    malformedGames = 0;
    rejectedSelfPlayGames = 0;
    List<ReplayableGame> games = new ArrayList<>();

    for (File file : pgnFiles) {
      boolean selfPlay = isSelfPlayFile(file);
      String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
      for (PgnGame pgn : PgnGame.parseAll(text)) {
        Double outcome = whiteOutcomeOf(pgn.getResult());
        if (outcome == null || pgn.getSanMoves().size() <= MIN_PLY) {
          malformedGames++;
          continue;
        }
        if (selfPlay && !isHighQualitySelfPlay(pgn)) {
          rejectedSelfPlayGames++;
          continue;
        }
        games.add(
            new ReplayableGame(
                pgn.getSanMoves(), pgn.getEvalCp(), pgn.getEvalDepth(), outcome, selfPlay));
      }
    }
    return games;
  }

  static boolean isHighQualitySelfPlay(PgnGame game) {
    String termination = game.getHeaders().getOrDefault("Termination", "").toLowerCase(Locale.ROOT);
    if (termination.equals("ply limit") || termination.equals("no legal move")) return false;

    Board board = new Board();
    int evaluations = 0;
    for (int ply = 0; ply < game.getSanMoves().size(); ply++) {
      Move move = SanMoveParser.parse(board, game.getSanMoves().get(ply));
      if (move == null) return false;

      if (ply >= MIN_PLY
          && game.getEvalDepth()[ply] >= MIN_SELFPLAY_EVALUATION_DEPTH
          && isUsableEvaluation(game.getEvalCp()[ply])) {
        evaluations++;
      }
      board.playMove(move);
      if (board.getCurrentPositionRepetitionCount() >= 3) return false;
    }

    int eligiblePositions = game.getSanMoves().size() - MIN_PLY;
    return evaluations >= MIN_SELFPLAY_EVALUATIONS
        && evaluations >= eligiblePositions * MIN_SELFPLAY_EVALUATION_COVERAGE;
  }

  private static Double whiteOutcomeOf(String result) {
    return switch (result) {
      case "1-0" -> 1.0;
      case "0-1" -> -1.0;
      case "1/2-1/2" -> 0.0;
      default -> null;
    };
  }

  /** A sparse feature vector and its side-to-move target. */
  record SparseExample(
      int[] indices,
      float[] values,
      double target,
      float weight,
      long positionKey,
      boolean hasEval) {}

  private record Candidate(
      long key, int[] indices, float[] values, double target, float weight, boolean hasEval) {}

  /** Replays games into deduplicated sparse rows, excluding validation positions. */
  private List<SparseExample> buildExamples(
      List<ReplayableGame> games,
      NNUEFeatureExtractor extractor,
      String label,
      boolean augment,
      Set<Long> excludeKeys) {
    PositionTable table = new PositionTable(Math.max(1024, games.size() * POSITIONS_PER_GAME_CAP));
    List<Candidate> candidates = new ArrayList<>(256);
    Board board = new Board();
    int sampledPositions = 0;
    int excludedPositions = 0;
    int rejectedEvaluations = 0;
    int gameIndex = 0;

    for (ReplayableGame game : games) {
      candidates.clear();
      board = new Board();
      double whiteOutcome = game.whiteOutcome();
      int totalPlies = game.sanMoves().size();

      for (int ply = 0; ply < totalPlies; ply++) {
        Move move = SanMoveParser.parse(board, game.sanMoves().get(ply));
        if (move == null) break; // malformed remainder of game

        if (ply >= MIN_PLY) {
          SparseExample sparse = sparsify(extractor.extract(board));

          double evalCp = game.evalCpAt(ply);
          int evalDepth = game.evalDepthAt(ply);
          boolean hasEval =
              isUsableEvaluation(evalCp)
                  && (!game.selfPlay() || evalDepth >= MIN_SELFPLAY_EVALUATION_DEPTH);
          if (!hasEval && !Double.isNaN(evalCp)) rejectedEvaluations++;
          double sideToMoveTarget =
              blendedSideToMoveTarget(
                  whiteOutcome,
                  totalPlies,
                  ply,
                  board.isWhiteToMove(),
                  hasEval ? evalCp : 0.0,
                  hasEval);
          candidates.add(
              new Candidate(
                  sparse.positionKey(),
                  sparse.indices(),
                  sparse.values(),
                  sideToMoveTarget,
                  (float) exampleWeight(whiteOutcome, sideToMoveTarget, hasEval, evalDepth),
                  hasEval));
        }
        board.playMove(move);
      }

      int total = candidates.size();
      int take = Math.min(total, POSITIONS_PER_GAME_CAP);
      for (int k = 0; k < take; k++) {
        Candidate candidate = candidates.get((int) ((long) k * total / take));
        sampledPositions++;
        if (excludeKeys != null && excludeKeys.contains(candidate.key())) {
          excludedPositions++;
          continue;
        }
        table.add(
            candidate.key(),
            candidate.indices(),
            candidate.values(),
            candidate.target(),
            candidate.weight(),
            candidate.hasEval());
      }

      gameIndex++;
      if (gameIndex % 1000 == 0) {
        System.out.printf(
            "[%s] %d/%d games replayed, %d unique position(s).%n",
            label, gameIndex, games.size(), table.size());
      }
      if (table.size() >= MAX_EXAMPLES) {
        System.out.println(
            "Unique-position cap " + MAX_EXAMPLES + " reached; ignoring further games.");
        break;
      }
    }

    List<SparseExample> examples = new ArrayList<>();
    int mergedRows = 0;
    int evalBackedRows = 0;
    for (int slot = 0; slot < table.capacity(); slot++) {
      if (!table.isOccupied(slot)) continue;
      if (examples.size() >= MAX_EXAMPLES) {
        System.out.println(
            "Example cap " + MAX_EXAMPLES + " reached while emitting " + label + " rows.");
        break;
      }
      int count = table.countAt(slot);
      if (count > 1) mergedRows++;
      double target = table.targetAt(slot);
      float weight = table.weightAt(slot);
      long key = table.keyAt(slot);
      boolean hasEval = table.hasEvalAt(slot);
      if (hasEval) evalBackedRows++;
      examples.add(
          new SparseExample(
              table.indicesAt(slot), table.valuesAt(slot), target, weight, key, hasEval));
      if (augment && examples.size() < MAX_EXAMPLES) {
        // Mirroring swaps both color and perspective, so the target stays unchanged.
        SparseExample twin =
            sparsify(
                NNUEFeatureExtractor.rotated(densify(table.indicesAt(slot), table.valuesAt(slot))));
        examples.add(new SparseExample(twin.indices(), twin.values(), target, weight, 0L, hasEval));
      }
    }

    double duplicationFactor =
        table.size() == 0 ? 1.0 : (double) (sampledPositions - excludedPositions) / table.size();
    System.out.printf(
        "[%s] %d game(s): %d sampled position(s), %d skipped (held-out overlap), %d unique"
            + " (%.2fx duplicated), %d merged-label row(s), %d eval-backed row(s), %d row(s)"
            + " emitted, %d invalid evaluation(s) rejected.%n",
        label,
        games.size(),
        sampledPositions,
        excludedPositions,
        table.size(),
        duplicationFactor,
        mergedRows,
        evalBackedRows,
        examples.size(),
        rejectedEvaluations);
    return examples;
  }

  static boolean isUsableEvaluation(double evalCp) {
    return Double.isFinite(evalCp) && Math.abs(evalCp) <= MAX_USABLE_EVAL_CP;
  }

  static double evaluationConfidence(int depth) {
    return depth <= 0 ? 0.75 : Math.clamp(0.55 + depth * 0.08, 0.75, 1.25);
  }

  static boolean preservesEvaluationQuality(double candidateMse, double referenceMse) {
    if (!Double.isFinite(referenceMse)) return true;
    return Double.isFinite(candidateMse)
        && candidateMse <= referenceMse * (1.0 + MAX_EVAL_MSE_REGRESSION);
  }

  static List<SparseExample> balanceTrainingExamples(List<SparseExample> examples) {
    List<SparseExample> evalBacked = new ArrayList<>();
    List<SparseExample> resultOnly = new ArrayList<>();
    for (SparseExample example : examples) {
      if (example.hasEval()) evalBacked.add(example);
      else resultOnly.add(example);
    }

    if (evalBacked.isEmpty()) return examples;

    int resultLimit =
        Math.min(resultOnly.size(), evalBacked.size() / EVALUATION_EXAMPLES_PER_RESULT_ONLY);
    if (resultLimit == resultOnly.size()) return examples;

    List<SparseExample> balanced = new ArrayList<>(evalBacked.size() + resultLimit);
    balanced.addAll(evalBacked);
    balanced.addAll(subsampleEvenly(resultOnly, resultLimit));
    System.out.printf(
        "Balanced training rows: kept %d evaluation-backed and %d/%d result-only examples.%n",
        evalBacked.size(), resultLimit, resultOnly.size());
    return balanced;
  }

  private static double temporalWhiteTarget(double whiteOutcome, int totalPlies, int ply) {
    if (whiteOutcome == 0.0) return 0.0;

    int pliesLeft = Math.max(0, totalPlies - ply);

    double futureSignal = Math.exp(-pliesLeft / TEMPORAL_DECAY_PLIES);
    double strength = TEMPORAL_BASE_WEIGHT + TEMPORAL_FUTURE_WEIGHT * futureSignal;

    return whiteOutcome * Math.clamp(strength, 0.0, 1.0);
  }

  static double blendedSideToMoveTarget(
      double whiteOutcome,
      int totalPlies,
      int ply,
      boolean whiteToMove,
      double evalCp,
      boolean hasEval) {
    double temporalWhite = temporalWhiteTarget(whiteOutcome, totalPlies, ply);
    double temporalSideToMove = whiteToMove ? temporalWhite : -temporalWhite;
    if (!hasEval) return temporalSideToMove;

    double evalSideToMove = Math.tanh(evalCp / EVAL_TARGET_SCALE_CP);
    return (1.0 - RESULT_MIX) * evalSideToMove + RESULT_MIX * temporalSideToMove;
  }

  private static double exampleWeight(
      double whiteOutcome, double target, boolean hasEval, int evalDepth) {
    if (hasEval) return evaluationConfidence(evalDepth);

    double drawMultiplier = whiteOutcome == 0.0 ? DRAW_EXAMPLE_WEIGHT : 1.0;
    double signal = Math.abs(target);
    double signalMultiplier = 0.35 + 0.65 * signal;
    return RESULT_ONLY_ROW_WEIGHT * drawMultiplier * signalMultiplier;
  }

  private static SparseExample sparsify(double[] features) {
    int nonZeros = 0;
    for (double v : features) if (v != 0.0) nonZeros++;

    int[] indices = new int[nonZeros];
    float[] values = new float[nonZeros];
    int n = 0;
    for (int i = 0; i < features.length; i++) {
      if (features[i] != 0.0) {
        indices[n] = i;
        values[n] = (float) features[i];
        n++;
      }
    }
    return new SparseExample(indices, values, 0.0, 1.0f, positionKey(indices, values), false);
  }

  private static long positionKey(int[] indices, float[] values) {
    long h = 0x9E3779B97F4A7C15L;
    for (int n = 0; n < indices.length; n++) {
      h ^= (indices[n] + 1L) * 0xC2B2AE3D27D4EB4FL;
      h ^= h >>> 29;
      h *= 0xBF58476D1CE4E5B9L;
      h ^= Double.doubleToRawLongBits(values[n]);
      h *= 0x94D049BB133111EBL;
    }
    h ^= h >>> 32;
    h *= 0xFF51AFD7ED558CCDL;
    h ^= h >>> 29;
    if (h == 0L) h = Long.MIN_VALUE;
    return h;
  }

  private static double[] densify(int[] indices, float[] values) {
    double[] dense = new double[NNUEFeatureExtractor.INPUT_SIZE];
    for (int n = 0; n < indices.length; n++) dense[indices[n]] = values[n];
    return dense;
  }

  static final class PositionTable {
    private static final long EMPTY = 0L;

    private long[] keys;
    private int[][] slotIndices;
    private float[][] slotValues;
    private double[] targetSum;
    private double[] weightSum;
    private int[] counts;
    private int[] evalCounts;
    private int size;

    PositionTable(int expectedEntries) {
      int capacity = 4096;
      while ((expectedEntries + 1) * 5L >= capacity * 3L) capacity <<= 1;
      allocate(capacity);
    }

    private void allocate(int capacity) {
      keys = new long[capacity];
      slotIndices = new int[capacity][];
      slotValues = new float[capacity][];
      targetSum = new double[capacity];
      weightSum = new double[capacity];
      counts = new int[capacity];
      evalCounts = new int[capacity];
      size = 0;
    }

    void add(long key, int[] indices, float[] values, double target, double weight, boolean eval) {
      if ((size + 1) * 5L >= keys.length * 3L) grow();
      int mask = keys.length - 1;
      int slot = spread(key) & mask;
      while (true) {
        long occupant = keys[slot];
        if (occupant == EMPTY) {
          keys[slot] = key;
          slotIndices[slot] = indices;
          slotValues[slot] = values;
          counts[slot] = 1;
          addLabel(slot, target, weight, eval);
          size++;
          return;
        }
        if (occupant == key) {
          counts[slot]++;
          addLabel(slot, target, weight, eval);
          return;
        }
        slot = (slot + 1) & mask;
      }
    }

    private void addLabel(int slot, double target, double weight, boolean eval) {
      if (eval) {
        if (evalCounts[slot] == 0) {
          targetSum[slot] = 0.0;
          weightSum[slot] = 0.0;
        }
        targetSum[slot] += target * weight;
        weightSum[slot] += weight;
        evalCounts[slot]++;
      } else if (evalCounts[slot] == 0) {
        targetSum[slot] += target * weight;
        weightSum[slot] += weight;
      }
    }

    private void grow() {
      long[] oldKeys = keys;
      int[][] oldIndices = slotIndices;
      float[][] oldValues = slotValues;
      double[] oldTargetSum = targetSum;
      double[] oldWeightSum = weightSum;
      int[] oldCounts = counts;
      int[] oldEvalCounts = evalCounts;
      allocate(oldKeys.length << 1);
      int mask = keys.length - 1;
      for (int oldSlot = 0; oldSlot < oldKeys.length; oldSlot++) {
        long key = oldKeys[oldSlot];
        if (key == EMPTY) continue;
        int slot = spread(key) & mask;
        while (keys[slot] != EMPTY) slot = (slot + 1) & mask;
        keys[slot] = key;
        slotIndices[slot] = oldIndices[oldSlot];
        slotValues[slot] = oldValues[oldSlot];
        targetSum[slot] = oldTargetSum[oldSlot];
        weightSum[slot] = oldWeightSum[oldSlot];
        counts[slot] = oldCounts[oldSlot];
        evalCounts[slot] = oldEvalCounts[oldSlot];
        size++;
      }
    }

    int size() {
      return size;
    }

    int capacity() {
      return keys.length;
    }

    boolean isOccupied(int slot) {
      return keys[slot] != EMPTY;
    }

    long keyAt(int slot) {
      return keys[slot];
    }

    int[] indicesAt(int slot) {
      return slotIndices[slot];
    }

    float[] valuesAt(int slot) {
      return slotValues[slot];
    }

    double targetAt(int slot) {
      return targetSum[slot] / (weightSum[slot] + LABEL_SHRINKAGE_PRIOR);
    }

    float weightAt(int slot) {
      return evalCounts[slot] > 0
          ? (float) Math.min(1.25, weightSum[slot] / evalCounts[slot])
          : (float) Math.min(1.0, weightSum[slot] / counts[slot]);
    }

    int countAt(int slot) {
      return counts[slot];
    }

    boolean hasEvalAt(int slot) {
      return evalCounts[slot] > 0;
    }

    private static int spread(long key) {
      long h = key * 0x9E3779B97F4A7C15L;
      h ^= h >>> 32;
      return (int) (h ^ (h >>> 16));
    }
  }

  private record ValidationSet(List<SparseExample> examples, Set<Long> keys) {}

  private static Set<Long> collectKeys(List<SparseExample> examples) {
    Set<Long> keys = new HashSet<>(Math.max(16, examples.size() * 2));
    for (SparseExample e : examples) keys.add(e.positionKey());
    return keys;
  }

  private ValidationSet buildValidationSet(
      List<ReplayableGame> validationGames, NNUEFeatureExtractor extractor) {
    List<ReplayableGame> evalGames = new ArrayList<>();
    for (ReplayableGame game : validationGames) if (game.hasEvaluations()) evalGames.add(game);
    List<ReplayableGame> source = evalGames.isEmpty() ? validationGames : evalGames;
    System.out.printf(
        "Building validation from %d game(s) (%d with evaluation comments).%n",
        source.size(), evalGames.size());

    List<SparseExample> built = buildExamples(source, extractor, "validation", false, null);
    if (!evalGames.isEmpty()) {
      built = built.stream().filter(SparseExample::hasEval).toList();
    }
    built = subsampleEvenly(built, VALIDATION_MAX_EXAMPLES);
    Set<Long> keys = collectKeys(built);
    System.out.printf(
        "Built current validation holdout with %d position(s); %d position key(s) stay out of"
            + " training.%n",
        built.size(), keys.size());
    return new ValidationSet(built, keys);
  }

  private static List<SparseExample> subsampleEvenly(List<SparseExample> examples, int max) {
    if (examples.size() <= max) return examples;
    List<SparseExample> out = new ArrayList<>(max);
    for (int i = 0; i < max; i++) {
      out.add(examples.get((int) ((long) i * examples.size() / max)));
    }
    return out;
  }

  private static final class TrainingState {
    Double bestValidationMse;
    Double bestValidationMae;
    Double bestBaselineMse;
    Integer bestTrainExamples;
    Integer bestValidationExamples;
    Integer bestGames;
    String bestTimestamp;
    Double lastRunBestMse;
    String lastRunTimestamp;

    static TrainingState load(File file) {
      TrainingState s = new TrainingState();
      if (!file.isFile()) return s;
      Properties p = new Properties();
      try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
        p.load(in);
      } catch (IOException e) {
        System.err.println("Ignoring unreadable " + file.getName() + ": " + e.getMessage());
        return s;
      }
      s.bestValidationMse = parseDouble(p, "best.validation.mse");
      s.bestValidationMae = parseDouble(p, "best.validation.mae");
      s.bestBaselineMse = parseDouble(p, "best.baseline.mse");
      s.bestTrainExamples = parseInteger(p, "best.train.examples");
      s.bestValidationExamples = parseInteger(p, "best.validation.examples");
      s.bestGames = parseInteger(p, "best.games");
      s.bestTimestamp = p.getProperty("best.timestamp");
      s.lastRunBestMse = parseDouble(p, "last.run.best.mse");
      s.lastRunTimestamp = p.getProperty("last.run.timestamp");
      return s;
    }

    void store(File file) throws IOException {
      Properties p = new Properties();
      put(p, "best.validation.mse", bestValidationMse);
      put(p, "best.validation.mae", bestValidationMae);
      put(p, "best.baseline.mse", bestBaselineMse);
      put(p, "best.train.examples", bestTrainExamples);
      put(p, "best.validation.examples", bestValidationExamples);
      put(p, "best.games", bestGames);
      put(p, "best.timestamp", bestTimestamp);
      put(p, "last.run.best.mse", lastRunBestMse);
      put(p, "last.run.timestamp", lastRunTimestamp);
      try (OutputStream out = new FileOutputStream(file)) {
        p.store(out, "GameTrainer cross-run state (all-time best). Delete to reset tracking.");
      }
    }

    private static void put(Properties properties, String key, Object value) {
      if (value != null) properties.setProperty(key, value.toString());
    }

    private static Double parseDouble(Properties p, String key) {
      String v = p.getProperty(key);
      if (v == null) return null;
      try {
        return Double.valueOf(v);
      } catch (NumberFormatException e) {
        return null;
      }
    }

    private static Integer parseInteger(Properties p, String key) {
      String v = p.getProperty(key);
      if (v == null) return null;
      try {
        return Integer.valueOf(v.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  private static String timestamp() {
    return LocalDateTime.now().format(TIMESTAMP_FMT);
  }

  private static void appendHistory(String line) {
    try {
      Files.writeString(
          Path.of(HISTORY_FILE),
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("Could not append to " + HISTORY_FILE + ": " + e.getMessage());
    }
  }

  private static String fmtOrNone(double mse) {
    return Double.isFinite(mse) ? String.format(java.util.Locale.US, "%.4f", mse) : "none yet";
  }

  private void train(
      ExecutorService executor,
      List<SparseExample> training,
      List<SparseExample> validation,
      int gameCount)
      throws IOException {

    double baselineMse = meanSquareTarget(validation);

    double divergenceMargin = DIVERGENCE_MARGIN_RELATIVE * baselineMse;
    TrainingState state = TrainingState.load(new File(STATE_FILE));
    File bestFile = new File(BEST_WEIGHTS_FILE);
    double allTimeBestMse = Double.POSITIVE_INFINITY;
    double allTimeBestMae = Double.NaN;
    double allTimeBestEvalMse = Double.NaN;

    if (bestFile.isFile()) {
      try {
        EvalStats stats = evaluateParallel(executor, NNUEWeights.load(bestFile), validation);
        allTimeBestMse = stats.mse;
        allTimeBestMae = stats.mae;
        allTimeBestEvalMse = stats.mseEval;
        if (state.bestValidationMse != null
            && Math.abs(state.bestValidationMse - allTimeBestMse) > 5.0e-3) {
          System.out.printf(
              "Recorded best (%.4f) disagrees with %s measured on the current"
                  + " validation set (%.4f); adopting the measured value.%n",
              state.bestValidationMse, BEST_WEIGHTS_FILE, allTimeBestMse);
        }
        state.bestValidationMse = allTimeBestMse;
        state.bestValidationMae = allTimeBestMae;
        state.bestBaselineMse = baselineMse;
      } catch (IOException e) {
        System.err.println("Could not read " + BEST_WEIGHTS_FILE + ": " + e.getMessage());
        if (state.bestValidationMse != null) {
          allTimeBestMse = state.bestValidationMse;
          System.out.println(
              "Keeping the recorded all-time best number, but the weights file must be"
                  + " regenerated before the engine prefers it again.");
        }
      }
    } else if (state.bestValidationMse != null) {
      System.out.printf(
          "%s is missing; ignoring the recorded metric because there is no checkpoint to"
              + " restore.%n",
          BEST_WEIGHTS_FILE);
    }

    System.out.println();
    System.out.println("================= STARTUP STATE =================");
    if (Double.isFinite(allTimeBestMse)) {
      System.out.printf(
          "ALL-TIME BEST validation MSE : %.4f (MAE %s)%n",
          allTimeBestMse,
          Double.isNaN(allTimeBestMae)
              ? "n/a"
              : String.format(java.util.Locale.US, "%.4f", allTimeBestMae));
      System.out.printf(
          "  recorded %s | baseline then %s | games %s%n",
          state.bestTimestamp != null ? state.bestTimestamp : "unknown",
          state.bestBaselineMse != null
              ? String.format(java.util.Locale.US, "%.4f", state.bestBaselineMse)
              : "unknown",
          state.bestGames != null ? state.bestGames.toString() : "unknown");
      System.out.println(
          "  net stored in " + BEST_WEIGHTS_FILE + " (the engine loads this file FIRST)");
    } else {
      System.out.println("ALL-TIME BEST validation MSE : none yet (first tracked run)");
    }
    System.out.printf(
        "Last finished run            : %s%n",
        state.lastRunBestMse != null
            ? String.format(
                java.util.Locale.US,
                "%.4f at %s",
                state.lastRunBestMse,
                state.lastRunTimestamp != null ? state.lastRunTimestamp : "unknown")
            : "none yet");
    System.out.printf(
        "Baseline on validation set   : %.4f (predicting 0.0 for everything)%n", baselineMse);
    System.out.println("Improvement log              : " + HISTORY_FILE);
    System.out.println("=================================================");
    System.out.println();

    NNUEWeights weights = chooseStartingWeights(executor, validation, baselineMse);

    AdamOptimizer adam = new AdamOptimizer();
    Gradients gradients = new Gradients();
    WorkerPool pool = new WorkerPool(executor);

    Gradients[] buffers = new Gradients[MAX_CHUNKS];
    for (int i = 0; i < buffers.length; i++) buffers[i] = new Gradients();

    double lr = warmStarted ? LEARNING_RATE * WARM_START_LR_FRACTION : LEARNING_RATE;
    NNUEWeights bestWeights = weights.copy();
    EvalStats initialEval = evaluateParallel(executor, weights, validation);
    double bestValidationLoss = initialEval.mse;
    int checkpointsSinceBest = 0;
    int divergingCheckpoints = 0;
    int divergeCooldown = 0;
    boolean stopped = false;

    Integer[] order = new Integer[training.size()];
    for (int i = 0; i < order.length; i++) order[i] = i;

    int batchesPerEpoch = (order.length + BATCH_SIZE - 1) / BATCH_SIZE;
    int validationsPerEpoch = batchesPerEpoch / VALIDATE_EVERY_BATCHES + 1;
    int lrPatience = Math.max(MIN_LR_PATIENCE, LR_PATIENCE_EPOCHS * validationsPerEpoch);
    int earlyStopPatience =
        Math.max(MIN_EARLY_STOP_PATIENCE, EARLY_STOP_PATIENCE_EPOCHS * validationsPerEpoch);
    System.out.printf(
        "Schedule: %d batches/epoch -> validation every %d batches (~%d per epoch);"
            + " LR halves after %d flat validations (~%d epoch(s)); early stop after %d"
            + " (~%d epoch(s)).%n",
        batchesPerEpoch,
        VALIDATE_EVERY_BATCHES,
        validationsPerEpoch,
        lrPatience,
        LR_PATIENCE_EPOCHS,
        earlyStopPatience,
        EARLY_STOP_PATIENCE_EPOCHS);
    System.out.printf(
        "Initial validation split: overall MSE %.4f | eval-backed MSE %s (n=%d) | result-only"
            + " MSE %s (n=%d)%n",
        initialEval.mse,
        fmtOrNone(initialEval.mseEval),
        initialEval.countEval,
        fmtOrNone(initialEval.mseNoEval),
        initialEval.countNoEval);

    for (int epoch = 1; epoch <= epochs && !stopped; epoch++) {
      long startNanos = System.nanoTime();
      Collections.shuffle(Arrays.asList(order), random);

      double squaredErrorSum = 0;
      double absoluteErrorSum = 0;
      long exampleCount = 0;
      int batchIndex = 0;

      for (int from = 0; from < order.length && !stopped; from += BATCH_SIZE) {
        int to = Math.min(from + BATCH_SIZE, order.length);
        BatchResult result =
            pool.trainBatch(weights, gradients, buffers, training, order, from, to);
        adam.step(weights, gradients, lr);

        squaredErrorSum += result.squaredErrorSum;
        absoluteErrorSum += result.absoluteErrorSum;
        exampleCount += result.examples;
        batchIndex++;

        if (batchIndex % LOG_EVERY_BATCHES == 0) {
          System.out.printf(
              "  epoch %d batch %d | running train MSE %.4f MAE %.4f%n",
              epoch, batchIndex, squaredErrorSum / exampleCount, absoluteErrorSum / exampleCount);
        }

        boolean epochEnd = from + BATCH_SIZE >= order.length;
        boolean checkpointDue = batchIndex % VALIDATE_EVERY_BATCHES == 0 || epochEnd;
        if (checkpointDue) {
          EvalStats validationStats = evaluateParallel(executor, weights, validation);
          checkpointsSinceBest++;

          System.out.printf(
              "  [val] epoch %d batch %d | validation MSE %.4f (run best %.4f | all-time %s"
                  + " | baseline %.4f) | eval %s (n=%d) | result-only %s (n=%d)%n",
              epoch,
              batchIndex,
              validationStats.mse,
              bestValidationLoss,
              fmtOrNone(allTimeBestMse),
              baselineMse,
              fmtOrNone(validationStats.mseEval),
              validationStats.countEval,
              fmtOrNone(validationStats.mseNoEval),
              validationStats.countNoEval);

          if (validationStats.mse < bestValidationLoss - MIN_VALIDATION_IMPROVEMENT) {
            bestValidationLoss = validationStats.mse;
            bestWeights = weights.copy();
            checkpointsSinceBest = 0;
            bestWeights.save(new File(OUTPUT_FILE));

            boolean newAllTimeBest =
                validationStats.mse < allTimeBestMse - MIN_VALIDATION_IMPROVEMENT
                    && preservesEvaluationQuality(validationStats.mseEval, allTimeBestEvalMse);
            if (newAllTimeBest) {
              double previousAllTime = allTimeBestMse;
              allTimeBestMse = validationStats.mse;
              allTimeBestMae = validationStats.mae;
              allTimeBestEvalMse = validationStats.mseEval;

              bestWeights.save(bestFile);
              state.bestValidationMse = allTimeBestMse;
              state.bestValidationMae = allTimeBestMae;
              state.bestBaselineMse = baselineMse;
              state.bestTrainExamples = training.size();
              state.bestValidationExamples = validation.size();
              state.bestGames = gameCount;
              state.bestTimestamp = timestamp();
              state.store(new File(STATE_FILE));

              appendHistory(
                  String.format(
                      java.util.Locale.US,
                      "%s | NEW BEST  | valMse %.4f | evalMse %s | resultOnlyMse %s"
                          + " | prevAllTime %s | baseline %.4f | trainEx %d | valEx %d"
                          + " | games %d",
                      state.bestTimestamp,
                      allTimeBestMse,
                      fmtOrNone(validationStats.mseEval),
                      fmtOrNone(validationStats.mseNoEval),
                      fmtOrNone(previousAllTime),
                      baselineMse,
                      training.size(),
                      validation.size(),
                      gameCount));

              System.out.printf(
                  "  >> *** ALL-TIME BEST *** validation MSE %.4f (was %s); saved to %s + %s%n",
                  allTimeBestMse, fmtOrNone(previousAllTime), BEST_WEIGHTS_FILE, STATE_FILE);
            } else {
              System.out.printf(
                  "  >> new run best: validation MSE %.4f (baseline %.4f); saved to %s%n",
                  bestValidationLoss, baselineMse, OUTPUT_FILE);
              if (validationStats.mse < allTimeBestMse - MIN_VALIDATION_IMPROVEMENT) {
                System.out.printf(
                    "  >> protected best retained: eval-backed MSE %s exceeded the %.4f"
                        + " safety limit.%n",
                    fmtOrNone(validationStats.mseEval),
                    allTimeBestEvalMse * (1.0 + MAX_EVAL_MSE_REGRESSION));
              }
            }
          } else {
            if (checkpointsSinceBest >= earlyStopPatience) {
              System.out.printf(
                  "  Early stopping: no validation improvement for %d validations"
                      + " (~%d epoch(s)).%n",
                  checkpointsSinceBest, checkpointsSinceBest / validationsPerEpoch);
              stopped = true;
            } else if (checkpointsSinceBest % lrPatience == 0 && lr > MIN_LEARNING_RATE) {
              lr = Math.max(MIN_LEARNING_RATE, lr * LR_DECAY);
              System.out.println("  Learning rate reduced to " + lr);
            }
          }

          if (validationStats.mse > bestValidationLoss + divergenceMargin) {
            divergingCheckpoints++;
          } else {
            divergingCheckpoints = 0;
          }
          if (divergeCooldown > 0) {
            divergeCooldown--;
          } else if (divergingCheckpoints >= DIVERGENCE_CHECKPOINTS && lr > MIN_LEARNING_RATE) {
            lr = Math.max(MIN_LEARNING_RATE, lr * LR_DECAY);
            divergeCooldown = DIVERGENCE_COOLDOWN;
            System.out.println(
                "  Validation well above run best for "
                    + DIVERGENCE_CHECKPOINTS
                    + " checkpoints; learning rate reduced to "
                    + lr);
          }
        }
      }

      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      System.out.printf(
          "Epoch %3d/%d | train MSE %.4f MAE %.4f | examples %d | lr %.5f | %d ms"
              + " | run best %.4f | all-time %s%n",
          epoch,
          epochs,
          squaredErrorSum / Math.max(1, exampleCount),
          absoluteErrorSum / Math.max(1, exampleCount),
          exampleCount,
          lr,
          elapsedMs,
          bestValidationLoss,
          fmtOrNone(allTimeBestMse));
    }

    bestWeights.save(new File(OUTPUT_FILE));
    EvalStats finalBestStats = evaluateParallel(executor, bestWeights, validation);

    if (bestValidationLoss < allTimeBestMse - MIN_VALIDATION_IMPROVEMENT
        && preservesEvaluationQuality(finalBestStats.mseEval, allTimeBestEvalMse)) {
      double previousAllTime = allTimeBestMse;
      allTimeBestMse = bestValidationLoss;
      allTimeBestMae = finalBestStats.mae;
      allTimeBestEvalMse = finalBestStats.mseEval;
      bestWeights.save(bestFile);
      state.bestValidationMse = allTimeBestMse;
      state.bestValidationMae = allTimeBestMae;
      state.bestBaselineMse = baselineMse;
      state.bestTrainExamples = training.size();
      state.bestValidationExamples = validation.size();
      state.bestGames = gameCount;
      state.bestTimestamp = timestamp();
      appendHistory(
          String.format(
              java.util.Locale.US,
              "%s | NEW BEST  | valMse %.4f | evalMse %s | resultOnlyMse %s"
                  + " | prevAllTime %s | baseline %.4f | trainEx %d | valEx %d | games %d",
              state.bestTimestamp,
              allTimeBestMse,
              fmtOrNone(finalBestStats.mseEval),
              fmtOrNone(finalBestStats.mseNoEval),
              fmtOrNone(previousAllTime),
              baselineMse,
              training.size(),
              validation.size(),
              gameCount));
      System.out.printf(
          "Promoted run-best validation MSE %.4f to %s.%n", allTimeBestMse, BEST_WEIGHTS_FILE);
    }

    if (bestFile.isFile() && bestValidationLoss > allTimeBestMse + 1.0e-9) {
      Files.copy(
          bestFile.toPath(), new File(OUTPUT_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
      System.out.println(
          "This run finished above the all-time best; restored "
              + BEST_WEIGHTS_FILE
              + " into "
              + OUTPUT_FILE
              + ".");
    }

    state.lastRunBestMse = bestValidationLoss;
    state.lastRunTimestamp = timestamp();
    state.store(new File(STATE_FILE));
    appendHistory(
        String.format(
            java.util.Locale.US,
            "%s | RUN DONE  | runBest %.4f | evalMse %s | resultOnlyMse %s | allTime %s"
                + " | baseline %.4f | epochs %d | games %d",
            state.lastRunTimestamp,
            bestValidationLoss,
            fmtOrNone(finalBestStats.mseEval),
            fmtOrNone(finalBestStats.mseNoEval),
            fmtOrNone(allTimeBestMse),
            baselineMse,
            epochs,
            gameCount));

    System.out.println();
    System.out.println("======================== TRAINING SUMMARY ========================");
    System.out.printf("Run best validation MSE      : %.4f%n", bestValidationLoss);
    System.out.printf(
        "  eval-backed MSE (n=%d)      : %s%n",
        finalBestStats.countEval, fmtOrNone(finalBestStats.mseEval));
    System.out.printf(
        "  result-only MSE (n=%d)      : %s%n",
        finalBestStats.countNoEval, fmtOrNone(finalBestStats.mseNoEval));
    if (Double.isFinite(allTimeBestMse)) {
      System.out.printf(
          "ALL-TIME BEST validation MSE : %.4f (recorded %s)%n",
          allTimeBestMse, state.bestTimestamp != null ? state.bestTimestamp : "unknown");
      System.out.println("All-time best net            : " + BEST_WEIGHTS_FILE);
    } else {
      System.out.println("ALL-TIME BEST validation MSE : none yet");
    }
    System.out.printf("Baseline (predict 0.0) MSE   : %.4f%n", baselineMse);
    System.out.println(
        "Engine loads (first match)   : nnue_weights_best.bin," + " then nnue_weights.bin");
    System.out.println("Full history                 : " + HISTORY_FILE);
    System.out.println("The engine picks these up automatically on next start.");
    System.out.println(
        "If eval-backed MSE is still improving while the overall number looks flat, that is a"
            + " label-ceiling effect from result-only rows, not overfitting - see the class"
            + " javadoc.");
    System.out.println("==================================================================");
  }

  private record WeightCandidate(String name, NNUEWeights weights) {}

  private NNUEWeights chooseStartingWeights(
      ExecutorService executor, List<SparseExample> validation, double baselineMse) {
    List<WeightCandidate> candidates = new ArrayList<>();
    for (String name : new String[] {OUTPUT_FILE, BEST_WEIGHTS_FILE}) {
      NNUEWeights w = loadWeightsFile(name);
      if (w != null) candidates.add(new WeightCandidate(name, w));
    }
    NNUEWeights fresh = NNUEWeights.random(SEED ^ System.nanoTime());

    if (candidates.isEmpty()) {
      System.out.println("Starting from a fresh random initialization.");
      return fresh;
    }

    NNUEWeights best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (WeightCandidate candidate : candidates) {
      double score = evaluateParallel(executor, candidate.weights(), validation).mse;
      if (score < baselineMse && score <= bestScore) {
        bestScore = score;
        best = candidate.weights();
        System.out.printf(
            "Candidate %s: validation MSE %.4f (baseline %.4f) - eligible.%n",
            candidate.name(), score, baselineMse);
      } else {
        System.out.printf(
            "Candidate %s: validation MSE %.4f (baseline %.4f) - rejected.%n",
            candidate.name(), score, baselineMse);
      }
    }

    double freshScore = evaluateParallel(executor, fresh, validation).mse;
    if (best != null && bestScore <= freshScore) {
      warmStarted = true;
      System.out.printf(
          "Warm-starting from existing checkpoint (validation MSE %.4f vs %.4f for fresh"
              + " init); initial learning rate reduced to %.2e.%n",
          bestScore, freshScore, LEARNING_RATE * WARM_START_LR_FRACTION);
      return best;
    }
    System.out.printf(
        "Discarding stale checkpoints (best %.4f; must beat both baseline %.4f and fresh init"
            + " %.4f), starting fresh.%n",
        bestScore, baselineMse, freshScore);
    return fresh;
  }

  private static NNUEWeights loadWeightsFile(String name) {
    File file = new File(name);
    if (!file.isFile()) return null;
    try {
      return NNUEWeights.load(file);
    } catch (IOException e) {
      System.err.println("Could not read " + name + ": " + e.getMessage());
      return null;
    }
  }

  private static double meanSquareTarget(List<SparseExample> examples) {
    double sum = 0;
    for (SparseExample e : examples) sum += e.target() * e.target();
    return sum / examples.size();
  }

  private static final class Scratch {
    final double[] h1 = new double[NNUEWeights.HIDDEN_SIZE];
    final double[] h2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[] h3 = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    final double[] dh1 = new double[NNUEWeights.HIDDEN_SIZE];
    final double[] dh2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[] dh3 = new double[NNUEWeights.THIRD_HIDDEN_SIZE];

    double output;
  }

  private static double forward(NNUEWeights w, SparseExample e, Scratch s, Random dropoutRng) {
    double[] h1 = s.h1;
    for (int k = 0; k < h1.length; k++) h1[k] = w.hiddenBias[k];
    int[] idx = e.indices();
    float[] vals = e.values();
    for (int n = 0; n < idx.length; n++) {
      double v = vals[n];
      double[] row = w.inputWeights[idx[n]];
      for (int k = 0; k < h1.length; k++) h1[k] += v * row[k];
    }
    for (int k = 0; k < h1.length; k++) if (h1[k] < 0.0) h1[k] = 0.0;

    double[] h2 = s.h2;
    denseRelu(h1, w.hiddenWeights, w.secondHiddenBias, h2);
    if (dropoutRng != null) applyDropout(h2, dropoutRng);

    double[] h3 = s.h3;
    denseRelu(h2, w.secondHiddenWeights, w.thirdHiddenBias, h3);
    if (dropoutRng != null) applyDropout(h3, dropoutRng);

    double raw = w.outputBias;
    for (int j = 0; j < h3.length; j++) raw += h3[j] * w.outputWeights[j];

    s.output = Math.tanh(raw);
    return s.output;
  }

  private static void denseRelu(
      double[] input, double[][] weights, double[] bias, double[] output) {
    for (int j = 0; j < output.length; j++) {
      double sum = bias[j];
      for (int i = 0; i < input.length; i++) sum += input[i] * weights[i][j];
      output[j] = Math.max(0.0, sum);
    }
  }

  private static void applyDropout(double[] activations, Random rng) {
    double scale = 1.0 / (1.0 - DROPOUT_RATE);
    for (int i = 0; i < activations.length; i++) {
      if (rng.nextDouble() < DROPOUT_RATE) activations[i] = 0.0;
      else activations[i] *= scale;
    }
  }

  private static void accumulateGradient(NNUEWeights w, Gradients g, SparseExample e, Scratch s) {
    double outputError = s.output - e.target();
    double dOut = 2.0 * outputError * (1.0 - s.output * s.output) * e.weight();
    double dropoutScale = 1.0 / (1.0 - DROPOUT_RATE);

    double[] h1 = s.h1;
    double[] h2 = s.h2;
    double[] h3 = s.h3;

    for (int j = 0; j < h3.length; j++) g.outputWeights[j] += dOut * h3[j];
    g.outputBias += dOut;

    double[] dh3 = s.dh3;
    for (int j = 0; j < h3.length; j++) {
      dh3[j] = h3[j] > 0.0 ? dOut * w.outputWeights[j] * dropoutScale : 0.0;
      g.thirdHiddenBias[j] += dh3[j];
    }

    double[] dh2 = s.dh2;
    for (int m = 0; m < h2.length; m++) {
      double sum = 0.0;
      if (h2[m] > 0.0) {
        double[] row = w.secondHiddenWeights[m];
        for (int j = 0; j < h3.length; j++) sum += row[j] * dh3[j];
        sum *= dropoutScale;
        g.secondHiddenBias[m] += sum;
        double[] gradRow = g.secondHiddenWeights[m];
        for (int j = 0; j < h3.length; j++) gradRow[j] += dh3[j] * h2[m];
      }
      dh2[m] = sum;
    }

    double[] dh1 = s.dh1;
    for (int k = 0; k < h1.length; k++) {
      double sum = 0.0;
      if (h1[k] > 0.0) {
        double[] row = w.hiddenWeights[k];
        for (int m = 0; m < h2.length; m++) sum += row[m] * dh2[m];
        g.hiddenBias[k] += sum;
        double[] gradRow = g.hiddenWeights[k];
        for (int m = 0; m < h2.length; m++) gradRow[m] += dh2[m] * h1[k];
      }
      dh1[k] = sum;
    }

    int[] idx = e.indices();
    float[] vals = e.values();
    for (int n = 0; n < idx.length; n++) {
      double v = vals[n];
      double[] gradRow = g.inputWeights[idx[n]];
      for (int k = 0; k < h1.length; k++) {
        if (dh1[k] != 0.0) gradRow[k] += dh1[k] * v;
      }
    }
  }

  private static int[][] chunkRanges(int total) {
    if (total == 0) return new int[0][];
    int parts = Math.min(total, MAX_CHUNKS);
    int chunkSize = (total + parts - 1) / parts;
    int[][] ranges = new int[(total + chunkSize - 1) / chunkSize][2];
    for (int i = 0, from = 0; i < ranges.length; i++, from += chunkSize) {
      ranges[i] = new int[] {from, Math.min(from + chunkSize, total)};
    }
    return ranges;
  }

  private record BatchResult(double squaredErrorSum, double absoluteErrorSum, int examples) {}

  private static BatchResult trainRange(
      NNUEWeights w,
      Gradients g,
      List<SparseExample> examples,
      Integer[] order,
      int fromInclusive,
      int toExclusive) {
    Scratch scratch = new Scratch();
    Random dropoutRng = new Random(SEED * 31 + fromInclusive);
    double se = 0;
    double ae = 0;
    int count = 0;
    for (int i = fromInclusive; i < toExclusive; i++) {
      SparseExample e = examples.get(order[i]);
      forward(w, e, scratch, dropoutRng);
      double error = scratch.output - e.target();
      se += error * error;
      ae += Math.abs(error);
      accumulateGradient(w, g, e, scratch);
      count++;
    }
    return new BatchResult(se, ae, count);
  }

  private final class WorkerPool {
    private final ExecutorService executor;

    WorkerPool(ExecutorService executor) {
      this.executor = executor;
    }

    BatchResult trainBatch(
        NNUEWeights w,
        Gradients master,
        Gradients[] buffers,
        List<SparseExample> examples,
        Integer[] order,
        int from,
        int to) {
      int[][] ranges = chunkRanges(to - from);
      List<Future<BatchResult>> futures = new ArrayList<>(ranges.length);

      master.clear();
      for (int r = 0; r < ranges.length; r++) {
        Gradients part = buffers[r];
        part.clear();
        final int lo = from + ranges[r][0];
        final int hi = from + ranges[r][1];
        futures.add(executor.submit(() -> trainRange(w, part, examples, order, lo, hi)));
      }

      double se = 0;
      double ae = 0;
      int count = 0;
      for (int r = 0; r < ranges.length; r++) {
        try {
          BatchResult result = futures.get(r).get();
          se += result.squaredErrorSum();
          ae += result.absoluteErrorSum();
          count += result.examples();
          master.add(buffers[r]);
        } catch (Exception e) {
          throw new IllegalStateException("Training worker failed", e);
        }
      }

      master.scale(1.0 / Math.max(1, count));
      master.clipNorm(GRAD_CLIP_NORM);
      return new BatchResult(se, ae, count);
    }
  }

  private EvalStats evaluateParallel(
      ExecutorService executor, NNUEWeights w, List<SparseExample> examples) {
    int[][] ranges = chunkRanges(examples.size());
    List<Future<EvalStats>> futures = new ArrayList<>(ranges.length);
    for (int[] range : ranges) {
      futures.add(
          executor.submit(
              () -> {
                Scratch scratch = new Scratch();
                EvalStats stats = new EvalStats();
                for (int i = range[0]; i < range[1]; i++) {
                  SparseExample e = examples.get(i);
                  double out = forward(w, e, scratch, null);
                  stats.add(out - e.target(), e.hasEval());
                }
                return stats;
              }));
    }

    EvalStats total = new EvalStats();
    for (Future<EvalStats> future : futures) {
      try {
        EvalStats part = future.get();
        total.add(part);
      } catch (Exception e) {
        throw new IllegalStateException("Evaluation worker failed", e);
      }
    }
    total.average();
    return total;
  }

  private static final class EvalStats {
    double mse;
    double mae;
    int count;

    double mseEval;
    double maeEval;
    int countEval;

    double mseNoEval;
    double maeNoEval;
    int countNoEval;

    void add(double error, boolean eval) {
      double squared = error * error;
      double absolute = Math.abs(error);
      mse += squared;
      mae += absolute;
      count++;
      if (eval) {
        mseEval += squared;
        maeEval += absolute;
        countEval++;
      } else {
        mseNoEval += squared;
        maeNoEval += absolute;
        countNoEval++;
      }
    }

    void add(EvalStats other) {
      mse += other.mse;
      mae += other.mae;
      count += other.count;
      mseEval += other.mseEval;
      maeEval += other.maeEval;
      countEval += other.countEval;
      mseNoEval += other.mseNoEval;
      maeNoEval += other.maeNoEval;
      countNoEval += other.countNoEval;
    }

    void average() {
      mse = average(mse, count);
      mae = average(mae, count);
      mseEval = average(mseEval, countEval);
      maeEval = average(maeEval, countEval);
      mseNoEval = average(mseNoEval, countNoEval);
      maeNoEval = average(maeNoEval, countNoEval);
    }

    private static double average(double sum, int count) {
      return count == 0 ? Double.NaN : sum / count;
    }
  }

  private static final class Gradients {
    final double[][] inputWeights = new double[NNUEWeights.INPUT_SIZE][NNUEWeights.HIDDEN_SIZE];
    final double[] hiddenBias = new double[NNUEWeights.HIDDEN_SIZE];
    final double[][] hiddenWeights =
        new double[NNUEWeights.HIDDEN_SIZE][NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[] secondHiddenBias = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[][] secondHiddenWeights =
        new double[NNUEWeights.SECOND_HIDDEN_SIZE][NNUEWeights.THIRD_HIDDEN_SIZE];
    final double[] thirdHiddenBias = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    final double[] outputWeights = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    double outputBias;

    void clear() {
      clear(inputWeights);
      Arrays.fill(hiddenBias, 0.0);
      clear(hiddenWeights);
      Arrays.fill(secondHiddenBias, 0.0);
      clear(secondHiddenWeights);
      Arrays.fill(thirdHiddenBias, 0.0);
      Arrays.fill(outputWeights, 0.0);
      outputBias = 0.0;
    }

    void add(Gradients other) {
      add(inputWeights, other.inputWeights);
      add(hiddenBias, other.hiddenBias);
      add(hiddenWeights, other.hiddenWeights);
      add(secondHiddenBias, other.secondHiddenBias);
      add(secondHiddenWeights, other.secondHiddenWeights);
      add(thirdHiddenBias, other.thirdHiddenBias);
      add(outputWeights, other.outputWeights);
      outputBias += other.outputBias;
    }

    void scale(double factor) {
      scale(inputWeights, factor);
      scale(hiddenBias, factor);
      scale(hiddenWeights, factor);
      scale(secondHiddenBias, factor);
      scale(secondHiddenWeights, factor);
      scale(thirdHiddenBias, factor);
      scale(outputWeights, factor);
      outputBias *= factor;
    }

    double l2Norm() {
      return Math.sqrt(
          squaredNorm(inputWeights)
              + squaredNorm(hiddenBias)
              + squaredNorm(hiddenWeights)
              + squaredNorm(secondHiddenBias)
              + squaredNorm(secondHiddenWeights)
              + squaredNorm(thirdHiddenBias)
              + squaredNorm(outputWeights)
              + outputBias * outputBias);
    }

    void clipNorm(double maxNorm) {
      double norm = l2Norm();
      if (norm > maxNorm && norm > 0.0) scale(maxNorm / norm);
    }

    private static void clear(double[][] values) {
      for (double[] row : values) Arrays.fill(row, 0.0);
    }

    private static void add(double[][] target, double[][] source) {
      for (int i = 0; i < target.length; i++) add(target[i], source[i]);
    }

    private static void add(double[] target, double[] source) {
      for (int i = 0; i < target.length; i++) target[i] += source[i];
    }

    private static void scale(double[][] values, double factor) {
      for (double[] row : values) scale(row, factor);
    }

    private static void scale(double[] values, double factor) {
      for (int i = 0; i < values.length; i++) values[i] *= factor;
    }

    private static double squaredNorm(double[][] values) {
      double sum = 0.0;
      for (double[] row : values) sum += squaredNorm(row);
      return sum;
    }

    private static double squaredNorm(double[] values) {
      double sum = 0.0;
      for (double value : values) sum += value * value;
      return sum;
    }
  }

  private static final class AdamOptimizer {
    private final double[][] mInput = new double[NNUEWeights.INPUT_SIZE][NNUEWeights.HIDDEN_SIZE];
    private final double[] mHiddenBias = new double[NNUEWeights.HIDDEN_SIZE];
    private final double[][] mHidden =
        new double[NNUEWeights.HIDDEN_SIZE][NNUEWeights.SECOND_HIDDEN_SIZE];
    private final double[] mSecondBias = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    private final double[][] mSecond =
        new double[NNUEWeights.SECOND_HIDDEN_SIZE][NNUEWeights.THIRD_HIDDEN_SIZE];
    private final double[] mThirdBias = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    private final double[] mOutput = new double[NNUEWeights.THIRD_HIDDEN_SIZE];

    private final double[][] vInput = new double[NNUEWeights.INPUT_SIZE][NNUEWeights.HIDDEN_SIZE];
    private final double[] vHiddenBias = new double[NNUEWeights.HIDDEN_SIZE];
    private final double[][] vHidden =
        new double[NNUEWeights.HIDDEN_SIZE][NNUEWeights.SECOND_HIDDEN_SIZE];
    private final double[] vSecondBias = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    private final double[][] vSecond =
        new double[NNUEWeights.SECOND_HIDDEN_SIZE][NNUEWeights.THIRD_HIDDEN_SIZE];
    private final double[] vThirdBias = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    private final double[] vOutput = new double[NNUEWeights.THIRD_HIDDEN_SIZE];

    private double mOutputBias;
    private double vOutputBias;

    private long t;

    void step(NNUEWeights w, Gradients g, double lr) {
      t++;
      double bc1 = 1.0 - Math.pow(ADAM_BETA1, t);
      double bc2 = 1.0 - Math.pow(ADAM_BETA2, t);
      double scale = lr / bc1;
      double decay = lr * WEIGHT_DECAY;

      decayStep2D(w.inputWeights, g.inputWeights, mInput, vInput, bc2, scale, decay);
      step1D(w.hiddenBias, g.hiddenBias, mHiddenBias, vHiddenBias, bc2, scale, 0.0);
      decayStep2D(w.hiddenWeights, g.hiddenWeights, mHidden, vHidden, bc2, scale, decay);
      step1D(w.secondHiddenBias, g.secondHiddenBias, mSecondBias, vSecondBias, bc2, scale, 0.0);
      decayStep2D(
          w.secondHiddenWeights, g.secondHiddenWeights, mSecond, vSecond, bc2, scale, decay);
      step1D(w.thirdHiddenBias, g.thirdHiddenBias, mThirdBias, vThirdBias, bc2, scale, 0.0);
      step1D(w.outputWeights, g.outputWeights, mOutput, vOutput, bc2, scale, decay);

      mOutputBias = ADAM_BETA1 * mOutputBias + (1 - ADAM_BETA1) * g.outputBias;
      vOutputBias = ADAM_BETA2 * vOutputBias + (1 - ADAM_BETA2) * g.outputBias * g.outputBias;
      w.outputBias -= scale * mOutputBias / (Math.sqrt(vOutputBias / bc2) + ADAM_EPS);
    }

    private static void decayStep2D(
        double[][] params,
        double[][] grads,
        double[][] m,
        double[][] v,
        double bc2,
        double scale,
        double decay) {
      for (int i = 0; i < params.length; i++) {
        step1D(params[i], grads[i], m[i], v[i], bc2, scale, decay);
      }
    }

    private static void step1D(
        double[] params,
        double[] grads,
        double[] m,
        double[] v,
        double bc2,
        double scale,
        double decay) {
      for (int k = 0; k < params.length; k++) {
        double grad = grads[k];
        m[k] = ADAM_BETA1 * m[k] + (1 - ADAM_BETA1) * grad;
        v[k] = ADAM_BETA2 * v[k] + (1 - ADAM_BETA2) * grad * grad;
        params[k] -= scale * m[k] / (Math.sqrt(v[k] / bc2) + ADAM_EPS) + decay * params[k];
      }
    }
  }
}
