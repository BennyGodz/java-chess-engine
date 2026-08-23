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
  private static final int MAX_CHECK_EXTENSIONS = 4;
  private static final int MAX_QUIESCENCE_DEPTH = 16;
  private static final int PROMOTION_EXTENSION = 1;

  private final Evaluator evaluator;
  private long nodes;
  private long deadlineNanos;

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

    TTEntry(long key, int depth, int score, TTFlag flag, Move bestMove) {
      this.key = key;
      this.depth = depth;
      this.score = score;
      this.flag = flag;
      this.bestMove = bestMove;
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
   * deterministic static pass supplies the fallback, so even a very short timeout returns a real
   * score and a reasoned move instead of the first legal move and an uninitialized score.
   */
  public SearchResult findBestMove(Board board, int maxDepth, long timeLimitMillis) {
    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

    if (legalMoves.isEmpty()) {
      int score = board.isInCheck(board.isWhiteToMove()) ? -MATE_SCORE : 0;
      return new SearchResult(null, score, 0, 0);
    }

    nodes = 0;
    RootResult fallback = chooseStaticFallback(board, legalMoves);
    Move bestMove = fallback.move();
    int bestScore = fallback.score();
    int completedDepth = 0;

    long budgetMillis = Math.max(1L, timeLimitMillis);
    long budgetNanos =
        budgetMillis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : budgetMillis * 1_000_000L;
    long startNanos = System.nanoTime();
    deadlineNanos =
        startNanos > Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : startNanos + budgetNanos;

    for (int depth = 1; depth <= maxDepth; depth++) {
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

  private RootResult chooseStaticFallback(Board board, List<Move> moves) {
    boolean white = board.isWhiteToMove();
    Move bestMove = moves.get(0);
    int bestScore = -INFINITY;

    for (Move move : moves) {
      Board child = board.copyAndPlayMoveForSearch(move);
      if (child.isCheckmate(child.isWhiteToMove())) {
        return new RootResult(move, MATE_SCORE);
      }

      int score = white ? evaluator.evaluate(child) : -evaluator.evaluate(child);
      if (score > bestScore) {
        bestMove = move;
        bestScore = score;
      }
    }
    return new RootResult(bestMove, bestScore);
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

      int extension = (givesCheck ? 1 : 0) + (move.isPromotion() ? PROMOTION_EXTENSION : 0);
      int childDepth = Math.max(0, depth - 1 + extension);

      int score;
      if (i == 0) {
        score = -negamax(child, childDepth, -beta, -alpha, 1, givesCheck ? 1 : 0, true);
      } else {
        int nullScore =
            -negamax(child, childDepth, -alpha - 1, -alpha, 1, givesCheck ? 1 : 0, true);
        score =
            nullScore > alpha
                ? -negamax(child, childDepth, -beta, -alpha, 1, givesCheck ? 1 : 0, true)
                : nullScore;
      }

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
   * Negamax alpha-beta search. Returns the score from the point of view of the side to move. Probes
   * the transposition table before generating moves, handles terminal states (mate, stalemate and
   * the five draw rules), prunes with null moves, extends forcing moves and records killer/history
   * data on beta cutoffs.
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
    long positionKey = board.getZobristKey();
    int ttIndex = (int) (positionKey ^ (positionKey >>> 32)) & TT_MASK;
    TTEntry ttEntry = tt[ttIndex];

    if (ttEntry != null && ttEntry.key == positionKey && ttEntry.depth >= depth) {
      if (ttEntry.flag == TTFlag.EXACT) return ttEntry.score;
      if (ttEntry.flag == TTFlag.ALPHA && ttEntry.score <= alpha) return alpha;
      if (ttEntry.flag == TTFlag.BETA && ttEntry.score >= beta) return beta;
    }

    List<Move> moves = board.getLegalMoves(side);
    if (moves.isEmpty()) {
      return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
    }
    if (isDraw(board)) return 0;
    if (depth <= 0) return quiescence(board, alpha, beta, ply, 0);

    /*
     * Null move pruning: pass the turn and search at reduced depth. A fail-high reply means the
     * real position is likely winning too. Skipped in zugzwang-prone cases: if the side to move
     * holds no non-pawn material, passing would be illegal in a real game and the null-move
     * reply can wildly overstate the score.
     */
    if (allowNull && !board.isInCheck(side) && depth >= 3 && hasNonPawnMaterial(board, side)) {
      Board nullBoard = new Board(board);
      nullBoard.makeNullMove();
      int nullScore = -negamax(nullBoard, depth - 3, -beta, -beta + 1, ply + 1, 0, false);
      if (nullScore >= beta) return beta;
    }

    Move ttMove = ttEntry != null && ttEntry.key == positionKey ? ttEntry.bestMove : null;
    orderMoves(board, moves, ttMove, ply);

    if (++nodes % HISTORY_RESET_NODES == 0) decayHistory();

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
    tt[ttIndex] = new TTEntry(positionKey, depth, bestScore, flag, bestMove);

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
    if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH) {
      return inCheck ? (side ? evaluator.evaluate(board) : -evaluator.evaluate(board)) : alpha;
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

  private void decayHistory() {
    for (int[] entries : historyHeuristic) {
      for (int i = 0; i < entries.length; i++) entries[i] = Math.max(0, entries[i] - 500);
    }
  }

  private boolean isTactical(Board board, Move move) {
    return move.isPromotion() || move.isEnPassant() || isCapture(board, move);
  }

  private boolean isCapture(Board board, Move move) {
    return board.getPiece(move.getEnd()) != null;
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
    if (System.nanoTime() >= deadlineNanos) throw new SearchTimeoutException();
  }

  private static class SearchTimeoutException extends RuntimeException {}

  private record RootResult(Move move, int score) {}

  public record SearchResult(Move bestMove, int score, int depth, long nodes) {}
}
