package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chess search engine using:
 *
 * Iterative deepening
 * Negamax
 * Alpha beta pruning
 * Quiescence search
 * Tactical move ordering
 *
 * The evaluator handles positional decisions such as:
 * development
 * center control
 * king safety
 * queen development
 * knight placement
 *
 * This class focuses primarily on calculating tactics and variations.
 */
public class SearchEngine {

    public static final int MATE_SCORE = 100_000;
    private static final int INFINITY = 1_000_000;

    /*
     * Maximum depth of quiescence search.
     *
     * Without a limit, a sequence of checks can cause
     * quiescence to recurse indefinitely.
     */
    private static final int MAX_QUIESCENCE_DEPTH = 12;

    private final Evaluator evaluator;

    private long nodes;
    private long deadlineNanos;

    public SearchEngine() {
        this(new Evaluator());
    }

    public SearchEngine(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Find the best move using iterative deepening.
     */
    public SearchResult findBestMove(
            Board board,
            int maxDepth,
            long timeLimitMillis
    ) {

        List<Move> legalMoves =
                board.getLegalMoves(board.isWhiteToMove());

        if (legalMoves.isEmpty()) {
            return new SearchResult(null, 0, 0, 0);
        }

        nodes = 0;

        deadlineNanos =
                System.nanoTime()
                        + Math.max(1, timeLimitMillis) * 1_000_000L;

        Move bestMove = legalMoves.get(0);

        int bestScore =
                board.isWhiteToMove()
                        ? -INFINITY
                        : INFINITY;

        int completedDepth = 0;

        /*
         * Iterative deepening.
         *
         * If the clock expires during a deeper search,
         * the result from the previous completed depth
         * remains valid.
         */
        for (int depth = 1; depth <= maxDepth; depth++) {

            try {

                RootResult result =
                        searchRoot(
                                board,
                                depth,
                                bestMove
                        );

                bestMove = result.move;
                bestScore = result.score;
                completedDepth = depth;

            } catch (SearchTimeoutException e) {

                break;
            }
        }

        return new SearchResult(
                bestMove,
                bestScore,
                completedDepth,
                nodes
        );
    }

    /**
     * Root search.
     */
    private RootResult searchRoot(
            Board board,
            int depth,
            Move previousBest
    ) {

        checkTime();

        List<Move> moves =
                board.getLegalMoves(board.isWhiteToMove());

        if (moves.isEmpty()) {
            return new RootResult(null, 0);
        }

        orderMoves(
                board,
                moves,
                previousBest
        );

        Move bestMove = moves.get(0);

        int bestScore = -INFINITY;

        int alpha = -INFINITY;
        int beta = INFINITY;

        for (Move move : moves) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(move);

            int score =
                    -negamax(
                            child,
                            depth - 1,
                            -beta,
                            -alpha,
                            1
                    );

            if (score > bestScore) {

                bestScore = score;
                bestMove = move;
            }

            if (score > alpha) {
                alpha = score;
            }
        }

        return new RootResult(
                bestMove,
                bestScore
        );
    }

