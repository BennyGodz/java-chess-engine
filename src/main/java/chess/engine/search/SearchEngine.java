package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.board.Position;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Iterative-deepening negamax search with alpha-beta pruning, a transposition table,
 * principal-variation search, quiescence search, check extensions, and tactical move ordering.
 */
public class SearchEngine {

    public static final int MATE_SCORE = 100_000;

    private static final int INFINITY = 1_000_000;
    private static final int MATE_THRESHOLD = MATE_SCORE - 1_000;
    private static final int MAX_CHECK_EXTENSIONS = 4;
    private static final int MAX_QUIESCENCE_DEPTH = 12;
    private static final int HARD_QUIESCENCE_DEPTH = 24;
    private static final int MAX_PLY = 128;

    private static final int TT_SIZE = 1 << 18;
    private static final int TT_MASK = TT_SIZE - 1;
    private static final byte TT_EXACT = 0;
    private static final byte TT_LOWER = 1;
    private static final byte TT_UPPER = 2;

    private final Evaluator evaluator;

    private final long[] ttKeys = new long[TT_SIZE];
    private final int[] ttDepths = new int[TT_SIZE];
    private final int[] ttScores = new int[TT_SIZE];
    private final byte[] ttFlags = new byte[TT_SIZE];
    private final Move[] ttMoves = new Move[TT_SIZE];
    private final int[] ttGenerations = new int[TT_SIZE];

    private final Move[][] killerMoves = new Move[MAX_PLY][2];
    private final int[][][] history = new int[2][64][64];

    private Map<String, Integer> repetitionCounts = new HashMap<>();
    private long nodes;
    private long deadlineNanos;
    private boolean ignoreTimeout;
    private int searchGeneration;

    public SearchEngine() {
        this(new Evaluator());
    }

    public SearchEngine(Evaluator evaluator) {
        this.evaluator = evaluator;
        Arrays.fill(ttDepths, -1);
    }

    /**
     * Finds the best move under a soft time limit. Depth one is always completed so an expired
     * clock or a cold JVM can never make the engine return an arbitrary, tactically losing move.
     */
    public SearchResult findBestMove(Board board, int maxDepth, long timeLimitMillis) {
        List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());
        if (legalMoves.isEmpty()) {
            return new SearchResult(null, 0, 0, 0);
        }

        resetSearchState(board);
        long budgetMillis = Math.max(1, timeLimitMillis);
        long now = System.nanoTime();
        long budgetNanos = budgetMillis > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE
                : budgetMillis * 1_000_000L;
        deadlineNanos = budgetNanos == Long.MAX_VALUE || now > Long.MAX_VALUE - budgetNanos
                ? Long.MAX_VALUE
                : now + budgetNanos;

        /*
         * This untimed safety pass is deliberately mandatory. Its quiescence search follows all
         * captures and check evasions, so directly hanging a rook or queen cannot be the fallback.
         */
        ignoreTimeout = true;
        RootResult completed = searchRoot(board, 1, null);
        ignoreTimeout = false;

        Move bestMove = completed.move;
        int bestScore = completed.score;
        int completedDepth = 1;

        for (int depth = 2; depth <= Math.max(1, maxDepth); depth++) {
            if (System.nanoTime() >= deadlineNanos) break;

            try {
                RootResult result = searchRoot(board, depth, bestMove);
                bestMove = result.move;
                bestScore = result.score;
                completedDepth = depth;
            } catch (SearchTimeoutException ignored) {
                break;
            }
        }

