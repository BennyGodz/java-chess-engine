package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.evaluation.Evaluator;
import chess.pieces.King;
import chess.pieces.Pawn;
import chess.pieces.Piece;
import chess.pieces.Queen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Alpha-beta search with iterative deepening, transposition table and move ordering. Features:
 * principal variation search, null move pruning, bounded check/promotion extensions, tactical
 * quiescence search, killer moves and a history heuristic for ordering quiet moves.
 */
public class SearchEngine {

  public static final int MATE_SCORE = 100_000;

  private static final int INFINITY = 1_000_000;
  private static final int MATE_THRESHOLD = MATE_SCORE - 1_000;
  private static final int MAX_CHECK_EXTENSIONS = 4;
  private static final int MAX_QUIESCENCE_DEPTH = 16;
  private static final int HARD_QUIESCENCE_DEPTH = 24;
  private static final int PROMOTION_EXTENSION = 1;
  private static final int ROOT_REPETITION_PENALTY = 30;

  private final Evaluator evaluator;
  private long nodes;
  private long deadlineNanos;
  private boolean ignoreTimeout;
  private int searchGeneration;
  private long lastRootContext = Long.MIN_VALUE;

  private static final int TT_SIZE = 1 << 21;
  private static final int TT_MASK = TT_SIZE - 1;
  private final TTEntry[] tt;

  private static final int MAX_DEPTH = 64;
  private final int[][] historyHeuristic;
  private final Move[][] killerMoves;

  private enum TTFlag {
    EXACT,
    ALPHA,
    BETA
  }

  private static class TTEntry {
    long key;
    int depth;
    int score;
    TTFlag flag;
    Move bestMove;
    int generation;

    TTEntry(long key, int depth, int score, TTFlag flag, Move bestMove, int generation) {
      update(key, depth, score, flag, bestMove, generation);
    }

    void update(long key, int depth, int score, TTFlag flag, Move bestMove, int generation) {
      this.key = key;
      this.depth = depth;
      this.score = score;
      this.flag = flag;
      this.bestMove = bestMove;
      this.generation = generation;
    }
  }

  private static final int HISTORY_RESET_NODES = 100_000;

  public SearchEngine() {
    this(new Evaluator());
  }

  public SearchEngine(Evaluator evaluator) {
    this.evaluator = evaluator;
    this.tt = new TTEntry[TT_SIZE];
    this.historyHeuristic = new int[2][64 * 64];
    this.killerMoves = new Move[MAX_DEPTH][2];
  }

  /**
   * Finds the best move for the side to move using iterative deepening up to maxDepth. A complete,
   * deterministic tactical pass supplies the fallback, so even a very short timeout resolves all
   * immediate captures instead of hanging a rook or queen because the clock expired.
   */
  public SearchResult findBestMove(Board board, int maxDepth, long timeLimitMillis) {
    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

    if (legalMoves.isEmpty()) {
      int score = board.isInCheck(board.isWhiteToMove()) ? -MATE_SCORE : 0;
      return new SearchResult(null, score, 0, 0);
    }

    nodes = 0;
    long budgetMillis = Math.max(1L, timeLimitMillis);
    long budgetNanos =
        budgetMillis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : budgetMillis * 1_000_000L;
    long startNanos = System.nanoTime();
    deadlineNanos =
        startNanos > Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : startNanos + budgetNanos;

    prepareSearchGeneration(board);

    ignoreTimeout = true;
    RootResult fallback = searchRoot(board, 1, null);
    ignoreTimeout = false;

    Move bestMove = fallback.move();
    int bestScore = fallback.score();
    int completedDepth = 1;

    for (int depth = 2; depth <= Math.max(1, maxDepth); depth++) {
      if (System.nanoTime() >= deadlineNanos) break;
      try {
        RootResult result = searchRoot(board, depth, bestMove);
        bestMove = result.move();
        bestScore = result.score();
        completedDepth = depth;
      } catch (SearchTimeoutException e) {
        break;
      }
    }

    return new SearchResult(bestMove, bestScore, completedDepth, nodes);
  }

  private void prepareSearchGeneration(Board board) {
    long rootContext =
        transpositionKey(board)
            ^ Long.rotateLeft(board.getCurrentPositionRepetitionCount() * 0x9E3779B97F4A7C15L, 17);
    if (rootContext != lastRootContext) {
      searchGeneration++;
      if (searchGeneration == 0) searchGeneration = 1;
      lastRootContext = rootContext;
    }
  }