    /**
     * Negamax alpha beta search.
     *
     * Scores are always returned from the perspective
     * of the side whose turn it is.
     */
    private int negamax(
            Board board,
            int depth,
            int alpha,
            int beta,
            int ply
    ) {

        nodes++;

        checkTime();

        boolean side =
                board.isWhiteToMove();

        List<Move> moves =
                board.getLegalMoves(side);

        /*
         * Checkmate or stalemate.
         */
        if (moves.isEmpty()) {

            if (board.isInCheck(side)) {

                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * Draw detection.
         */
        if (board.isSeventyFiveMoveRule()
                || board.isFivefoldRepetition()
                || board.isFiftyMoveRule()
                || board.isThreefoldRepetition()
                || board.isInsufficientMaterial()) {

            return 0;
        }

        /*
         * Reached the leaf.
         */
        if (depth <= 0) {

            return quiescence(
                    board,
                    alpha,
                    beta,
                    ply,
                    0
            );
        }

        orderMoves(
                board,
                moves,
                null
        );

        int best = -INFINITY;

        for (Move move : moves) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(move);

            int score =
                    -negamax(
                            child,
                            depth - 1,
                            -beta,
                            -alpha,
                            ply + 1
                    );

            if (score > best) {
                best = score;
            }

            if (score > alpha) {
                alpha = score;
            }

            /*
             * Beta cutoff.
             */
            if (alpha >= beta) {
                break;
            }
        }

        return best;
    }

    /**
     * Quiescence search.
     *
     * Continues calculating tactical positions instead
     * of evaluating immediately after a capture.
     *
     * Quiescence also considers checks, but only up to
     * MAX_QUIESCENCE_DEPTH. This prevents endless sequences
     * of checking moves from overflowing the Java stack.
     */
    private int quiescence(
            Board board,
            int alpha,
            int beta,
            int ply,
            int qDepth
    ) {

        nodes++;

        checkTime();

        boolean side =
                board.isWhiteToMove();

        List<Move> legalMoves =
                board.getLegalMoves(side);

        /*
         * Checkmate or stalemate.
         *
         * This must be checked before the quiescence
         * depth limit because checkmate is always tactical.
         */
        if (legalMoves.isEmpty()) {

            if (board.isInCheck(side)) {
                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * Safety limit for quiescence.
         *
         * Once we have gone deep enough into a tactical
         * sequence, stop recursively searching.
         *
         * This is especially important because checks can
         * create long or repeating tactical sequences.
         */
        if (qDepth >= MAX_QUIESCENCE_DEPTH) {

            int evaluation =
                    evaluator.evaluate(board);

            return side
                    ? evaluation
                    : -evaluation;
        }

        /*
         * If in check, every legal move is a possible
         * check evasion and therefore must be considered.
         */
        if (board.isInCheck(side)) {

            orderMoves(
                    board,
                    legalMoves,
                    null
            );

            for (Move move : legalMoves) {

                checkTime();

                Board child =
                        board.copyAndPlayMoveForSearch(move);

                int score =
                        -quiescence(
                                child,
                                -beta,
                                -alpha,
                                ply + 1,
                                qDepth + 1
                        );

                if (score >= beta) {
                    return beta;
                }

                if (score > alpha) {
                    alpha = score;
                }
            }

            return alpha;
        }

        /*
         * Static evaluation.
         */
        int standPat =
                side
                        ? evaluator.evaluate(board)
                        : -evaluator.evaluate(board);

        /*
         * If the static evaluation is already good enough
         * to cause a beta cutoff, no tactical continuation
         * needs to be searched.
         */
        if (standPat >= beta) {
            return beta;
        }

        if (standPat > alpha) {
            alpha = standPat;
        }

        /*
         * Only investigate forcing moves.
         */
        List<Move> tactical =
                new ArrayList<>();

        for (Move move : legalMoves) {

            if (isTactical(board, move)) {
                tactical.add(move);
            }
        }

        /*
         * If there are no tactical moves, this is a quiet
         * position and the static evaluation is sufficient.
         */
        if (tactical.isEmpty()) {
            return alpha;
        }

        orderMoves(
                board,
                tactical,
                null
        );

        for (Move move : tactical) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(move);

            int score =
                    -quiescence(
                            child,
                            -beta,
                            -alpha,
                            ply + 1,
                            qDepth + 1
                    );

            if (score >= beta) {
                return beta;
            }

            if (score > alpha) {
                alpha = score;
            }
        }

        return alpha;
    }

    /**
     * Tactical moves are:
     *
     * captures
     * promotions
     * en passant
     * checks
     *
     * Checks are detected by making the move once.
     *
     * This is only used in quiescence, so the extra board
     * copy is worthwhile because it prevents the engine
     * from stopping in tactical positions.
     */
    private boolean isTactical(
            Board board,
            Move move
    ) {

        /*
         * Promotions are always tactical.
         */
        if (move.isPromotion()) {
            return true;
        }

        /*
         * En passant is always tactical.
         */
        if (move.isEnPassant()) {
            return true;
        }

        /*
         * Normal capture.
         */
        if (board.getPiece(move.getEnd()) != null) {
            return true;
        }

        /*
         * Detect checks.
         *
         * This is intentionally NOT used during normal
         * move ordering because doing so for every move
         * at every search node is expensive.
         */
        Board child =
                board.copyAndPlayMoveForSearch(move);

        return child.isInCheck(child.isWhiteToMove());
    }

    /**
     * Move ordering.
     *
     * Good ordering is extremely important for alpha beta.
     *
     * Priority:
     *
     * 1. Previous principal variation move
     * 2. Promotions
     * 3. Captures
     * 4. Castling
     * 5. Quiet moves
     */
    private void orderMoves(
            Board board,
            List<Move> moves,
            Move principalVariationMove
    ) {

        moves.sort(
                Comparator.comparingInt(
                        move ->
                                -moveOrderingScore(
                                        board,
                                        move,
                                        principalVariationMove
                                )
                )
        );
    }

    /**
     * Score a move for search ordering.
     *
     * This does NOT determine whether the move is good.
     * It only determines which move should be searched first.
     */
    private int moveOrderingScore(
            Board board,
            Move move,
            Move principalVariationMove
    ) {

        int score = 0;

        /*
         * Previous best move.
         *
         * Searching this first gives alpha beta a much
         * better chance of producing cutoffs.
         */
        if (principalVariationMove != null
                && sameMove(
                move,
                principalVariationMove
        )) {

            score += 1_000_000;
        }

        /*
         * Promotions.
         */
        if (move.isPromotion()) {

            score +=
                    80_000
                            + move.getPromotionPiece().getValue();
        }

        /*
         * Captures.
         *
         * MVV LVA:
         * Most Valuable Victim
         * Least Valuable Attacker
         */
        Piece captured =
                board.getPiece(move.getEnd());

        if (captured != null) {

            Piece attacker =
                    board.getPiece(move.getStart());

            int attackerValue =
                    attacker == null
                            ? 0
                            : attacker.getValue();

            score +=
                    50_000
                            + captured.getValue() * 10
                            - attackerValue;
        }

        /*
         * En passant.
         */
        if (move.isEnPassant()) {
            score += 50_000;
        }

        /*
         * Castling is a useful quiet move.
         */
        if (move.isCastling()) {
            score += 2_000;
        }

        return score;
    }

    /**
     * Compare two moves.
     */
    private boolean sameMove(
            Move a,
            Move b
    ) {

        if (!a.getStart().equals(b.getStart())) {
            return false;
        }

        if (!a.getEnd().equals(b.getEnd())) {
            return false;
        }

        if (a.isPromotion() != b.isPromotion()) {
            return false;
        }

        if (a.isPromotion()) {

            return a.getPromotionPiece()
                    .getClass()
                    ==
                    b.getPromotionPiece()
                            .getClass();
        }

        return true;
    }

    /**
     * Stop the search when the time limit expires.
     */
    private void checkTime() {

        if (System.nanoTime() >= deadlineNanos) {
            throw new SearchTimeoutException();
        }
    }

    private static class SearchTimeoutException
            extends RuntimeException {
    }

    private record RootResult(
            Move move,
            int score
    ) {
    }

    public record SearchResult(
            Move bestMove,
            int score,
            int depth,
            long nodes
    ) {
    }
}