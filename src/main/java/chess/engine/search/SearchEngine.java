package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;
import chess.pieces.Queen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SearchEngine {

  public static final int MATE_SCORE = 100_000;
  private static final int INFINITY = 1_000_000;
  private static final int MAX_CHECK_EXTENSIONS = 12;
  private static final int MAX_QUIESCENCE_DEPTH = 16;
  private static final int ENDGAME_EXTENSION = 1;
  private static final int PROMOTION_EXTENSION = 2;
  private static final int PVS_FIRST_MOVE_SCORE = 0; // Special marker for PVS first move

  private final Evaluator evaluator;
  private long nodes;
  private long deadlineNanos;

  // Zobrist key for position identification
  private long zobristKey;

  // Transposition Table
  private static final int TT_SIZE = 1 << 21; // ~2 million entries - larger for better coverage
  private static final int TT_MASK = TT_SIZE - 1;
  private final TTEntry[] tt;

  // History and Killer Moves
  private final int[][] historyHeuristic;
  private final Move[][] killerMoves;
  private static final int MAX_DEPTH = 64;

  private enum TTFlag {
    EXACT,
    ALPHA,
    BETA
  }

  private static class TTEntry {
    String key;
    int depth;
    int score;
    TTFlag flag;
    Move bestMove;

    TTEntry(String key, int depth, int score, TTFlag flag, Move bestMove) {
      this.key = key;
      this.depth = depth;
      this.score = score;
      this.flag = flag;
      this.bestMove = bestMove;
    }
  }

  // History reset counter - clear history after this many nodes to prevent stale data
  private static final int HISTORY_RESET_NODES = 100_000;

  public SearchEngine() {
    this(new Evaluator());
  }

  public SearchEngine(Evaluator evaluator) {
    this.evaluator = evaluator;
    this.tt = new TTEntry[TT_SIZE];
    this.historyHeuristic = new int[2][64 * 64]; // [color][start * 64 + end]
    this.killerMoves = new Move[MAX_DEPTH][2];
    this.nodes = 0;
  }

  public SearchResult findBestMove(Board board, int maxDepth, long timeLimitMillis) {
    List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());

    if (legalMoves.isEmpty()) {
      return new SearchResult(null, 0, 0, 0);
    }

    nodes = 0;
    deadlineNanos = System.nanoTime() + Math.max(1, timeLimitMillis) * 1_000_000L;

    Move mateMove = findImmediateMate(board, legalMoves);
    if (mateMove != null) {
      return new SearchResult(mateMove, MATE_SCORE, 1, nodes);
    }

    // Iterative deepening with stall detection
    Move bestMove = legalMoves.get(0);
    int bestScore = -INFINITY;
    int completedDepth = 0;
    int prevScore = -INFINITY;
    int stallCount = 0;
    long nodesAtPreviousDepth = 0; // Changed from int to long

    for (int depth = 1; depth <= maxDepth; depth++) {
      try {
        RootResult result = searchRoot(board, depth, bestMove);
        bestMove = result.move;
        bestScore = result.score;
        completedDepth = depth;

        // Stall detection: if score hasn't improved significantly, stop
        if (Math.abs(bestScore - prevScore) < 50) {
          stallCount++;
        } else {
          stallCount = 0;
          nodesAtPreviousDepth = nodes;
        }
        prevScore = bestScore;

        // If we've stalled for 3 depths and we've searched at least 2 depths, stop
        if (stallCount >= 3 && depth >= 3) {
          System.out.println("Search stalled at depth " + depth + ", stopping iterative deepening");
          break;
        }

      } catch (SearchTimeoutException e) {
        break;
      }
    }

    return new SearchResult(bestMove, bestScore, completedDepth, nodes);
  }

  private Move findImmediateMate(Board board, List<Move> moves) {
    for (Move move : moves) {
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      boolean enemyToMove = child.isWhiteToMove();
      if (child.isInCheck(enemyToMove) && child.getLegalMoves(enemyToMove).isEmpty()) {
        return move;
      }
    }
    return null;
  }

  private RootResult searchRoot(Board board, int depth, Move previousBest) {
    checkTime();
    List<Move> moves = board.getLegalMoves(board.isWhiteToMove());

    if (moves.isEmpty()) {
      return new RootResult(null, board.isInCheck(board.isWhiteToMove()) ? -MATE_SCORE : 0);
    }

    orderMoves(board, moves, previousBest, 0);
    Move bestMove = moves.get(0);
    int bestScore = -INFINITY; // Added declaration
    int alpha = -INFINITY;
    int beta = INFINITY;

    // PVS: Principal Variation Search
    // Search first move with full alpha-beta, rest with null windows
    for (int i = 0; i < moves.size(); i++) {
      Move move = moves.get(i);
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      boolean givesCheck = child.isInCheck(child.isWhiteToMove());

      int extension = 0;
      if (givesCheck && MAX_CHECK_EXTENSIONS > 0) extension = 1;
      if (move.isPromotion()) extension += PROMOTION_EXTENSION;
      if (isEndgame(child)) extension += ENDGAME_EXTENSION;

      int score;
      if (i == 0) {
        // First move: search with full alpha-beta
        score = -negamax(child, Math.max(0, depth - 1 + extension), -beta, -alpha, 1, 
                         givesCheck ? 1 : 0, true);
      } else {
        // Subsequent moves: PVS with null window
        // Score = -negamax(child, depth-1, -alpha-1, -alpha, 1, ...)
        // If score > alpha, re-search with full window
        int nullScore = -negamax(child, Math.max(0, depth - 1 + extension), 
                                  -alpha - 1, -alpha, 1, 
                                  givesCheck ? 1 : 0, true);
        if (nullScore > alpha) {
          // Re-search with full window - this is the "full re-search"
          score = -negamax(child, Math.max(0, depth - 1 + extension), 
                           -beta, -alpha, 1, 
                           givesCheck ? 1 : 0, true);
        } else {
          score = nullScore;
        }
      }

      if (score > bestScore) {
        bestScore = score;
        bestMove = move;
      }
      if (score > alpha) alpha = score;
      if (alpha >= beta) break; // Beta cutoff
    }
    return new RootResult(bestMove, bestScore);
  }

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
    String positionKey = board.getPositionKey();
    int ttIndex = positionKey.hashCode() & TT_MASK;
    TTEntry ttEntry = tt[ttIndex];

    if (ttEntry != null && ttEntry.key.equals(positionKey) && ttEntry.depth >= depth) {
      if (ttEntry.flag == TTFlag.EXACT) return ttEntry.score;
      if (ttEntry.flag == TTFlag.ALPHA && ttEntry.score <= alpha) return alpha;
      if (ttEntry.flag == TTFlag.BETA && ttEntry.score >= beta) return beta;
    }

    List<Move> moves = board.getLegalMoves(side);
    if (moves.isEmpty()) {
      return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
    }
    if (board.isSeventyFiveMoveRule()
        || board.isFivefoldRepetition()
        || board.isFiftyMoveRule()
        || board.isThreefoldRepetition()
        || board.isInsufficientMaterial()) {
      return 0;
    }
    if (depth <= 0) {
      return quiescence(board, alpha, beta, ply, 0);
    }

    // Null Move Pruning - enhanced with core search verification
    if (allowNull && !board.isInCheck(side) && depth >= 3) {
      Board nullBoard = new Board(board);
      nullBoard.makeNullMove();
      int nullScore = -negamax(nullBoard, depth - 3, -beta, -beta + 1, ply + 1, 0, false);
      if (nullScore >= beta) return beta;
      // Null move fail-soft: don't reduce depth as much if null move fails low
    }

    // Move ordering - TT move first, then killers, then captures, then quiet
    Move ttMove = ttEntry != null && ttEntry.key.equals(positionKey) ? ttEntry.bestMove : null;
    orderMoves(board, moves, ttMove, ply);

    // History heuristic reset - clear stale history periodically
    nodes++;
    if (nodes % HISTORY_RESET_NODES == 0) {
      // Reset history to zero out stale values, but keep killers
      for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 64 * 64; j++) {
          historyHeuristic[i][j] = Math.max(0, historyHeuristic[i][j] - 500);
        }
      }
    }

    int bestScore = -INFINITY;
    Move bestMove = null;
    int bestMoveIndex = -1;
    int originalAlpha = alpha;
    int movesSearched = 0;

    for (int i = 0; i < moves.size(); i++) {
      Move move = moves.get(i);
      checkTime();
      Board child = board.copyAndPlayMoveForSearch(move);
      boolean givesCheck = child.isInCheck(child.isWhiteToMove());

      int extension = 0;
      if (givesCheck && checkExtensions < MAX_CHECK_EXTENSIONS) extension = 1;
      if (move.isPromotion()) extension += PROMOTION_EXTENSION;
      if (isEndgame(child)) extension += ENDGAME_EXTENSION;

      int newCheckExtensions = givesCheck ? checkExtensions + 1 : 0;
      int score;

      // Late Move Pruning (LMP) - skip obvious bad moves at shallow depth
      // Don't prune captures, promotions, checks, or early moves
      int moveIndex = movesSearched;
      boolean shouldPruneLMP = 
          !givesCheck 
          && !move.isPromotion()
          && !isCapture(board, move)
          && depth <= 4 
          && moveIndex >= 4; // Only prune after 4th move

      if (shouldPruneLMP) {
        // Quick null window search to see if move is worth full evaluation
        score = -negamax(child, depth - 2, -alpha - 1, -alpha, ply + 1, newCheckExtensions, true);
        if (score > alpha) { // Needs full re-search
          score =
              -negamax(
                  child, depth - 1 + extension, -beta, -alpha, ply + 1, newCheckExtensions, true);
        }
      } else {
        // Standard search with LMR reduction
        score = -negamax(child, depth - 1 + extension, -beta, -alpha, ply + 1, newCheckExtensions, true);
      }

      if (score > bestScore) {
        bestScore = score;
        bestMoveIndex = i;
        bestMove = move;
      }
      if (score > alpha) alpha = score;
      if (alpha >= beta) {
        // Record killer move and history heuristic
        if (!isCapture(board, move)) {
          if (ply < MAX_DEPTH) {
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = move;
          }
          // History heuristic - only for quiet moves that cause beta-cutoff
          historyHeuristic[
                  side ? 1 : 0][
                  move.getStart().getRow() * 512
                      + move.getStart().getColumn() * 64
                      + move.getEnd().getRow() * 8
                      + move.getEnd().getColumn()] +=
              depth * depth;
        }
        break;
      }
      movesSearched++;
    }

    // Store TT entry
    TTFlag flag = TTFlag.EXACT;
    if (bestScore <= originalAlpha) flag = TTFlag.ALPHA;
    else if (bestScore >= beta) flag = TTFlag.BETA;
    tt[ttIndex] = new TTEntry(positionKey, depth, bestScore, flag, bestMove != null ? moves.get(bestMoveIndex) : null);

    return bestScore;
  }

  private int quiescence(Board board, int alpha, int beta, int ply, int quiescenceDepth) {
    nodes++;
    checkTime();
    boolean side = board.isWhiteToMove();
    List<Move> legalMoves = board.getLegalMoves(side);

    if (legalMoves.isEmpty()) {
      return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
    }

    if (board.isInCheck(side)) {
      if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH) {
        return side ? evaluator.evaluate(board) : -evaluator.evaluate(board);
      }
      orderMoves(board, legalMoves, null, ply);
      for (Move move : legalMoves) {
        checkTime();
        Board child = board.copyAndPlayMoveForSearch(move);
        int score = -quiescence(child, -beta, -alpha, ply + 1, quiescenceDepth + 1);
        if (score >= beta) return beta;
        if (score > alpha) alpha = score;
      }
      return alpha;
    }

    int standPat = side ? evaluator.evaluate(board) : -evaluator.evaluate(board);
    if (standPat >= beta) return beta;
    if (standPat > alpha) alpha = standPat;
    if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH) return alpha;

    // Extended Futility Pruning (EFP) at quiescence root:
    // Prune non-tactical moves where stand-pat + piece value - 100 < alpha
    if (quiescenceDepth == 0) {
      List<Move> nonTactical = new ArrayList<>();
      for (Move move : legalMoves) {
        if (!isTactical(board, move)) {
          Piece piece = board.getPiece(move.getStart());
          int pVal = piece != null ? piece.getValue() : 0;
          if (standPat + pVal - 100 < alpha) {
            // Skip this move (will be filtered out later)
          } else {
            nonTactical.add(move);
          }
        } else {
          nonTactical.add(move);
        }
      }
      legalMoves = nonTactical;
      if (legalMoves.isEmpty()) return alpha;
    }

    // Delta pruning: if stand-pat is already >= beta, prune all non-captures
    if (standPat + 50 >= beta) {
      List<Move> tactical = new ArrayList<>();
      for (Move move : legalMoves) {
        if (isTactical(board, move)) tactical.add(move);
      }
      if (tactical.isEmpty()) return alpha;
      orderMoves(board, tactical, null, ply);
      for (Move move : tactical) {
        checkTime();
        if (isCapture(board, move) && !move.isPromotion()) {
          Piece captured = board.getPiece(move.getEnd());
          if (captured != null && standPat + captured.getValue() + 200 < alpha)
            continue;
        }
        Board child = board.copyAndPlayMoveForSearch(move);
        int score = -quiescence(child, -beta, -alpha, ply + 1, quiescenceDepth + 1);
        if (score >= beta) return beta;
        if (score > alpha) alpha = score;
      }
      return alpha;
    }

    // Root razoring: at shallow depths, prune if stand-pat + margin <= alpha
    if (quiescenceDepth < 2) {
      int razorMargin = (2 - quiescenceDepth) * 50; // 100 at depth 0, 50 at depth 1
      if (standPat + razorMargin <= alpha) return alpha;
    }

    List<Move> tactical = new ArrayList<>();
    for (Move move : legalMoves) {
      if (isTactical(board, move)) tactical.add(move);
    }
    if (tactical.isEmpty()) return alpha;

    orderMoves(board, tactical, null, ply);
    for (Move move : tactical) {
      checkTime();
      // Delta pruning + SEE-based capture filtering
      if (isCapture(board, move) && !move.isPromotion()) {
        Piece captured = board.getPiece(move.getEnd());
        if (captured != null) {
          Piece attacker = board.getPiece(move.getStart());
          int attackerValue = attacker == null ? 0 : attacker.getValue();
          int captureDiff = captured.getValue() - attackerValue;
          // SEE: if capturing side loses material, apply razor margin
          if (captureDiff < 0) { // Losing capture
            if (standPat + Math.abs(captureDiff) * 100 + 100 < alpha)
              continue; // prune losing capture
          } else { // Winning capture
            if (standPat + captured.getValue() + 200 < alpha)
              continue; // safety margin
          }
        }
      }
      Board child = board.copyAndPlayMoveForSearch(move);
      int score = -quiescence(child, -beta, -alpha, ply + 1, quiescenceDepth + 1);
      if (score >= beta) return beta;
      if (score > alpha) alpha = score;
    }
    return alpha;
  }

  private boolean isEndgame(Board board) {
    int queens = 0, rooks = 0, minors = 0, pawns = 0;
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        Piece piece = board.getPiece(new chess.board.Position(r, c));
        if (piece == null) continue;
        if (piece instanceof Queen) queens++;
        else if (piece instanceof chess.pieces.Rook) rooks++;
        else if (piece instanceof chess.pieces.Bishop || piece instanceof chess.pieces.Knight)
          minors++;
        else if (piece instanceof chess.pieces.Pawn) pawns++;
      }
    }
    if (queens == 0 && rooks <= 1) return true;
    if (queens == 0 && rooks <= 2 && minors <= 3) return true;
    if (rooks + minors <= 2 && pawns <= 8) return true;
    return false;
  }

  /** Returns the current Zobrist key for the position. */
  public long getZobristKey() {
    return zobristKey;
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

  private int moveOrderingScore(Board board, Move move, Move ttMove, int ply) {
    int score = 0;
    if (ttMove != null && sameMove(move, ttMove)) {
      score += 2_000_000;
    }
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

    // Killer moves and History heuristics for quiet moves
    if (captured == null && !move.isPromotion()) {
      if (ply < MAX_DEPTH) {
        if (sameMove(move, killerMoves[ply][0])) score += 50_000;
        else if (sameMove(move, killerMoves[ply][1])) score += 40_000;
      }
      int historyScore =
          historyHeuristic[board.isWhiteToMove() ? 1 : 0][
              move.getStart().getRow() * 512
                  + move.getStart().getColumn() * 64
                  + move.getEnd().getRow() * 8
                  + move.getEnd().getColumn()];
      score += Math.min(historyScore, 30_000);
    }

    return score;
  }

  private boolean sameMove(Move a, Move b) {
    if (a == null || b == null) return false;
    if (!a.getStart().equals(b.getStart())) return false;
    if (!a.getEnd().equals(b.getEnd())) return false;
    if (a.isPromotion() != b.isPromotion()) return false;
    if (a.isPromotion()) {
      return a.getPromotionPiece().getClass() == b.getPromotionPiece().getClass();
    }
    return true;
  }

  private void checkTime() {
    if (System.nanoTime() >= deadlineNanos) throw new SearchTimeoutException();
  }

  private static class SearchTimeoutException extends RuntimeException {}

  private record RootResult(Move move, int score) {}

  public record SearchResult(Move bestMove, int score, int depth, long nodes) {}
}