        return new SearchResult(bestMove, bestScore, completedDepth, nodes);
    }

    private void resetSearchState(Board board) {
        nodes = 0;
        repetitionCounts = new HashMap<>(board.getRepetitionCountsSnapshot());
        searchGeneration++;
        if (searchGeneration == 0) {
            Arrays.fill(ttGenerations, 0);
            searchGeneration = 1;
        }

        for (Move[] killers : killerMoves) {
            Arrays.fill(killers, null);
        }
        for (int[][] sideHistory : history) {
            for (int[] fromHistory : sideHistory) {
                Arrays.fill(fromHistory, 0);
            }
        }
    }

    private RootResult searchRoot(Board board, int depth, Move previousBest) {
        checkTime();

        List<Move> moves = board.getLegalMoves(board.isWhiteToMove());
        if (moves.isEmpty()) return new RootResult(null, 0);

        long rootKey = board.getSearchKey();
        int ttIndex = tableIndex(rootKey);
        Move tableMove = ttGenerations[ttIndex] == searchGeneration
                && ttKeys[ttIndex] == rootKey
                ? ttMoves[ttIndex]
                : null;
        Move orderingMove = previousBest != null ? previousBest : tableMove;
        orderMoves(board, moves, orderingMove, 0);

        Move bestMove = moves.get(0);
        int bestScore = -INFINITY;
        int alpha = -INFINITY;
        int beta = INFINITY;
        boolean firstMove = true;

        for (Move move : moves) {
            checkTime();

            Board child = board.copyAndPlayMoveForSearch(move);
            String childPositionKey = pushPosition(child);
            boolean givesCheck = child.isInCheck(child.isWhiteToMove());
            int extension = depth > 1 && givesCheck ? 1 : 0;
            int childDepth = depth - 1 + extension;
            int score;

            try {
                if (firstMove) {
                    score = -negamax(
                            child, childDepth, -beta, -alpha, 1,
                            givesCheck ? 1 : 0, childPositionKey
                    );
                } else {
                    score = -negamax(
                            child, childDepth, -alpha - 1, -alpha, 1,
                            givesCheck ? 1 : 0, childPositionKey
                    );
                    if (score > alpha && score < beta) {
                        score = -negamax(
                                child, childDepth, -beta, -alpha, 1,
                                givesCheck ? 1 : 0, childPositionKey
                        );
                    }
                }
            } finally {
                popPosition(childPositionKey);
            }

            firstMove = false;
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) alpha = score;
        }

        storeTransposition(rootKey, depth, bestScore, TT_EXACT, bestMove, 0);
        return new RootResult(bestMove, bestScore);
    }

    private int negamax(
            Board board,
            int depth,
            int alpha,
            int beta,
            int ply,
            int checkExtensions,
            String positionKey
    ) {
        nodes++;
        checkTimePeriodically();

        boolean side = board.isWhiteToMove();
        List<Move> moves = board.getLegalMoves(side);
        if (moves.isEmpty()) {
            return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
        }
        if (isDraw(board, positionKey)) return 0;
        if (ply >= MAX_PLY - 1) return evaluateForSide(board, side);
        if (depth <= 0) {
            return quiescence(board, alpha, beta, ply, 0, positionKey);
        }

        long key = board.getSearchKey();
        int index = tableIndex(key);
        boolean tableHit = ttGenerations[index] == searchGeneration
                && ttDepths[index] >= 0
                && ttKeys[index] == key;
        Move tableMove = tableHit ? ttMoves[index] : null;
        boolean tableAllowed = repetitionCounts.getOrDefault(positionKey, 0) <= 1;

        if (tableAllowed && tableHit && ttDepths[index] >= depth) {
            int tableScore = scoreFromTable(ttScores[index], ply);
            if (ttFlags[index] == TT_EXACT) return tableScore;
            if (ttFlags[index] == TT_LOWER) alpha = Math.max(alpha, tableScore);
            else if (ttFlags[index] == TT_UPPER) beta = Math.min(beta, tableScore);
            if (alpha >= beta) return tableScore;
        }

        int originalAlpha = alpha;

        boolean inCheck = board.isInCheck(side);
        orderMoves(board, moves, tableMove, ply);

        Move bestMove = moves.get(0);
        int bestScore = -INFINITY;
        boolean firstMove = true;
        int moveNumber = 0;

        for (Move move : moves) {
            checkTimePeriodically();
            moveNumber++;

            boolean quiet = !isCapture(board, move) && !move.isPromotion();
            Board child = board.copyAndPlayMoveForSearch(move);
            String childPositionKey = pushPosition(child);
            boolean givesCheck = child.isInCheck(child.isWhiteToMove());

            int extension = givesCheck && checkExtensions < MAX_CHECK_EXTENSIONS ? 1 : 0;
            int nextExtensions = checkExtensions + extension;
            int fullDepth = depth - 1 + extension;
            int score;

            try {
                if (firstMove) {
                    score = -negamax(
                            child, fullDepth, -beta, -alpha, ply + 1,
                            nextExtensions, childPositionKey
                    );
                } else {
                    int reduction = lateMoveReduction(
                            depth, moveNumber, quiet, inCheck, givesCheck
                    );
                    int reducedDepth = Math.max(0, fullDepth - reduction);

                    score = -negamax(
                            child, reducedDepth, -alpha - 1, -alpha, ply + 1,
                            nextExtensions, childPositionKey
                    );

                    if (score > alpha && reducedDepth < fullDepth) {
                        score = -negamax(
                                child, fullDepth, -alpha - 1, -alpha, ply + 1,
                                nextExtensions, childPositionKey
                        );
                    }
                    if (score > alpha && score < beta) {
                        score = -negamax(
                                child, fullDepth, -beta, -alpha, ply + 1,
                                nextExtensions, childPositionKey
                        );
                    }
                }
            } finally {
                popPosition(childPositionKey);
            }

            firstMove = false;
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) alpha = score;

            if (alpha >= beta) {
                if (quiet) recordQuietCutoff(move, side, ply, depth);
                if (tableAllowed) {
                    storeTransposition(key, depth, bestScore, TT_LOWER, bestMove, ply);
                }
                return bestScore;
            }
        }

        if (tableAllowed) {
            byte flag = bestScore <= originalAlpha ? TT_UPPER : TT_EXACT;
            storeTransposition(key, depth, bestScore, flag, bestMove, ply);
        }
        return bestScore;
    }

    private int quiescence(
            Board board,
            int alpha,
            int beta,
            int ply,
            int quiescenceDepth,
            String positionKey
    ) {
        nodes++;
        checkTimePeriodically();

        boolean side = board.isWhiteToMove();
        List<Move> legalMoves = board.getLegalMoves(side);
        if (legalMoves.isEmpty()) {
            return board.isInCheck(side) ? -MATE_SCORE + ply : 0;
        }
        if (isDraw(board, positionKey)) return 0;

        boolean inCheck = board.isInCheck(side);
        if (quiescenceDepth >= HARD_QUIESCENCE_DEPTH) {
            return evaluateForSide(board, side);
        }

        if (!inCheck) {
            int standPat = evaluateForSide(board, side);
            if (standPat >= beta) return standPat;
            if (standPat > alpha) alpha = standPat;
            if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH) return alpha;

            legalMoves.removeIf(move -> !isNoisy(board, move));
            if (legalMoves.isEmpty()) return alpha;
        }

        orderMoves(board, legalMoves, null, ply);

        for (Move move : legalMoves) {
            checkTimePeriodically();
            Board child = board.copyAndPlayMoveForSearch(move);
            String childPositionKey = pushPosition(child);
            int score;

            try {
                score = -quiescence(
                        child, -beta, -alpha, ply + 1,
                        quiescenceDepth + 1, childPositionKey
                );
            } finally {
                popPosition(childPositionKey);
            }

            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }

        return alpha;
    }

    private int lateMoveReduction(
            int depth,
            int moveNumber,
            boolean quiet,
            boolean inCheck,
            boolean givesCheck
    ) {
        if (depth < 3 || moveNumber < 5 || !quiet || inCheck || givesCheck) return 0;
        return depth >= 6 && moveNumber >= 10 ? 2 : 1;
    }

    private boolean isDraw(Board board, String positionKey) {
        return board.isSeventyFiveMoveRule()
                || board.isFiftyMoveRule()
                || repetitionCounts.getOrDefault(positionKey, 0) >= 3
                || board.isInsufficientMaterial();
    }

    private int evaluateForSide(Board board, boolean side) {
        int evaluation = evaluator.evaluate(board);
        return side ? evaluation : -evaluation;
    }

    private String pushPosition(Board board) {
        String key = board.getPositionKey();
        repetitionCounts.merge(key, 1, Integer::sum);
        return key;
    }

    private void popPosition(String key) {
        int count = repetitionCounts.getOrDefault(key, 0);
        if (count <= 1) repetitionCounts.remove(key);
        else repetitionCounts.put(key, count - 1);
    }

    private boolean isNoisy(Board board, Move move) {
        return move.isPromotion() || move.isEnPassant() || isCapture(board, move);
    }

    private boolean isCapture(Board board, Move move) {
        return move.isEnPassant() || board.getPiece(move.getEnd()) != null;
    }

    private void orderMoves(Board board, List<Move> moves, Move principalMove, int ply) {
        moves.sort(Comparator.comparingInt(
                move -> -moveOrderingScore(board, move, principalMove, ply)
        ));
    }

    private int moveOrderingScore(Board board, Move move, Move principalMove, int ply) {
        int score = 0;

        if (principalMove != null && sameMove(move, principalMove)) score += 2_000_000;

        if (move.isPromotion()) {
            score += 1_200_000 + move.getPromotionPiece().getValue();
        }

        Piece captured = board.getPiece(move.getEnd());
        if (captured != null || move.isEnPassant()) {
            Piece attacker = board.getPiece(move.getStart());
            int victimValue = captured == null ? 100 : captured.getValue();
            int attackerValue = attacker == null ? 0 : attacker.getValue();
            score += 1_000_000 + victimValue * 16 - attackerValue;
        } else if (ply < MAX_PLY) {
            if (killerMoves[ply][0] != null && sameMove(move, killerMoves[ply][0])) {
                score += 900_000;
            } else if (killerMoves[ply][1] != null && sameMove(move, killerMoves[ply][1])) {
                score += 800_000;
            }

            Piece mover = board.getPiece(move.getStart());
            if (mover != null) {
                int side = mover.isWhite() ? 0 : 1;
                score += history[side][squareIndex(move.getStart())][squareIndex(move.getEnd())];
            }
        }

        if (move.isCastling()) score += 10_000;
        return score;
    }

    private void recordQuietCutoff(Move move, boolean side, int ply, int depth) {
        if (ply < MAX_PLY && !sameMove(move, killerMoves[ply][0])) {
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = move;
        }

        int sideIndex = side ? 0 : 1;
        int from = squareIndex(move.getStart());
        int to = squareIndex(move.getEnd());
        history[sideIndex][from][to] = Math.min(
                750_000,
                history[sideIndex][from][to] + depth * depth
        );
    }

    private int squareIndex(Position position) {
        return position.getRow() * 8 + position.getColumn();
    }

    private boolean sameMove(Move a, Move b) {
        if (a == null || b == null) return false;
        if (!a.getStart().equals(b.getStart()) || !a.getEnd().equals(b.getEnd())) return false;
        if (a.isPromotion() != b.isPromotion()) return false;
        return !a.isPromotion()
                || a.getPromotionPiece().getClass() == b.getPromotionPiece().getClass();
    }

    private int tableIndex(long key) {
        return (int) (key ^ (key >>> 32)) & TT_MASK;
    }

    private void storeTransposition(
            long key,
            int depth,
            int score,
            byte flag,
            Move bestMove,
            int ply
    ) {
        int index = tableIndex(key);
        boolean currentEntry = ttGenerations[index] == searchGeneration;
        if (currentEntry && ttKeys[index] != key && ttDepths[index] > depth + 2) return;
        if (currentEntry && ttKeys[index] == key
                && ttDepths[index] > depth && flag != TT_EXACT) return;

        ttKeys[index] = key;
        ttDepths[index] = depth;
        ttScores[index] = scoreToTable(score, ply);
        ttFlags[index] = flag;
        ttMoves[index] = bestMove;
        ttGenerations[index] = searchGeneration;
    }

    private int scoreToTable(int score, int ply) {
        if (score > MATE_THRESHOLD) return score + ply;
        if (score < -MATE_THRESHOLD) return score - ply;
        return score;
    }

    private int scoreFromTable(int score, int ply) {
        if (score > MATE_THRESHOLD) return score - ply;
        if (score < -MATE_THRESHOLD) return score + ply;
        return score;
    }

    private void checkTimePeriodically() {
        if ((nodes & 255L) == 0L) checkTime();
    }

    private void checkTime() {
        if (!ignoreTimeout && System.nanoTime() >= deadlineNanos) {
            throw new SearchTimeoutException();
        }
    }

    private static class SearchTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record RootResult(Move move, int score) {
    }

    public record SearchResult(Move bestMove, int score, int depth, long nodes) {
    }
}