  /**
   * Root search: orders moves with the previous iteration's best move first and applies PVS,
   * searching later moves with a null window first and re-searching on fail high.
   */
  private RootResult searchRoot(Board board, int depth, Move previousBest) {
    checkTime();
    List<Move> moves = board.getLegalMoves(board.isWhiteToMove());

    if (moves.isEmpty()) {
      return new RootResult(null, board.isInCheck(board.isWhiteToMove()) ? -MATE_SCORE : 0);
    }

    orderMoves(board, moves, previousBest, 0);
    Move bestMove = moves.get(0);
    int bestScore = -INFINITY;
    int alpha = -INFINITY;
    int beta = INFINITY;

    for (int i = 0; i < moves.size(); i++) {
      Move move = moves.get(i);
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      boolean givesCheck = child.isInCheck(child.isWhiteToMove());

      int extension =
          depth > 1 ? (givesCheck ? 1 : 0) + (move.isPromotion() ? PROMOTION_EXTENSION : 0) : 0;
      int childDepth = Math.max(0, depth - 1 + extension);

      int score;
      if (i == 0) {
        score = -negamax(child, childDepth, -beta, -alpha, 1, givesCheck ? 1 : 0, true);
      } else {
        int requiredRawScore = alpha + repetitionPenalty(child);
        int nullScore =
            -negamax(
                child,
                childDepth,
                -requiredRawScore - 1,
                -requiredRawScore,
                1,
                givesCheck ? 1 : 0,
                true);
        score =
            nullScore > requiredRawScore
                ? -negamax(child, childDepth, -beta, -alpha, 1, givesCheck ? 1 : 0, true)
                : nullScore;
      }
      score -= repetitionPenalty(child);

      if (score > bestScore) {
        bestScore = score;
        bestMove = move;
      }
      if (score > alpha) alpha = score;
      if (alpha >= beta) break;
    }
    return new RootResult(bestMove, bestScore);
  }

