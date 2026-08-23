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
 * Check extensions
 *
 * The evaluator handles positional decisions.
 *
 * This class focuses primarily on calculating tactics,
 * forcing sequences, checks, captures, and mating attacks.
 */
public class SearchEngine {

    public static final int MATE_SCORE = 100_000;

    private static final int INFINITY = 1_000_000;

    /*
     * Maximum number of check extensions allowed
     * in one variation.
     */
    private static final int MAX_CHECK_EXTENSIONS = 8;

    /*
     * Maximum depth of quiescence search.
     *
     * This is necessary because checks and captures can
     * create very long tactical sequences.
     *
     * Without a limit, quiescence can recurse forever
     * and eventually cause StackOverflowError.
     */
    private static final int MAX_QUIESCENCE_DEPTH = 8;

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
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        if (legalMoves.isEmpty()) {

            return new SearchResult(
                    null,
                    0,
                    0,
                    0
            );
        }

        nodes = 0;

        deadlineNanos =
                System.nanoTime()
                        + Math.max(
                        1,
                        timeLimitMillis
                ) * 1_000_000L;

        Move bestMove =
                legalMoves.get(0);

        int bestScore =
                -INFINITY;

        int completedDepth = 0;

        /*
         * Iterative deepening.
         *
         * If time expires during a deeper search,
         * the result from the previous completed depth
         * is kept.
         */
        for (
                int depth = 1;
                depth <= maxDepth;
                depth++
        ) {

            try {

                RootResult result =
                        searchRoot(
                                board,
                                depth,
                                bestMove
                        );

                bestMove =
                        result.move;

                bestScore =
                        result.score;

                completedDepth =
                        depth;

            } catch (
                    SearchTimeoutException e
            ) {

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
                board.getLegalMoves(
                        board.isWhiteToMove()
                );

        if (moves.isEmpty()) {

            return new RootResult(
                    null,
                    0
            );
        }

        orderMoves(
                board,
                moves,
                previousBest
        );

        Move bestMove =
                moves.get(0);

        int bestScore =
                -INFINITY;

        int alpha =
                -INFINITY;

        int beta =
                INFINITY;

        for (Move move : moves) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(
                            move
                    );

            /*
             * Give checking moves an extension.
             */
            boolean givesCheck =
                    child.isInCheck(
                            child.isWhiteToMove()
                    );

            int extension =
                    givesCheck
                            ? 1
                            : 0;

            int score =
                    -negamax(
                            child,
                            depth - 1 + extension,
                            -beta,
                            -alpha,
                            1,
                            givesCheck
                                    ? 1
                                    : 0
                    );

            if (score > bestScore) {

                bestScore =
                        score;

                bestMove =
                        move;
            }

            if (score > alpha) {

                alpha =
                        score;
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
     *
     * Checking moves receive search extensions.
     */
    private int negamax(
            Board board,
            int depth,
            int alpha,
            int beta,
            int ply,
            int checkExtensions
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

                /*
                 * Mate sooner is better for the attacker.
                 */
                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * Draw detection.
         */
        if (
                board.isSeventyFiveMoveRule()
                        || board.isFivefoldRepetition()
                        || board.isFiftyMoveRule()
                        || board.isThreefoldRepetition()
                        || board.isInsufficientMaterial()
        ) {

            return 0;
        }

        /*
         * Leaf node.
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

        /*
         * Tactical move ordering.
         */
        orderMoves(
                board,
                moves,
                null
        );

        int best =
                -INFINITY;

        for (Move move : moves) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(
                            move
                    );

            /*
             * Determine whether the move gives check.
             */
            boolean givesCheck =
                    child.isInCheck(
                            child.isWhiteToMove()
                    );

            /*
             * Check extension.
             */
            int extension = 0;

            int newCheckExtensions =
                    checkExtensions;

            if (
                    givesCheck
                            && checkExtensions
                            < MAX_CHECK_EXTENSIONS
            ) {

                extension = 1;

                newCheckExtensions =
                        checkExtensions + 1;
            }

            int score =
                    -negamax(
                            child,
                            depth - 1 + extension,
                            -beta,
                            -alpha,
                            ply + 1,
                            newCheckExtensions
                    );

            if (score > best) {

                best =
                        score;
            }

            if (score > alpha) {

                alpha =
                        score;
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
     * Continues searching tactical positions instead
     * of stopping immediately after a capture.
     *
     * The search is limited by MAX_QUIESCENCE_DEPTH
     * so that endless tactical sequences cannot cause
     * a StackOverflowError.
     */
    private int quiescence(
            Board board,
            int alpha,
            int beta,
            int ply,
            int quiescenceDepth
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
         * This must happen before the depth cutoff so
         * that checkmate is still recognized correctly.
         */
        if (legalMoves.isEmpty()) {

            if (board.isInCheck(side)) {

                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * If the quiescence depth limit has been reached,
         * stop searching and use the static evaluation.
         *
         * This prevents infinite recursion from long
         * sequences of checks or captures.
         */
        if (
                quiescenceDepth
                        >= MAX_QUIESCENCE_DEPTH
        ) {

            return side
                    ? evaluator.evaluate(board)
                    : -evaluator.evaluate(board);
        }

        /*
         * If in check, every legal evasion must be searched.
         *
         * We cannot use stand-pat here because being in
         * check means the current position cannot simply
         * be evaluated without making a move.
         */
        if (board.isInCheck(side)) {

            orderMoves(
                    board,
                    legalMoves,
                    null
            );

            for (Move move :
                    legalMoves) {

                checkTime();

                Board child =
                        board.copyAndPlayMoveForSearch(
                                move
                        );

                int score =
                        -quiescence(
                                child,
                                -beta,
                                -alpha,
                                ply + 1,
                                quiescenceDepth + 1
                        );

                if (score >= beta) {

                    return beta;
                }

                if (score > alpha) {

                    alpha =
                            score;
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

        if (standPat >= beta) {

            return beta;
        }

        if (standPat > alpha) {

            alpha =
                    standPat;
        }

        /*
         * Search tactical moves.
         */
        List<Move> tactical =
                new ArrayList<>();

        for (Move move :
                legalMoves) {

            if (
                    isTactical(
                            board,
                            move
                    )
            ) {

                tactical.add(move);
            }
        }

        /*
         * If there are no tactical moves, the position
         * is quiet enough to stop.
         */
        if (tactical.isEmpty()) {

            return alpha;
        }

        orderMoves(
                board,
                tactical,
                null
        );

        for (Move move :
                tactical) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(
                            move
                    );

            int score =
                    -quiescence(
                            child,
                            -beta,
                            -alpha,
                            ply + 1,
                            quiescenceDepth + 1
                    );

            if (score >= beta) {

                return beta;
            }

            if (score > alpha) {

                alpha =
                        score;
            }
        }

        return alpha;
    }

    /**
     * Determine whether a move is tactical.
     *
     * Tactical moves include:
     *
     * captures
     * promotions
     * en passant
     * checks
     */
    private boolean isTactical(
            Board board,
            Move move
    ) {

        if (move.isPromotion()) {

            return true;
        }

        if (move.isEnPassant()) {

            return true;
        }

        /*
         * Normal capture.
         */
        if (
                board.getPiece(
                        move.getEnd()
                ) != null
        ) {

            return true;
        }

        /*
         * Check detection.
         */
        Board child =
                board.copyAndPlayMoveForSearch(
                        move
                );

        return child.isInCheck(
                child.isWhiteToMove()
        );
    }

    /**
     * Move ordering.
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
     * Score a move for ordering.
     *
     * This does not determine whether a move is good.
     * It determines which moves are searched first.
     */
    private int moveOrderingScore(
            Board board,
            Move move,
            Move principalVariationMove
    ) {

        int score = 0;

        /*
         * Previous best move.
         */
        if (
                principalVariationMove != null
                        && sameMove(
                        move,
                        principalVariationMove
                )
        ) {

            score += 1_000_000;
        }

        /*
         * Promotions.
         */
        if (move.isPromotion()) {

            score +=
                    80_000
                            + move
                            .getPromotionPiece()
                            .getValue();
        }

        /*
         * Captures.
         */
        Piece captured =
                board.getPiece(
                        move.getEnd()
                );

        if (captured != null) {

            Piece attacker =
                    board.getPiece(
                            move.getStart()
                    );

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
         * Castling.
         */
        if (move.isCastling()) {

            score += 2_000;
        }

        /*
         * Checks should be searched very early.
         */
        Board child =
                board.copyAndPlayMoveForSearch(
                        move
                );

        if (
                child.isInCheck(
                        child.isWhiteToMove()
                )
        ) {

            score += 40_000;
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

        if (
                !a.getStart()
                        .equals(b.getStart())
        ) {

            return false;
        }

        if (
                !a.getEnd()
                        .equals(b.getEnd())
        ) {

            return false;
        }

        if (
                a.isPromotion()
                        != b.isPromotion()
        ) {

            return false;
        }

        if (a.isPromotion()) {

            return
                    a.getPromotionPiece()
                            .getClass()
                            ==
                            b.getPromotionPiece()
                                    .getClass();
        }

        return true;
    }

    /**
     * Stop search when the clock expires.
     */
    private void checkTime() {

        if (
                System.nanoTime()
                        >= deadlineNanos
        ) {

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