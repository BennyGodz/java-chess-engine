package chess.engine.training;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.nnue.NNUEFeatureExtractor;
import chess.engine.evaluation.nnue.NNUEWeights;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Trains the NNUE evaluation network FROM GAMES.
 *
 * <p>Every PGN file under {@code games/} is replayed move by move. Each position is labelled with a
 * BLEND of two recorded-game signals instead of the game's bare final result:
 *
 * <ol>
 *   <li><b>Search evaluation</b> (when the PGN carries a {@code { ev N }} comment for that move,
 *       written by {@link SelfPlayGenerator}): the root score of the position, converted through
 *       tanh({@code cp / EVAL_TARGET_SCALE_CP}) onto [-1, +1]. Self-play outcomes between equal
 *       engines are near-random coin flips decided by opening line and injected randomness, but
 *       search evaluations are stable POSITION facts — dense supervision that generalizes instead
 *       of noise that can only be memorised. This is why earlier runs showed train MSE falling
 *       (~0.06) while validation MSE climbed back to the baseline.
 *   <li><b>Temporal outcome target</b> (see {@link #temporalWhiteTarget}): an exponentially decaying
 *       final-result signal, kept as an anchor so absolute judgements stay calibrated to what
 *       actually happened.
 * </ol>
 *
 * <p>{@code target = (1 - RESULT_MIX) * evalTarget + RESULT_MIX * temporalTarget}. Rows without an
 * evaluation comment (older games) fall back to the pure temporal target at reduced gradient
 * weight ({@link #RESULT_ONLY_ROW_WEIGHT}).
 *
 * <p>FURTHER ANTI-OVERFITTING MEASURES:
 *
 * <ul>
 *   <li><b>Augmented twins keep their target.</b> Labels are MOVER-perspective. The rank-mirrored
 *       colour-swapped twin flips BOTH the perspective and the outcome, which cancel — so its
 *       correct label equals the original's.
 *   <li><b>Position-level deduplication across games.</b> Every unique position (hashed from its
 *       sparse feature vector) is assigned to exactly ONE set: first occurrence wins,
 *       deterministically. All occurrences inside a set merge into ONE row whose target is the MEAN
 *       target over occurrences, shrunk toward zero by {@link #LABEL_SHRINKAGE_PRIOR}.
 *   <li><b>Soft draw supervision.</b> Drawn-game rows without evaluations get reduced gradient
 *       weight ({@link #DRAW_EXAMPLE_WEIGHT}); evaluation-backed rows weigh fully because their
 *       label quality does not depend on how the game ended.
 *   <li><b>Light dropout</b> on hidden layers 2–3 during training passes only.
 *   <li>All positions are precomputed ONCE as sparse feature vectors, so epochs spend their time on
 *       gradient steps instead of re-parsing SAN.
 *   <li>The optimizer is Adam (adaptive per-parameter step sizes).
 *   <li>Batches are shuffled at POSITION level and processed in parallel across worker threads.
 *   <li>Warm starting considers BOTH saved checkpoints ({@code nnue_weights.bin} and {@code
 *       nnue_weights_best.bin}) and keeps whichever beats the baseline AND a fresh random
 *       initialization; a resumed run starts at a REDUCED learning rate so Adam's state reset does
 *       not immediately wreck a good network.
 * </ul>
 *
 * <p>CROSS-RUN STATE (the trainer remembers its best between invocations):
 *
 * <ul>
 *   <li>{@code validation_cache.bin} — the validation positions AND their position keys are
 *       serialized once and reused on every later run. Identical validation rows make MSE
 *       comparable across runs; the stored keys let later training builds exclude held-out
 *       positions even when new games are appended. The cache version must be bumped whenever
 *       feature-extraction or labelling semantics change so stale caches rebuild automatically.
 *   <li>{@code nnue_weights_best.bin} — the all-time best network ever measured on that fixed
 *       validation set. {@link chess.engine.evaluation.nnue.NNUEEvaluator} loads this file FIRST,
 *       so the engine always plays with the best net even if a later training run underperforms.
 *       The working file {@code nnue_weights.bin} is additionally RESTORED from the best net when a
 *       run finishes above the all-time best.
 *   <li>{@code training_state.txt} — machine-readable record of the all-time best numbers.
 *   <li>{@code training_history.log} — one appended line per new best and per finished run, so the
 *       actual best numbers are always visible WITHOUT scrolling back through the console.
 *   <li>The startup banner prints the stored all-time best before any training begins.
 * </ul>
 *
 * <p>If no games exist yet, a self-play batch is generated automatically.
 *
 * <p>Usage: {@code GameTrainer [epochs]}
 */
public final class GameTrainer {

  /** Overridable via -Dgames.root=... for tests. */
  static final String GAMES_ROOT = System.getProperty("games.root", "games");

  private static final String OUTPUT_FILE = "nnue_weights.bin";

  /** All-time best network; NNUEEvaluator prefers this file over OUTPUT_FILE. */
  private static final String BEST_WEIGHTS_FILE = "nnue_weights_best.bin";

  /** Cross-run tracking state (all-time-best numbers). Delete to reset tracking. */
  private static final String STATE_FILE = "training_state.txt";

  /** Append-only log of new bests and finished runs. */
  private static final String HISTORY_FILE = "training_history.log";

  /**
   * Serialized validation set. Fixed across runs so that validation MSE is comparable between runs;
   * delete it to rebuild validation from the current games/ contents.
   */
  private static final String VALIDATION_CACHE_FILE = "validation_cache.bin";

  private static final int VALIDATION_CACHE_MAGIC = 0x56414348; // "VACH"

  /**
   * v2 adds the per-example loss weight and the 64-bit position key used to keep held-out positions
   * out of the training set. v3 rebuilt targets under LABEL_SHRINKAGE_PRIOR 0.25 (v2 rows were
   * shrunk with prior 2.0 and would silently mismatch the training label scale). v5 rebuilt targets
   * under the smooth temporal outcome system. v6 rebuilt validation from EVALUATION-BEARING games
   * under the blended search-eval + temporal objective. v7 rebuilds again because v6 rows were
   * labelled with MISALIGNED evaluation comments (each eval attached ~10 plies too early, onto the
   * uncommented opening prefix) — a PgnGame parsing bug since fixed; reusing those rows would keep
   * training against noise. Bump again whenever feature/labelling semantics change.
   */
  private static final int VALIDATION_CACHE_VERSION = 7;

  /** Upper bound on cached validation examples so the cache stays small and fast to load. */
  private static final int VALIDATION_CACHE_MAX_EXAMPLES = 200_000;

  private static final DateTimeFormatter TIMESTAMP_FMT =
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final int DEFAULT_EPOCHS = 60;

  /*
   * Conservative configuration for the new temporal targets (smoother, lower-variance labels than
   * the old +-1 final-result scheme): smaller steps, larger batches, more frequent validation and
   * tighter patience so a diverging run is cut short early. Revisit ONLY after validating that the
   * new target system has closed the train/validation gap.
   */
  private static final int BATCH_SIZE = 1024;
  private static final double LEARNING_RATE = 1.5e-4;
  private static final double MIN_LEARNING_RATE = 2.0e-6;
  private static final double ADAM_BETA1 = 0.9;
  private static final double ADAM_BETA2 = 0.999;
  private static final double ADAM_EPS = 1.0e-8;

  /** Decoupled weight decay (AdamW style). */
  private static final double WEIGHT_DECAY = 0.04;

  /**
   * Fraction of {@link #LEARNING_RATE} used at the START of a warm-started run. Adam's moment
   * estimates reset between invocations, so full-size steps immediately knock a converged network
   * off its optimum; the reduced rate plus the usual decay schedule lets it settle first.
   */
  private static final double WARM_START_LR_FRACTION = 0.5;

  private static final double GRAD_CLIP_NORM = 2.0;
  private static final double LR_DECAY = 0.65;

  /*
   * Patience is counted in VALIDATIONS, but the constants below are expressed in EPOCHS and
   * converted at runtime: a multi-million-position dataset validates dozens of times per epoch
   * while a tiny one validates once, so fixed validation counts silently changed schedule
   * semantics with dataset size.
   */
  private static final int VALIDATE_EVERY_BATCHES = 50;

  /** Flat epochs tolerated before halving the learning rate. */
  private static final int LR_PATIENCE_EPOCHS = 2;

  /** Flat epochs tolerated before abandoning the run entirely. */
  private static final int EARLY_STOP_PATIENCE_EPOCHS = 8;

  /** Floors keep small datasets from twitching on every single epoch-end validation. */
  private static final int MIN_LR_PATIENCE = 20;

  private static final int MIN_EARLY_STOP_PATIENCE = 80;
  /*
   * Divergence guard: memorising nets walk validation UP while train MSE keeps falling, and
   * waiting out the full flat-epoch patience wastes whole epochs of compute before the rate
   * decays. When validation sits clearly above the run best for a few consecutive checkpoints,
   * decay the learning rate immediately (with a cooldown so halvings do not stack).
   *
   * The margin is RELATIVE to the predict-zero baseline rather than an absolute constant: the
   * baseline (= mean squared target) changed scale when final-result labels were replaced by
   * temporal targets, and any future relabelling would otherwise silently detune the trigger.
   */
  private static final double DIVERGENCE_MARGIN_RELATIVE = 0.05;
  private static final int DIVERGENCE_CHECKPOINTS = 4;
  private static final int DIVERGENCE_COOLDOWN = 12;
  /*
   * Validation noise between adjacent checkpoints is small but nonzero (dropout-free evaluation on
   * a fixed set keeps it tiny); 1e-4 discarded real mid-training improvements of warm-started
   * runs, which is one reason the overnight pipeline recorded no new bests for hours.
   */
  private static final double MIN_VALIDATION_IMPROVEMENT = 2e-5;
  private static final double VALIDATION_RATIO = 0.10;

  private static final int MIN_PLY = 6;
  private static final long SEED = 12345L;
  private static final int LOG_EVERY_BATCHES = 50;

  /**
   * Upper bound on sampled plies per game. After cross-game deduplication redundant rows cost far
   * less, but the cap still bounds replay memory and keeps end-to-end game coverage even.
   */
  private static final int POSITIONS_PER_GAME_CAP = 48;

  /** Hard cap on stored examples (including augmented twins) per set. */
  private static final int MAX_EXAMPLES = 3_000_000;

  /*
   * ======================= TEMPORAL TARGET SYSTEM =======================
   *
   * A position played `ply` half-moves into a game of `totalPlies` half-moves is labelled with a
   * BLEND of outcome evidence from different look-ahead windows instead of the bare final result:
   *
   *   component(h) = finalResult  if the game ended within the next h plies
   *                              (totalPlies - ply <= h)
   *                = 0            otherwise (nothing was decided inside the window)
   *
   *   future = NEAR_W*component(NEAR_H) + MEDIUM_W*component(MEDIUM_H) + LONG_W*component(LONG_H)
   *   target = FUTURE_OUTCOME_WEIGHT*future + FINAL_RESULT_WEIGHT*finalResult     (in [-1, +1])
   *
   * WHY: assigning +-1 to every ply of a decisive game claims an equal opening was ALREADY winning
   * because of a blunder dozens of plies later. Most of those labels are pure noise, and the only
   * way a network can fit noise is by memorising games — exactly the train-MSE-down /
   * validation-MSE-up pattern in the old logs. Horizon-limited outcomes are still 100% recorded-
   * game facts ("did this game actually reach its end inside the window, and who won it?") — no
   * engine and no synthetic evaluations anywhere — and they concentrate strong supervision where
   * it is trustworthy: near the point where the game actually tipped over.
   */
  private static final int NEAR_FUTURE_PLIES = 12;
  private static final int MEDIUM_FUTURE_PLIES = 30;
  private static final int LONG_FUTURE_PLIES = 60;

  /** Closer horizons carry more influence inside the future-outcome blend; weights sum to 1. */
  private static final double NEAR_FUTURE_WEIGHT = 0.50;

  private static final double MEDIUM_FUTURE_WEIGHT = 0.30;
  private static final double LONG_FUTURE_WEIGHT = 0.20;

  /**
   * Smooth temporal target parameters.
   *
   * <p>The previous target used hard jumps at 12, 30 and 60 plies. That makes nearly identical
   * positions receive noticeably different labels simply because they sit on opposite sides of a
   * horizon boundary. The smooth target removes those discontinuities while retaining recorded-game
   * only supervision.
   */
  private static final double TEMPORAL_BASE_WEIGHT = 0.10;
  private static final double TEMPORAL_FUTURE_WEIGHT = 0.90;
  private static final double TEMPORAL_DECAY_PLIES = 36.0;

  /*
   * ======================= SEARCH-EVAL TARGET BLEND =======================
   *
   * Self-play PGNs carry a per-move root score ({@code { ev N }}, centipawns, mover perspective)
   * written by SelfPlayGenerator at zero extra cost — the search already computed it to pick the
   * move. For any position WITH such a comment the label becomes
   *
   *   target = (1 - RESULT_MIX) * tanh(whiteEvalCp / EVAL_TARGET_SCALE_CP)
   *          +        RESULT_MIX    * temporalWhiteTarget(...)
   *
   * while positions WITHOUT one keep the pure temporal target at reduced gradient weight.
   *
   * WHY: self-play outcomes between two identical engines are near-random coin flips driven by
   * opening line and injected randomness; outcome-only labels are mostly game-specific noise the
   * network can only memorise (train MSE down, validation MSE up). Search evaluations are stable
   * facts ABOUT THE POSITION — the same dense-supervision principle Stockfish NNUE training uses —
   * so they generalize across games instead of overfitting them.
   */
  /** Centipawn scale of the tanh squash: +-350cp maps to ~+-0.96, keeping headroom for worse. */
  private static final double EVAL_TARGET_SCALE_CP = 350.0;

  /**
   * Weight of the temporal-outcome component inside blended labels; the remaining mass comes from
   * the search evaluation. Small by design: the eval already dominates and the outcome anchor only
   * calibrates absolute judgements toward what actually happened.
   */
  private static final double RESULT_MIX = 0.15;

  /**
   * Gradient weight multiplier for rows WITHOUT an evaluation comment (older games). Their
   * outcome-only labels are noisier than blended ones, so they contribute less per occurrence but
   * still train — the fallback keeps the whole historical corpus usable.
   */
  private static final double RESULT_ONLY_ROW_WEIGHT = 0.6;

  /*
   * Merged-row targets are means of k noisy samples shrunk toward zero by this many virtual
   * zero-outcome observations: target = sum / (count + prior). REDUCED from 0.25 because temporal
   * targets already have far lower variance than the old +-1 final-result labels; the prior now
   * only damps extreme low-count outliers. Cross-set positional leakage is handled by
   * DEDUPLICATION, not by the prior.
   */
  private static final double LABEL_SHRINKAGE_PRIOR = 0.05;

  /*
   * Gradient weight multiplier for rows coming from DRAWN games. A draw target of 0 is weaker
   * evidence than a decisive result: it may be a genuinely equal position, missed wins, or an
   * unconverted advantage, so draw rows pull less per occurrence. Decisive-game rows weigh exactly
   * 1.0 — the former ply-based ramp is gone because temporal targets already encode how close a
   * position was to being decided, making artificial phase reweighting unnecessary.
   */
  private static final double DRAW_EXAMPLE_WEIGHT = 0.75;

  /**
   * Inverted dropout keep-rate floor on hidden layers 2 and 3 during TRAINING forward passes only
   * (evaluation never drops). Raised slightly because single-occurrence rows still invite
   * memorisation; evaluation-backed labels reduce the pressure but do not remove it.
   */
  private static final double DROPOUT_RATE = 0.10;

  /** Maximum number of parallel chunk ranges; matches the gradient-buffer pool size. */
  private static final int MAX_CHUNKS = 16;

  private final int epochs;
  private final Random random;
  private final int threads;

  public GameTrainer(int epochs) {
    this.epochs = Math.max(1, epochs);
    this.random = new Random(SEED);
    this.threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
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
      System.out.println("No games found under " + GAMES_ROOT + "/ - generating self-play games.");
      new SelfPlayGenerator(
              SelfPlayGenerator.DEFAULT_GAMES, SelfPlayGenerator.DEFAULT_TIME_PER_MOVE_MS, SEED)
              .generate();
      pgnFiles = collectPgnFiles();
    }
    System.out.println("Found " + pgnFiles.size() + " PGN file(s).");

    List<ReplayableGame> games = loadGames(pgnFiles);
    System.out.println(
            "Parsed " + games.size() + " usable game(s) (" + malformedGames + " skipped).");
    if (games.size() < 8) {
      throw new IOException("Not enough usable games to train (need at least 8).");
    }

    /*
     * Deterministic content-based split: each game is hashed by its SAN move list, so a game's
     * assignment never changes when new self-play games are appended. (Shuffling a growing list
     * with a fixed seed reshuffles the WHOLE permutation, which silently changed the validation
     * set every pipeline iteration and made validation MSE incomparable between runs.)
     */
    List<ReplayableGame> trainingGames = new ArrayList<>();
    List<ReplayableGame> validationGames = new ArrayList<>();
    for (ReplayableGame game : games) {
      if (isValidationGame(game)) validationGames.add(game);
      else trainingGames.add(game);
    }
    if (validationGames.isEmpty()) {
      validationGames.add(trainingGames.remove(trainingGames.size() - 1));
    }

    /*
     * Process both sets in a content-derived order so that "first occurrence of a position wins"
     * deduplication yields byte-identical datasets regardless of filesystem walk order.
     */
    sortGamesDeterministically(trainingGames);
    sortGamesDeterministically(validationGames);

    NNUEFeatureExtractor extractor = new NNUEFeatureExtractor();

    long startNanos = System.nanoTime();

    /*
     * Validation comes FIRST and from the FIXED cache whenever possible: identical positions on
     * every run are what make "all-time best" comparisons across runs meaningful at all. Its
     * position keys are then excluded from the training build below, so a shared opening position
     * can never appear on both sides of the split — the leakage that previously let train MSE fall
     * forever while validation MSE sat at the baseline.
     */
    List<SparseExample> validation = loadOrBuildValidationCache(validationGames, extractor);

    List<SparseExample> training =
            buildExamples(trainingGames, extractor, "train", true, validationKeys, null);
    long loadSec = (System.nanoTime() - startNanos) / 1_000_000_000;

    if (training.isEmpty() || validation.isEmpty()) {
      throw new IOException("No trainable positions could be built from the parsed games.");
    }

    System.out.println(
            "Precomputed "
                    + training.size()
                    + " training example(s) (deduplicated; augmented twins included); validation has "
                    + validation.size()
                    + " fixed position(s) in "
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

  // ------------------------------------------------------------------
  // Game loading
  // ------------------------------------------------------------------

  private int malformedGames;

  /** Set by {@link #chooseStartingWeights} when training resumes from an existing checkpoint. */
  private boolean warmStarted;

  /** A parsed game ready to be replayed into training examples. */
  private record ReplayableGame(List<String> sanMoves, double[] evalCp, double whiteOutcome) {

    /** Search score in centipawns (mover perspective) for {@code ply}, or NaN when unannotated. */
    double evalCpAt(int ply) {
      return evalCp != null && ply < evalCp.length ? evalCp[ply] : Double.NaN;
    }

    /** True when at least one move of the game carries an evaluation comment. */
    boolean hasEvaluations() {
      if (evalCp == null) return false;
      for (double v : evalCp) if (!Double.isNaN(v)) return true;
      return false;
    }
  }

  /**
   * True when the game belongs in the validation set: the last {@code VALIDATION_RATIO} fraction of
   * hash buckets of its SAN move list. String.hashCode is specified and stable across JVM runs, so
   * the split is reproducible and stable as the game collection grows.
   */
  private static boolean isValidationGame(ReplayableGame game) {
    int bucket = Math.floorMod(gameKey(game).hashCode(), 100);
    return bucket < (int) Math.round(VALIDATION_RATIO * 100);
  }

  /** Stable content key of a game: its normalized SAN move list. */
  private static String gameKey(ReplayableGame game) {
    StringBuilder key = new StringBuilder();
    for (String san : game.sanMoves()) key.append(san).append(' ');
    return key.toString();
  }

  /** Orders games by content so deduplication and sampling are reproducible run to run. */
  private static void sortGamesDeterministically(List<ReplayableGame> games) {
    games.sort(Comparator.comparing(GameTrainer::gameKey));
  }

  private List<File> collectPgnFiles() throws IOException {
    Path root = Path.of(GAMES_ROOT);
    if (!Files.isDirectory(root)) return List.of();
    try (Stream<Path> stream = Files.walk(root)) {
      List<File> files = new ArrayList<>();
      stream
              .filter(p -> p.toString().toLowerCase().endsWith(".pgn"))
              .map(Path::toFile)
              .forEach(files::add);
      return files;
    }
  }

  private List<ReplayableGame> loadGames(List<File> pgnFiles) throws IOException {
    malformedGames = 0;
    List<ReplayableGame> games = new ArrayList<>();

    for (File file : pgnFiles) {
      String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
      for (PgnGame pgn : PgnGame.parseAll(text)) {
        Double outcome = whiteOutcomeOf(pgn.getResult());
        if (outcome == null || pgn.getSanMoves().size() <= MIN_PLY) {
          malformedGames++;
          continue;
        }
        games.add(new ReplayableGame(pgn.getSanMoves(), pgn.getEvalCp(), outcome));
      }
    }
    return games;
  }

  /** Maps a PGN result token to White's outcome: win +1, loss -1, draw 0. */
  private static Double whiteOutcomeOf(String result) {
    return switch (result) {
      case "1-0" -> 1.0;
      case "0-1" -> -1.0;
      case "1/2-1/2" -> 0.0;
      default -> null; // "*" or unknown: no supervision signal
    };
  }

  // ------------------------------------------------------------------
  // Example building
  // ------------------------------------------------------------------

  /**
   * One training row in sparse form: only the non-zero entries of the feature vector are stored,
   * which makes both forward and backward passes dramatically cheaper.
   *
   * <p>{@code target} is the averaged, shrunk side-to-move TEMPORAL outcome label (see {@link
   * #temporalWhiteTarget}), {@code weight} scales the row's gradient contribution ONLY (reported
   * metrics stay unweighted), and {@code positionKey} identifies the chess position so rows can be
   * kept out of the held-out set ({@code 0} for augmented twins, which never take part in exclusion
   * checks).
   */
  record SparseExample(
          int[] indices, float[] values, double target, float weight, long positionKey) {}

  /** One sampled position awaiting deduplication, with its temporal target and gradient weight. */
  private record Candidate(long key, int[] indices, float[] values, double target, float weight) {}

  /**
   * Replays games into DEDUPLICATED sparse training rows.
   *
   * <p>Every sampled position is hashed from its sparse features; repeated occurrences —
   * overwhelmingly shared opening lines across self-play games — collapse into ONE row whose label
   * is the MEAN temporal target over all occurrences, shrunk toward zero by a small prior. That
   * averages away game-specific noise (blunders, turnarounds) and leaves the systematic part —
   * position quality — plus it removes the train/validation positional leakage that previously made
   * validation MSE unimprovable.
   *
   * <p>When {@code excludeKeys} carries the fixed validation set's position keys, occurrences of
   * those positions are dropped instead of trained on. When {@code augment} is set, every emitted
   * row additionally gains its rank-mirrored colour-swapped twin with the SAME target — from the
   * mover's perspective the mirrored position is worth exactly as much as the original (perspective
   * and outcome flip together and cancel), so the same chess knowledge must hold from the other
   * side of the board.
   *
   * @param collectedKeys optional sink receiving the key of every emitted base row (used to seed
   *     the validation exclusion set)
   */
  private List<SparseExample> buildExamples(
          List<ReplayableGame> games,
          NNUEFeatureExtractor extractor,
          String label,
          boolean augment,
          Set<Long> excludeKeys,
          Set<Long> collectedKeys) {
    PositionTable table = new PositionTable(Math.max(1024, games.size() * POSITIONS_PER_GAME_CAP));
    List<Candidate> candidates = new ArrayList<>(256);
    Board board = new Board();
    int sampledPositions = 0;
    int excludedPositions = 0;
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

          /*
           * BLENDED TARGET: search evaluation (when the move carries a { ev N } comment) dominates,
           * with the temporal outcome as anchor (see {@link #temporalWhiteTarget}). Both components
           * are computed from WHITE's perspective and then FLIPPED for black-to-move positions, so
           * every label stays on the mover-perspective [-1, +1] scale that the tanh network output
           * predicts.
           *
           * Note: totalPlies counts the full recorded SAN list. If a game contains a corrupt tail
           * move the replay stops early; such games are rare, and the effect is only a slightly
           * softer target near their truncated end.
           */
          double evalCp = game.evalCpAt(ply);
          boolean hasEval = !Double.isNaN(evalCp);
          double whitePerspectiveTarget =
                  blendedWhiteTarget(whiteOutcome, totalPlies, ply, hasEval ? evalCp : 0.0, hasEval);
          double sideToMoveTarget =
                  board.isWhiteToMove() ? whitePerspectiveTarget : -whitePerspectiveTarget;
          candidates.add(
                  new Candidate(
                          sparse.positionKey(),
                          sparse.indices(),
                          sparse.values(),
                          sideToMoveTarget,
                          (float) exampleWeight(whiteOutcome, sideToMoveTarget, hasEval)));
        }
        board.playMove(move);
      }

      /*
       * Exact evenly-spaced sample of the game's positions (keeps opening AND endgame coverage,
       * never samples two adjacent plies unless the game is shorter than the cap).
       */
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
                candidate.weight());
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

    /*
     * Emit one row per unique position: label = mean occurrence outcome shrunk toward 0, weight =
     * mean occurrence gradient weight. Slot order is deterministic given the insertion sequence.
     */ List<SparseExample> examples = new ArrayList<>();
    int mergedRows = 0;
    for (int slot = 0; slot < table.capacity(); slot++) {
      if (!table.isOccupied(slot)) continue;
      if (examples.size() >= MAX_EXAMPLES) {
        System.out.println(
                "Example cap " + MAX_EXAMPLES + " reached while emitting " + label + " rows.");
        break;
      }
      int count = table.countAt(slot);
      if (count > 1) mergedRows++;
      double target = table.targetSumAt(slot) / (count + LABEL_SHRINKAGE_PRIOR);
      float weight = (float) Math.min(1.0d, table.weightSumAt(slot) / count);
      long key = table.keyAt(slot);
      examples.add(
              new SparseExample(table.indicesAt(slot), table.valuesAt(slot), target, weight, key));
      if (collectedKeys != null) collectedKeys.add(key);

      if (augment && examples.size() < MAX_EXAMPLES) {
        // The twin is the colour-swapped rank mirror, a faithful encoding of the mirrored
        // position INCLUDING its flipped side to move. Labels are mover-perspective outcomes,
        // and the mirrored position's mover wins exactly as often as the original's — so the
        // twin KEEPS the target. Negating it here (a past bug) taught every twin the exact
        // opposite of its position and drove the whole network toward predicting zero.
        SparseExample twin =
                sparsify(
                        NNUEFeatureExtractor.rotated(densify(table.indicesAt(slot), table.valuesAt(slot))));
        examples.add(new SparseExample(twin.indices(), twin.values(), target, weight, 0L));
      }
    }

    double duplicationFactor =
            table.size() == 0 ? 1.0 : (double) (sampledPositions - excludedPositions) / table.size();
    System.out.printf(
            "[%s] %d game(s): %d sampled position(s), %d skipped (held-out overlap), %d unique"
                    + " (%.2fx duplicated), %d merged-label row(s), %d row(s) emitted.%n",
            label,
            games.size(),
            sampledPositions,
            excludedPositions,
            table.size(),
            duplicationFactor,
            mergedRows,
            examples.size());
    return examples;
  }

  /**
   * Computes the blended TEMPORAL target for the position before half-move number {@code ply}
   * (0-based) of a game whose recorded movetext contains {@code totalPlies} half-moves, from
   * WHITE's perspective, guaranteed to lie in [-1, +1].
   *
   * <p>Each horizon contributes the true game result ONLY if the game actually finished within that
   * many further half-moves ({@code totalPlies - ply <= horizon}); otherwise nothing was decided
   * inside the window and it contributes 0 ("undecided"). The three horizon outcomes are combined
   * with decreasing influence and anchored on the final result:
   *
   * <pre>
   * future = 0.50 * near(12) + 0.30 * medium(30) + 0.20 * long(60)
   * target = 0.85 * future + 0.15 * finalResult
   * </pre>
   *
   * <p>Concretely, a decisive game (|result| = 1) yields targets of magnitude 0.15 for positions
   * more than 60 plies before the end, 0.32 within [30, 60], 0.575 within [12, 30] and the full ±1
   * only in the last 12 plies; drawn games yield exactly 0 everywhere. Compare this with the old
   * scheme where EVERY position received the bare ±1/0: supervision strength now grows as the game
   * approaches its actual decision point instead of being smeared uniformly over all plies.
   *
   * <p>This is deliberately the ONLY place target values are produced, and it uses nothing but the
   * recorded game — no engine evaluation is simulated anywhere. The caller flips the sign for
   * black-to-move positions.
   */
  private static double temporalWhiteTarget(double whiteOutcome, int totalPlies, int ply) {
    if (whiteOutcome == 0.0) return 0.0;

    int pliesLeft = Math.max(0, totalPlies - ply);

    /*
     * Continuous decay instead of hard 12/30/60-ply jumps. At the end of the game the target is
     * exactly the recorded result. Far from the result it smoothly approaches the small anchor.
     * This keeps useful signal throughout the game without claiming that an opening was already a
     * forced win.
     */
    double futureSignal = Math.exp(-pliesLeft / TEMPORAL_DECAY_PLIES);
    double strength =
            TEMPORAL_BASE_WEIGHT + TEMPORAL_FUTURE_WEIGHT * futureSignal;

    return whiteOutcome * Math.min(1.0, Math.max(0.0, strength));
  }

  /**
   * Final WHITE-perspective label for one occurrence: {@code tanh(evalCp / EVAL_TARGET_SCALE_CP)}
   * blended with the temporal outcome when the move carries a search-evaluation comment, otherwise
   * just the temporal outcome. Guaranteed to lie in [-1, +1]. The caller flips the sign for
   * black-to-move positions.
   */
  private static double blendedWhiteTarget(
          double whiteOutcome, int totalPlies, int ply, double evalCp, boolean hasEval) {
    double temporal = temporalWhiteTarget(whiteOutcome, totalPlies, ply);
    if (!hasEval) return temporal;

    double evalTarget = Math.tanh(evalCp / EVAL_TARGET_SCALE_CP);
    return (1.0 - RESULT_MIX) * evalTarget + RESULT_MIX * temporal;
  }

  /**
   * Gradient weight for one occurrence of a position.
   *
   * <p>Evaluation-backed rows weigh exactly 1.0: their labels are stable position facts whose
   * quality does not depend on how the game ended. Result-only rows (older PGNs) are down-weighted
   * by {@link #RESULT_ONLY_ROW_WEIGHT}, drawn games further by {@link #DRAW_EXAMPLE_WEIGHT}, and
   * very early positions by a signal floor, because outcome-only evidence grows with proximity to
   * the actual decision.
   */
  private static double exampleWeight(double whiteOutcome, double target, boolean hasEval) {
    if (hasEval) return 1.0;

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
    return new SparseExample(indices, values, 0.0, 1.0f, positionKey(indices, values));
  }

  /**
   * 64-bit content key of a sparse feature vector. Extracting the same board twice yields identical
   * index/value sequences, hence identical keys; collisions between DIFFERENT positions are
   * negligible at 64 bits for datasets of a few million rows.
   */
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
    if (h == 0L) h = Long.MIN_VALUE; // reserve 0 as the table's empty-slot marker
    return h;
  }

  /** Rebuilds the dense feature vector of a stored sparse row (mirror augmentation input). */
  private static double[] densify(int[] indices, float[] values) {
    double[] dense = new double[NNUEFeatureExtractor.INPUT_SIZE];
    for (int n = 0; n < indices.length; n++) dense[indices[n]] = values[n];
    return dense;
  }

  /**
   * Insert-only open-addressing map from position key to aggregated label statistics plus the
   * first-seen sparse features. Slot-order iteration is deterministic given the insertion sequence,
   * which itself is deterministic because games are sorted by content before replay.
   */
  private static final class PositionTable {
    private static final long EMPTY = 0L;

    private long[] keys;
    private int[][] slotIndices;
    private float[][] slotValues;
    private double[] targetSum;
    private double[] weightSum;
    private int[] counts;
    private int size;

    PositionTable(int expectedEntries) {
      int capacity = 4096;
      // Leave headroom below a ~60% load factor for linear probing.
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
      size = 0;
    }

    void add(long key, int[] indices, float[] values, double target, double weight) {
      if ((size + 1) * 5L >= keys.length * 3L) grow();
      int mask = keys.length - 1;
      int slot = spread(key) & mask;
      while (true) {
        long occupant = keys[slot];
        if (occupant == EMPTY) {
          keys[slot] = key;
          slotIndices[slot] = indices;
          slotValues[slot] = values;
          targetSum[slot] = target;
          weightSum[slot] = weight;
          counts[slot] = 1;
          size++;
          return;
        }
        if (occupant == key) {
          targetSum[slot] += target;
          weightSum[slot] += weight;
          counts[slot]++;
          return;
        }
        slot = (slot + 1) & mask;
      }
    }

    private void grow() {
      long[] oldKeys = keys;
      int[][] oldIndices = slotIndices;
      float[][] oldValues = slotValues;
      double[] oldTargetSum = targetSum;
      double[] oldWeightSum = weightSum;
      int[] oldCounts = counts;
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

    double targetSumAt(int slot) {
      return targetSum[slot];
    }

    double weightSumAt(int slot) {
      return weightSum[slot];
    }

    int countAt(int slot) {
      return counts[slot];
    }

    /** Extra avalanche so structured keys spread across the whole table. */
    private static int spread(long key) {
      long h = key * 0x9E3779B97F4A7C15L;
      h ^= h >>> 32;
      return (int) (h ^ (h >>> 16));
    }
  }

  // ------------------------------------------------------------------
  // Fixed validation cache
  // ------------------------------------------------------------------

  /** Position keys of the FIXED validation set; training builds must never reuse these rows. */
  private Set<Long> validationKeys = Collections.emptySet();

  private static Set<Long> collectKeys(List<SparseExample> examples) {
    Set<Long> keys = new HashSet<>(Math.max(16, examples.size() * 2));
    for (SparseExample e : examples) keys.add(e.positionKey());
    return keys;
  }

  /**
   * Returns the fixed validation set: loaded from {@link #VALIDATION_CACHE_FILE} when present,
   * otherwise built from the current validation games, subsampled deterministically and cached.
   *
   * <p>When building, games WITH evaluation comments are preferred so the measured objective
   * matches what training optimizes; result-only games are used only when no annotated game exists
   * at all. Either path also populates {@link #validationKeys} so later training builds can exclude
   * held-out positions even when thousands of new self-play games replay the same opening lines.
   *
   * <p>A corrupted or outdated cache (version/architecture mismatch) is detected and rebuilt
   * automatically; bump {@link #VALIDATION_CACHE_VERSION} whenever feature or labelling semantics
   * change.
   */
  private List<SparseExample> loadOrBuildValidationCache(
          List<ReplayableGame> validationGames, NNUEFeatureExtractor extractor) throws IOException {
    File cache = new File(VALIDATION_CACHE_FILE);
    List<SparseExample> cached = loadValidationCache(cache);
    if (cached != null && !cached.isEmpty()) {
      validationKeys = collectKeys(cached);
      System.out.printf(
              "Reusing FIXED validation set from %s (%d positions); cross-run MSE comparisons stay"
                      + " valid and those %d position key(s) stay out of training.%n",
              VALIDATION_CACHE_FILE, cached.size(), validationKeys.size());
      return cached;
    }

    List<ReplayableGame> evalGames = new ArrayList<>();
    for (ReplayableGame game : validationGames) if (game.hasEvaluations()) evalGames.add(game);
    List<ReplayableGame> source = evalGames.isEmpty() ? validationGames : evalGames;
    System.out.printf(
            "Building validation from %d game(s) (%d with evaluation comments).%n",
            source.size(), evalGames.size());

    List<SparseExample> built = buildExamples(source, extractor, "validation", false, null, null);
    built = subsampleStride(built, VALIDATION_CACHE_MAX_EXAMPLES);
    saveValidationCache(cache, built);
    validationKeys = collectKeys(built);
    System.out.printf(
            "Created FIXED validation set %s (%d positions). It will be reused on every future run;"
                    + " delete the file to rebuild it from the current games.%n",
            VALIDATION_CACHE_FILE, built.size());
    return built;
  }

  /** Keeps at most {@code max} evenly spaced examples (deterministic stride sampling). */
  private static List<SparseExample> subsampleStride(List<SparseExample> examples, int max) {
    if (examples.size() <= max) return examples;
    int stride = (examples.size() + max - 1) / max;
    List<SparseExample> out = new ArrayList<>(max);
    for (int i = 0; i < examples.size(); i += stride) out.add(examples.get(i));
    return out;
  }

  private static void saveValidationCache(File file, List<SparseExample> examples)
          throws IOException {
    try (DataOutputStream out =
                 new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      out.writeInt(VALIDATION_CACHE_MAGIC);
      out.writeInt(VALIDATION_CACHE_VERSION);
      out.writeInt(NNUEWeights.INPUT_SIZE);
      out.writeInt(examples.size());
      for (SparseExample e : examples) {
        out.writeInt(e.indices().length);
        for (int index : e.indices()) out.writeInt(index);
        for (float value : e.values()) out.writeFloat(value);
        out.writeDouble(e.target());
        out.writeFloat(e.weight());
        out.writeLong(e.positionKey());
      }
    }
  }

  /** Returns the cached validation set, or null when missing/corrupt/incompatible. */
  private static List<SparseExample> loadValidationCache(File file) {
    if (!file.isFile()) return null;
    try (DataInputStream in =
                 new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      int magic = in.readInt();
      int version = in.readInt();
      int inputSize = in.readInt();
      if (magic != VALIDATION_CACHE_MAGIC
              || version != VALIDATION_CACHE_VERSION
              || inputSize != NNUEWeights.INPUT_SIZE) {
        throw new IOException("unsupported cache format");
      }
      int count = in.readInt();
      if (count <= 0 || count > 5_000_000) throw new IOException("implausible example count");

      List<SparseExample> examples = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        int n = in.readInt();
        if (n <= 0) throw new IOException("corrupt example entry");
        int[] indices = new int[n];
        float[] values = new float[n];
        for (int k = 0; k < n; k++) indices[k] = in.readInt();
        for (int k = 0; k < n; k++) values[k] = in.readFloat();
        double target = in.readDouble();
        float weight = in.readFloat();
        long key = in.readLong();
        examples.add(new SparseExample(indices, values, target, weight, key));
      }
      if (in.read() != -1) throw new IOException("trailing bytes");
      return examples;
    } catch (IOException e) {
      System.out.println("Validation cache unusable (" + e.getMessage() + "); rebuilding it.");
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Cross-run state (all-time best) + history log
  // ------------------------------------------------------------------

  /** Persisted all-time-best metrics; individual fields may be absent on first use. */
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
      if (bestValidationMse != null)
        p.setProperty("best.validation.mse", bestValidationMse.toString());
      if (bestValidationMae != null)
        p.setProperty("best.validation.mae", bestValidationMae.toString());
      if (bestBaselineMse != null) p.setProperty("best.baseline.mse", bestBaselineMse.toString());
      if (bestTrainExamples != null)
        p.setProperty("best.train.examples", bestTrainExamples.toString());
      if (bestValidationExamples != null)
        p.setProperty("best.validation.examples", bestValidationExamples.toString());
      if (bestGames != null) p.setProperty("best.games", bestGames.toString());
      if (bestTimestamp != null) p.setProperty("best.timestamp", bestTimestamp);
      if (lastRunBestMse != null) p.setProperty("last.run.best.mse", lastRunBestMse.toString());
      if (lastRunTimestamp != null) p.setProperty("last.run.timestamp", lastRunTimestamp);
      try (OutputStream out = new FileOutputStream(file)) {
        p.store(out, "GameTrainer cross-run state (all-time best). Delete to reset tracking.");
      }
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

  // ------------------------------------------------------------------
  // Training
  // ------------------------------------------------------------------

  private void train(
          ExecutorService executor,
          List<SparseExample> training,
          List<SparseExample> validation,
          int gameCount)
          throws IOException {

    double baselineMse = meanSquareTarget(validation);

    /*
     * Divergence-trigger margin scales with THIS label set: temporal targets have a much lower
     * predict-zero baseline than the old +-1 final-result labels, and a fixed absolute margin
     * would either never fire or fire constantly. See DIVERGENCE_MARGIN_RELATIVE.
     */
    double divergenceMargin = DIVERGENCE_MARGIN_RELATIVE * baselineMse;

    /*
     * Reconcile the persisted all-time best with reality on THIS fixed validation set. The saved
     * best network is re-measured directly, so the banner number is always something this exact
     * validation set reproduces — not a stale figure inherited from an older dataset.
     */
    TrainingState state = TrainingState.load(new File(STATE_FILE));
    File bestFile = new File(BEST_WEIGHTS_FILE);
    double allTimeBestMse = Double.POSITIVE_INFINITY;
    double allTimeBestMae = Double.NaN;

    if (bestFile.isFile()) {
      try {
        EvalStats stats = evaluateParallel(executor, NNUEWeights.load(bestFile), validation);
        allTimeBestMse = stats.mse;
        allTimeBestMae = stats.mae;
        if (state.bestValidationMse != null
                && Math.abs(state.bestValidationMse - allTimeBestMse) > 5.0e-3) {
          System.out.printf(
                  "Recorded all-time best (%.4f) disagrees with %s measured on the current fixed"
                          + " validation set (%.4f); adopting the measured value.%n",
                  state.bestValidationMse, BEST_WEIGHTS_FILE, allTimeBestMse);
        }
        state.bestValidationMse = allTimeBestMse;
        state.bestValidationMae = allTimeBestMae;
        state.bestBaselineMse = baselineMse;
      } catch (IOException e) {
        System.err.println("Could not read " + BEST_WEIGHTS_FILE + ": " + e.getMessage());
        if (state.bestValidationMse != null) {
          // Best weights file was lost but the recorded number survives; keep tracking from it.
          allTimeBestMse = state.bestValidationMse;
          System.out.println(
                  "Keeping the recorded all-time best number, but the weights file must be"
                          + " regenerated before the engine prefers it again.");
        }
      }
    } else if (state.bestValidationMse != null) {
      allTimeBestMse = state.bestValidationMse;
      allTimeBestMae = state.bestValidationMae != null ? state.bestValidationMae : Double.NaN;
      System.out.printf(
              "%s is missing; the recorded all-time best (%.4f) is kept as the target to beat.%n",
              BEST_WEIGHTS_FILE, allTimeBestMse);
    }

    // ----------------------------------------------------------------
    // Startup banner: the actual best numbers, visible without scrolling.
    // ----------------------------------------------------------------
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

    // Reused per-chunk gradient accumulators (~13 MB total): allocating them fresh every batch
    // turned into pure GC pressure and slowed epochs down.
    Gradients[] buffers = new Gradients[MAX_CHUNKS];
    for (int i = 0; i < buffers.length; i++) buffers[i] = new Gradients();

    /*
     * A resumed run starts slower: Adam's moments are empty, so full-size first steps would
     * immediately knock a converged network off its optimum (the overnight pipeline's runs kept
     * degrading their warm start within the first few hundred batches).
     */
    double lr = warmStarted ? LEARNING_RATE * WARM_START_LR_FRACTION : LEARNING_RATE;
    NNUEWeights bestWeights = deepCopy(weights);
    double bestValidationLoss = evaluateParallel(executor, weights, validation).mse;
    int checkpointsSinceBest = 0;
    int divergingCheckpoints = 0;
    int divergeCooldown = 0;
    boolean stopped = false;

    Integer[] order = new Integer[training.size()];
    for (int i = 0; i < order.length; i++) order[i] = i;

    /*
     * Convert epoch-based patience into validation counts for THIS dataset, so the schedule
     * means the same thing whether an epoch has 5 batches or 5860.
     */
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

        /*
         * Validate every few batches AND at epoch end: outcome labels overfit fast, so the
         * best snapshot is usually mid-epoch, long before train MSE looks impressive.
         */
        boolean epochEnd = from + BATCH_SIZE >= order.length;
        boolean checkpointDue = batchIndex % VALIDATE_EVERY_BATCHES == 0 || epochEnd;
        if (checkpointDue) {
          EvalStats validationStats = evaluateParallel(executor, weights, validation);
          checkpointsSinceBest++;

          /*
           * Always show the CURRENT validation value next to both reference points, so the live
           * metric is visible even when nothing new is happening.
           */
          System.out.printf(
                  "  [val] epoch %d batch %d | validation MSE %.4f (run best %.4f | all-time %s"
                          + " | baseline %.4f)%n",
                  epoch,
                  batchIndex,
                  validationStats.mse,
                  bestValidationLoss,
                  fmtOrNone(allTimeBestMse),
                  baselineMse);

          if (validationStats.mse < bestValidationLoss - MIN_VALIDATION_IMPROVEMENT) {
            bestValidationLoss = validationStats.mse;
            bestWeights = deepCopy(weights);
            checkpointsSinceBest = 0;
            bestWeights.save(new File(OUTPUT_FILE));

            boolean newAllTimeBest =
                    validationStats.mse < allTimeBestMse - MIN_VALIDATION_IMPROVEMENT;
            if (newAllTimeBest) {
              double previousAllTime = allTimeBestMse;
              allTimeBestMse = validationStats.mse;
              allTimeBestMae = validationStats.mae;

              // All-time snapshot: the file the ENGINE actually loads.
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
                              "%s | NEW BEST  | valMse %.4f | prevAllTime %s | baseline %.4f"
                                      + " | trainEx %d | valEx %d | games %d",
                              state.bestTimestamp,
                              allTimeBestMse,
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

          /*
           * Divergence guard (measured against the possibly-just-updated run best, so a fresh
           * improvement always resets it): memorisation shows up as validation drifting ABOVE
           * the run best while train keeps falling — decay the rate now instead of after a
           * full patience window of wasted epochs.
           */
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

    // ----------------------------------------------------------------
    // Finalize: protect the working file, persist state, print summary.
    // ----------------------------------------------------------------
    bestWeights.save(new File(OUTPUT_FILE));

    if (bestFile.isFile() && bestValidationLoss > allTimeBestMse + 1.0e-9) {
      // This run ended ABOVE the all-time best: never let the working file regress.
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
                    "%s | RUN DONE  | runBest %.4f | allTime %s | baseline %.4f | epochs %d | games %d",
                    state.lastRunTimestamp,
                    bestValidationLoss,
                    fmtOrNone(allTimeBestMse),
                    baselineMse,
                    epochs,
                    gameCount));

    System.out.println();
    System.out.println("======================== TRAINING SUMMARY ========================");
    System.out.printf("Run best validation MSE      : %.4f%n", bestValidationLoss);
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
    System.out.println("==================================================================");
  }

  /**
   * Picks the starting point for training. BOTH saved checkpoints are considered — the working
   * file and the protected all-time-best file — and whichever evaluates better on the fixed
   * validation set survives, provided it beats the trivial predict-zero baseline AND a fresh random
   * initialization (a degenerate net can score well by outputting tiny values everywhere, which
   * says nothing about chess). Previously only the working file was consulted, so progress stored
   * exclusively in the best checkpoint could be thrown away.
   */
  private NNUEWeights chooseStartingWeights(
          ExecutorService executor, List<SparseExample> validation, double baselineMse) {
    List<NNUEWeights> candidates = new ArrayList<>();
    for (String name : new String[] {OUTPUT_FILE, BEST_WEIGHTS_FILE}) {
      NNUEWeights w = loadWeightsFile(name);
      if (w != null) candidates.add(w);
    }
    NNUEWeights fresh = NNUEWeights.random(SEED ^ System.nanoTime());

    if (candidates.isEmpty()) {
      System.out.println("Starting from a fresh random initialization.");
      return fresh;
    }

    NNUEWeights best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (NNUEWeights candidate : candidates) {
      double score = evaluateParallel(executor, candidate, validation).mse;
      String origin = candidate == candidates.get(0) ? OUTPUT_FILE : BEST_WEIGHTS_FILE;
      if (score < baselineMse && score <= bestScore) {
        bestScore = score;
        best = candidate;
        System.out.printf(
                "Candidate %s: validation MSE %.4f (baseline %.4f) - eligible.%n",
                origin, score, baselineMse);
      } else {
        System.out.printf(
                "Candidate %s: validation MSE %.4f (baseline %.4f) - rejected.%n",
                origin, score, baselineMse);
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

  // ------------------------------------------------------------------
  // Network math
  // ------------------------------------------------------------------

  /** Reusable activation/error buffers so neither pass allocates per example. */
  private static final class Scratch {
    final double[] h1 = new double[NNUEWeights.HIDDEN_SIZE];
    final double[] h2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[] h3 = new double[NNUEWeights.THIRD_HIDDEN_SIZE];
    final double[] dh1 = new double[NNUEWeights.HIDDEN_SIZE];
    final double[] dh2 = new double[NNUEWeights.SECOND_HIDDEN_SIZE];
    final double[] dh3 = new double[NNUEWeights.THIRD_HIDDEN_SIZE];

    double output;
  }

  /**
   * Forward pass writing activations into {@code s}; returns the tanh-squashed output.
   *
   * <p>When {@code dropoutRng} is non-null (training passes), inverted dropout is applied to hidden
   * layers 2 and 3; evaluation passes must pass {@code null}.
   */
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
    for (int m = 0; m < h2.length; m++) {
      double sum = w.secondHiddenBias[m];
      for (int k = 0; k < h1.length; k++) sum += h1[k] * w.hiddenWeights[k][m];
      h2[m] = sum > 0.0 ? sum : 0.0;
    }
    if (dropoutRng != null) applyDropout(h2, dropoutRng);

    double[] h3 = s.h3;
    for (int j = 0; j < h3.length; j++) {
      double sum = w.thirdHiddenBias[j];
      for (int m = 0; m < h2.length; m++) sum += h2[m] * w.secondHiddenWeights[m][j];
      h3[j] = sum > 0.0 ? sum : 0.0;
    }
    if (dropoutRng != null) applyDropout(h3, dropoutRng);

    double raw = w.outputBias;
    for (int j = 0; j < h3.length; j++) raw += h3[j] * w.outputWeights[j];

    s.output = Math.tanh(raw);
    return s.output;
  }

  /**
   * Inverted dropout: dropped activations become exactly 0, which also removes their backward
   * contribution automatically (every gradient term gates on activation &gt; 0), and survivors are
   * scaled by 1/keep-rate so evaluation needs no rescaling.
   */
  private static void applyDropout(double[] activations, Random rng) {
    double scale = 1.0 / (1.0 - DROPOUT_RATE);
    for (int i = 0; i < activations.length; i++) {
      if (rng.nextDouble() < DROPOUT_RATE) activations[i] = 0.0;
      else activations[i] *= scale;
    }
  }

  /**
   * Adds the gradients of L = weight·(output - target)^2 for ONE example into the accumulator.
   *
   * <p>The per-example {@code weight} (1.0 for decisive games, softened for draws — see {@link
   * #exampleWeight}) scales the whole gradient; reported error metrics elsewhere remain unweighted.
   * Chain rule, layer by layer:
   *
   * <pre>
   * dOut     = weight · 2(output - target) · (1 - output^2)    tanh'
   * dh3[j]   = dOut * Wo[j],          gated by h3[j] > 0       ReLU' (+ dropout mask)
   * dh2[m]   = sum_j W2[m][j]*dh3[j], gated by h2[m] > 0
   * dh1[k]   = sum_m W1[k][m]*dh2[m], gated by h1[k] > 0
   * </pre>
   */
  private static void accumulateGradient(NNUEWeights w, Gradients g, SparseExample e, Scratch s) {
    double outputError = s.output - e.target();
    double dOut = 2.0 * outputError * (1.0 - s.output * s.output) * e.weight();

    double[] h1 = s.h1;
    double[] h2 = s.h2;
    double[] h3 = s.h3;

    for (int j = 0; j < h3.length; j++) g.outputWeights[j] += dOut * h3[j];
    g.outputBias += dOut;

    double[] dh3 = s.dh3;
    for (int j = 0; j < h3.length; j++) {
      dh3[j] = h3[j] > 0.0 ? dOut * w.outputWeights[j] : 0.0;
      g.thirdHiddenBias[j] += dh3[j];
    }

    double[] dh2 = s.dh2;
    for (int m = 0; m < h2.length; m++) {
      double sum = 0.0;
      if (h2[m] > 0.0) {
        double[] row = w.secondHiddenWeights[m];
        for (int j = 0; j < h3.length; j++) sum += row[j] * dh3[j];
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

  // ------------------------------------------------------------------
  // Parallel execution helpers
  // ------------------------------------------------------------------

  /** Splits work into contiguous chunks, one per worker thread. */
  private static int[][] chunkRanges(int total) {
    int parts = Math.min(total > 0 ? total : 1, MAX_CHUNKS);
    int chunkSize = (total + parts - 1) / parts;
    List<int[]> ranges = new ArrayList<>();
    for (int from = 0; from < total; from += chunkSize) {
      ranges.add(new int[] {from, Math.min(from + chunkSize, total)});
    }
    return ranges.toArray(new int[0][]);
  }

  private record BatchResult(double squaredErrorSum, double absoluteErrorSum, int examples) {}

  /** Runs forward+backward over a slice of examples into the worker's own accumulator. */
  private static BatchResult trainRange(
          NNUEWeights w,
          Gradients g,
          List<SparseExample> examples,
          Integer[] order,
          int fromInclusive,
          int toExclusive) {
    Scratch scratch = new Scratch();
    /*
     * Chunk ranges are fixed by the batch layout, so a range-derived seed keeps dropout
     * reproducible across identical runs.
     */
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

    /**
     * Accumulates gradients for one batch in parallel (each thread owns its private pooled
     * accumulator), reduces them on the caller thread, averages and clips them in place.
     */
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
                          double error = out - e.target();
                          stats.mse += error * error;
                          stats.mae += Math.abs(error);
                          stats.count++;
                        }
                        return stats;
                      }));
    }

    EvalStats total = new EvalStats();
    for (Future<EvalStats> future : futures) {
      try {
        EvalStats part = future.get();
        total.mse += part.mse;
        total.mae += part.mae;
        total.count += part.count;
      } catch (Exception e) {
        throw new IllegalStateException("Evaluation worker failed", e);
      }
    }
    if (total.count > 0) {
      total.mse /= total.count;
      total.mae /= total.count;
    }
    return total;
  }

  private static final class EvalStats {
    double mse;
    double mae;
    int count;
  }

  private static NNUEWeights deepCopy(NNUEWeights source) {
    NNUEWeights copy = new NNUEWeights();
    for (int i = 0; i < NNUEWeights.INPUT_SIZE; i++)
      System.arraycopy(source.inputWeights[i], 0, copy.inputWeights[i], 0, NNUEWeights.HIDDEN_SIZE);
    System.arraycopy(source.hiddenBias, 0, copy.hiddenBias, 0, NNUEWeights.HIDDEN_SIZE);
    for (int k = 0; k < NNUEWeights.HIDDEN_SIZE; k++)
      System.arraycopy(
              source.hiddenWeights[k], 0, copy.hiddenWeights[k], 0, NNUEWeights.SECOND_HIDDEN_SIZE);
    System.arraycopy(
            source.secondHiddenBias, 0, copy.secondHiddenBias, 0, NNUEWeights.SECOND_HIDDEN_SIZE);
    for (int m = 0; m < NNUEWeights.SECOND_HIDDEN_SIZE; m++)
      System.arraycopy(
              source.secondHiddenWeights[m],
              0,
              copy.secondHiddenWeights[m],
              0,
              NNUEWeights.THIRD_HIDDEN_SIZE);
    System.arraycopy(
            source.thirdHiddenBias, 0, copy.thirdHiddenBias, 0, NNUEWeights.THIRD_HIDDEN_SIZE);
    System.arraycopy(source.outputWeights, 0, copy.outputWeights, 0, NNUEWeights.THIRD_HIDDEN_SIZE);
    copy.outputBias = source.outputBias;
    return copy;
  }

  // ------------------------------------------------------------------
  // Gradient accumulator + Adam optimizer
  // ------------------------------------------------------------------

  /**
   * Raw gradient sums for one batch, shared shape with {@link NNUEWeights}. After reduction they
   * are averaged, clipped by global L2 norm and consumed by one Adam step.
   */
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
      for (double[] row : inputWeights) Arrays.fill(row, 0.0);
      Arrays.fill(hiddenBias, 0.0);
      for (double[] row : hiddenWeights) Arrays.fill(row, 0.0);
      Arrays.fill(secondHiddenBias, 0.0);
      for (double[] row : secondHiddenWeights) Arrays.fill(row, 0.0);
      Arrays.fill(thirdHiddenBias, 0.0);
      Arrays.fill(outputWeights, 0.0);
      outputBias = 0.0;
    }

    void add(Gradients other) {
      for (int i = 0; i < inputWeights.length; i++)
        for (int k = 0; k < inputWeights[i].length; k++)
          inputWeights[i][k] += other.inputWeights[i][k];
      for (int k = 0; k < hiddenBias.length; k++) hiddenBias[k] += other.hiddenBias[k];
      for (int k = 0; k < hiddenWeights.length; k++)
        for (int m = 0; m < hiddenWeights[k].length; m++)
          hiddenWeights[k][m] += other.hiddenWeights[k][m];
      for (int m = 0; m < secondHiddenBias.length; m++)
        secondHiddenBias[m] += other.secondHiddenBias[m];
      for (int m = 0; m < secondHiddenWeights.length; m++)
        for (int j = 0; j < secondHiddenWeights[m].length; j++)
          secondHiddenWeights[m][j] += other.secondHiddenWeights[m][j];
      for (int j = 0; j < thirdHiddenBias.length; j++)
        thirdHiddenBias[j] += other.thirdHiddenBias[j];
      for (int j = 0; j < outputWeights.length; j++) outputWeights[j] += other.outputWeights[j];
      outputBias += other.outputBias;
    }

    void scale(double factor) {
      for (double[] row : inputWeights) for (int k = 0; k < row.length; k++) row[k] *= factor;
      for (int k = 0; k < hiddenBias.length; k++) hiddenBias[k] *= factor;
      for (double[] row : hiddenWeights) for (int m = 0; m < row.length; m++) row[m] *= factor;
      for (int m = 0; m < secondHiddenBias.length; m++) secondHiddenBias[m] *= factor;
      for (double[] row : secondHiddenWeights)
        for (int j = 0; j < row.length; j++) row[j] *= factor;
      for (int j = 0; j < thirdHiddenBias.length; j++) thirdHiddenBias[j] *= factor;
      for (int j = 0; j < outputWeights.length; j++) outputWeights[j] *= factor;
      outputBias *= factor;
    }

    /** Global L2 norm over every gradient entry. */
    double l2Norm() {
      double sum = 0;
      for (double[] row : inputWeights) for (double v : row) sum += v * v;
      for (double v : hiddenBias) sum += v * v;
      for (double[] row : hiddenWeights) for (double v : row) sum += v * v;
      for (double v : secondHiddenBias) sum += v * v;
      for (double[] row : secondHiddenWeights) for (double v : row) sum += v * v;
      for (double v : thirdHiddenBias) sum += v * v;
      for (double v : outputWeights) sum += v * v;
      sum += outputBias * outputBias;
      return Math.sqrt(sum);
    }

    void clipNorm(double maxNorm) {
      double norm = l2Norm();
      if (norm > maxNorm && norm > 0.0) scale(maxNorm / norm);
    }
  }

  /**
   * Adam optimizer state (first/second moment estimates per parameter). Adam's adaptive step sizes
   * are what let this network escape the all-zero-output plateau that plain SGD never left.
   */
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

      /*
       * Decoupled weight decay (AdamW): shrink weights towards zero every step, independent of
       * the gradient. Biases are exempt. This is what keeps the net from growing huge weights
       * that memorise individual games instead of learning chess.
       */
      decayStep2D(w.inputWeights, g.inputWeights, mInput, vInput, bc2, scale, decay);
      step1D(w.hiddenBias, g.hiddenBias, mHiddenBias, vHiddenBias, bc2, scale);
      decayStep2D(w.hiddenWeights, g.hiddenWeights, mHidden, vHidden, bc2, scale, decay);
      step1D(w.secondHiddenBias, g.secondHiddenBias, mSecondBias, vSecondBias, bc2, scale);
      decayStep2D(
              w.secondHiddenWeights, g.secondHiddenWeights, mSecond, vSecond, bc2, scale, decay);
      step1D(w.thirdHiddenBias, g.thirdHiddenBias, mThirdBias, vThirdBias, bc2, scale);
      decayStep1D(w.outputWeights, g.outputWeights, mOutput, vOutput, bc2, scale, decay);

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
        double[] p = params[i];
        double[] gr = grads[i];
        double[] mr = m[i];
        double[] vr = v[i];
        for (int k = 0; k < p.length; k++) {
          double grad = gr[k];
          mr[k] = ADAM_BETA1 * mr[k] + (1 - ADAM_BETA1) * grad;
          vr[k] = ADAM_BETA2 * vr[k] + (1 - ADAM_BETA2) * grad * grad;
          p[k] -= scale * mr[k] / (Math.sqrt(vr[k] / bc2) + ADAM_EPS) + decay * p[k];
        }
      }
    }

    private static void decayStep1D(
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

    private static void step1D(
            double[] params, double[] grads, double[] m, double[] v, double bc2, double scale) {
      for (int k = 0; k < params.length; k++) {
        double grad = grads[k];
        m[k] = ADAM_BETA1 * m[k] + (1 - ADAM_BETA1) * grad;
        v[k] = ADAM_BETA2 * v[k] + (1 - ADAM_BETA2) * grad * grad;
        params[k] -= scale * m[k] / (Math.sqrt(v[k] / bc2) + ADAM_EPS);
      }
    }
  }
}