  /**
   * Negamax alpha-beta search. Returns the score from the point of view of the side to move. It
   * handles terminal states before using cached scores, prunes with null moves, extends forcing
   * moves and records killer/history data on beta cutoffs.
   */
  private int negamax(
      Board board,
      int depth,
      int alpha,
      int beta,
      int ply,
      int checkExtensions,
      boolean allowNull) {
    nodes++;
    checkTime();

    boolean side = board.isWhiteToMove();
    List<Move> moves = board.getLegalMoves(side);
    if (moves.isEmpty()) {
      return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
    }
    if (isDraw(board)) return 0;
    if (depth <= 0) return quiescence(board, alpha, beta, ply, 0);

    boolean useTranspositionTable = isTranspositionSafe(board);
    long positionKey = transpositionKey(board);
    int ttIndex = (int) (positionKey ^ (positionKey >>> 32)) & TT_MASK;
    TTEntry ttEntry = useTranspositionTable ? tt[ttIndex] : null;
    if (ttEntry != null && ttEntry.generation != searchGeneration) ttEntry = null;

    if (ttEntry != null && ttEntry.key == positionKey && ttEntry.depth >= depth) {
      int tableScore = scoreFromTable(ttEntry.score, ply);
      if (ttEntry.flag == TTFlag.EXACT) return tableScore;
      if (ttEntry.flag == TTFlag.ALPHA && tableScore <= alpha) return alpha;
      if (ttEntry.flag == TTFlag.BETA && tableScore >= beta) return beta;
    }

    /*
     * Null move pruning: pass the turn and search at reduced depth. A fail-high reply means the
     * real position is likely winning too. Skipped in zugzwang-prone cases: if the side to move
     * holds no non-pawn material, passing would be illegal in a real game and the null-move
     * reply can wildly overstate the score.
     */
    if (allowNull && !board.isInCheck(side) && depth >= 3 && hasNonPawnMaterial(board, side)) {
      Board nullBoard = board.copyAndMakeNullMoveForSearch();
      int nullScore = -negamax(nullBoard, depth - 3, -beta, -beta + 1, ply + 1, 0, false);
      if (nullScore >= beta) return beta;
    }

    Move ttMove = ttEntry != null && ttEntry.key == positionKey ? ttEntry.bestMove : null;
    orderMoves(board, moves, ttMove, ply);

    if (nodes % HISTORY_RESET_NODES == 0) decayHistory();

    int bestScore = -INFINITY;
    Move bestMove = null;
    int originalAlpha = alpha;

    for (int i = 0; i < moves.size(); i++) {
      Move move = moves.get(i);
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      boolean givesCheck = child.isInCheck(child.isWhiteToMove());

      int newCheckExtensions = checkExtensions;
      int extension = 0;
      if (givesCheck && checkExtensions < MAX_CHECK_EXTENSIONS) {
        extension = 1;
        newCheckExtensions++;
      }
      if (move.isPromotion()) extension += PROMOTION_EXTENSION;
      int score;

      boolean shouldPruneLMP =
          !givesCheck && !move.isPromotion() && !isCapture(board, move) && depth <= 4 && i >= 4;

      if (shouldPruneLMP) {
        score = -negamax(child, depth - 2, -alpha - 1, -alpha, ply + 1, newCheckExtensions, true);
        if (score > alpha) {
          score =
              -negamax(
                  child, depth - 1 + extension, -beta, -alpha, ply + 1, newCheckExtensions, true);
        }
      } else {
        score =
            -negamax(
                child, depth - 1 + extension, -beta, -alpha, ply + 1, newCheckExtensions, true);
      }

      if (score > bestScore) {
        bestScore = score;
        bestMove = move;
      }
      if (score > alpha) alpha = score;
      if (alpha >= beta) {
        if (!isCapture(board, move)) {
          if (ply < MAX_DEPTH) {
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = move;
          }
          historyHeuristic[side ? 1 : 0][historyIndex(move)] += depth * depth;
        }
        break;
      }
    }

    TTFlag flag = TTFlag.EXACT;
    if (bestScore <= originalAlpha) flag = TTFlag.ALPHA;
    else if (bestScore >= beta) flag = TTFlag.BETA;
    if (useTranspositionTable) {
      int tableScore = scoreToTable(bestScore, ply);
      TTEntry stored = tt[ttIndex];
      boolean replace =
          stored == null
              || stored.generation != searchGeneration
              || stored.key == positionKey
              || depth + 2 >= stored.depth;
      if (stored == null) {
        tt[ttIndex] = new TTEntry(positionKey, depth, tableScore, flag, bestMove, searchGeneration);
      } else if (replace) {
        stored.update(positionKey, depth, tableScore, flag, bestMove, searchGeneration);
      }
    }

    return bestScore;
  }

  /**
   * Quiescence search: resolves captures and check evasions so leaf evaluations stay tactically
   * stable. Stand-pat is never used while in check, and speculative pruning cannot discard a saving
   * capture.
   */
  private int quiescence(Board board, int alpha, int beta, int ply, int quiescenceDepth) {
    nodes++;
    checkTime();
    boolean side = board.isWhiteToMove();
    List<Move> legalMoves = board.getLegalMoves(side);

    if (legalMoves.isEmpty()) {
      return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
    }
    if (isDraw(board)) return 0;

    boolean inCheck = board.isInCheck(side);
    if (!inCheck) {
      int standPat = side ? evaluator.evaluate(board) : -evaluator.evaluate(board);
      if (standPat >= beta) return beta;
      if (standPat > alpha) alpha = standPat;
    }
    if (quiescenceDepth >= HARD_QUIESCENCE_DEPTH) {
      return side ? evaluator.evaluate(board) : -evaluator.evaluate(board);
    }
    if (!inCheck && quiescenceDepth >= MAX_QUIESCENCE_DEPTH) {
      return alpha;
    }

    List<Move> movesToSearch = legalMoves;
    if (!inCheck) {
      movesToSearch = new ArrayList<>();
      for (Move move : legalMoves) {
        if (isTactical(board, move)) movesToSearch.add(move);
      }
    }
    if (movesToSearch.isEmpty()) return alpha;

    orderMoves(board, movesToSearch, null, ply);
    for (Move move : movesToSearch) {
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      int score = -quiescence(child, -beta, -alpha, ply + 1, quiescenceDepth + 1);
      if (score >= beta) return beta;
      if (score > alpha) alpha = score;
    }
    return alpha;
  }

  /** True if white's army contains any piece besides king and pawns (null-move guard). */
  private boolean hasNonPawnMaterial(Board board, boolean white) {
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        Piece piece = board.getPiece(new Position(r, c));
        if (piece == null || piece.isWhite() != white || piece instanceof King) continue;
        if (!(piece instanceof Pawn)) return true;
      }
    }
    return false;
  }

  private boolean isDraw(Board board) {
    return board.isSeventyFiveMoveRule()
        || board.isFivefoldRepetition()
        || board.isFiftyMoveRule()
        || board.isThreefoldRepetition()
        || board.isInsufficientMaterial();
  }

  static int repetitionPenalty(Board board) {
    return Math.max(0, board.getCurrentPositionRepetitionCount() - 1) * ROOT_REPETITION_PENALTY;
  }

  static boolean isTranspositionSafe(Board board) {
    return board.getCurrentPositionRepetitionCount() <= 1;
  }

  private static long transpositionKey(Board board) {
    long halfmove = (board.getHalfmoveClock() + 1L) * 0x9E3779B97F4A7C15L;
    halfmove = (halfmove ^ (halfmove >>> 30)) * 0xBF58476D1CE4E5B9L;
    return board.getZobristKey() ^ halfmove ^ (halfmove >>> 27);
  }

  private void decayHistory() {
    for (int[] entries : historyHeuristic) {
      for (int i = 0; i < entries.length; i++) entries[i] = Math.max(0, entries[i] - 500);
    }
  }

  private boolean isTactical(Board board, Move move) {
    return move.isPromotion() || move.isEnPassant() || isCapture(board, move);
  }

  private boolean isCapture(Board board, Move move) {
    return move.isEnPassant() || board.getPiece(move.getEnd()) != null;
  }

  private static int scoreToTable(int score, int ply) {
    if (score > MATE_THRESHOLD) return score + ply;
    if (score < -MATE_THRESHOLD) return score - ply;
    return score;
  }

  private static int scoreFromTable(int score, int ply) {
    if (score > MATE_THRESHOLD) return score - ply;
    if (score < -MATE_THRESHOLD) return score + ply;
    return score;
  }

  private void orderMoves(Board board, List<Move> moves, Move ttMove, int ply) {
    moves.sort(Comparator.comparingInt(move -> -moveOrderingScore(board, move, ttMove, ply)));
  }

  /**
   * Ordering priority: TT move, promotions, captures (MVV-LVA style), en passant, castling, then
   * killer moves and history for quiet moves.
   */
  private int moveOrderingScore(Board board, Move move, Move ttMove, int ply) {
    int score = 0;
    if (sameMove(move, ttMove)) score += 2_000_000;
    if (move.isPromotion()) {
      score += 300_000;
      if (move.getPromotionPiece() != null) score += move.getPromotionPiece().getValue();
    }
    Piece captured = board.getPiece(move.getEnd());
    if (captured != null) {
      Piece attacker = board.getPiece(move.getStart());
      int attackerValue = attacker == null ? 0 : attacker.getValue();
      if (captured instanceof Queen) score += 500_000;
      else score += 100_000 + captured.getValue() * 100 - attackerValue;
    }
    if (move.isEnPassant()) score += 100_000;
    if (move.isCastling()) score += 3_000;

    if (captured == null && !move.isPromotion()) {
      if (ply < MAX_DEPTH) {
        if (sameMove(move, killerMoves[ply][0])) score += 50_000;
        else if (sameMove(move, killerMoves[ply][1])) score += 40_000;
      }
      int historyScore = historyHeuristic[board.isWhiteToMove() ? 1 : 0][historyIndex(move)];
      score += Math.min(historyScore, 30_000);
    }

    return score;
  }

  private static int historyIndex(Move move) {
    return move.getStart().getRow() * 512
        + move.getStart().getColumn() * 64
        + move.getEnd().getRow() * 8
        + move.getEnd().getColumn();
  }

  private static boolean sameMove(Move a, Move b) {
    if (a == null || b == null) return false;
    if (!a.getStart().equals(b.getStart())) return false;
    if (!a.getEnd().equals(b.getEnd())) return false;
    if (a.isPromotion() != b.isPromotion()) return false;
    return !a.isPromotion() || a.getPromotionPiece().getClass() == b.getPromotionPiece().getClass();
  }

  private void checkTime() {
    if (!ignoreTimeout && System.nanoTime() >= deadlineNanos) throw new SearchTimeoutException();
  }

  private static class SearchTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private record RootResult(Move move, int score) {}

  public record SearchResult(Move bestMove, int score, int depth, long nodes) {}
}
