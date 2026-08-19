package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chess engine search.
 *
 * Features:
 *  iterative deepening
 *  negamax alpha beta pruning
 *  quiescence search
 *  check extensions in quiescence
 *  capture and promotion ordering
 *  check move ordering
 *  castling move ordering
 *  mate distance scoring
 */
public class SearchEngine {

    public static final int MATE_SCORE = 100_000;
    private static final int INFINITY = 1_000_000;

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
                        + Math.max(1, timeLimitMillis)
                        * 1_000_000L;

        Move bestMove = legalMoves.get(0);

        int bestScore =
                -INFINITY;

        int completedDepth = 0;

        /*
         * Iterative deepening.
         *
         * The best completed depth is always preserved if the
         * next depth times out.
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
     *
     * Scores are always from the side to move's perspective.
     */
    private RootResult searchRoot(
            Board board,
            int depth,
            Move previousBest
    ) {

        checkTime();

        boolean side =
                board.isWhiteToMove();

        List<Move> moves =
                board.getLegalMoves(side);

        if (moves.isEmpty()) {

            if (board.isInCheck(side)) {
                return new RootResult(
                        null,
                        -MATE_SCORE
                );
            }

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
     * The returned score is always from the perspective of
     * the side whose turn it currently is.
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

                /*
                 * The extra ply means:
                 *
                 * mate in 1 is better than mate in 2
                 * when we are mating
                 *
                 * and delaying mate is preferred when losing.
                 */
                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * Draw rules.
         */
        if (board.isSeventyFiveMoveRule()
                || board.isFivefoldRepetition()
                || board.isFiftyMoveRule()
                || board.isThreefoldRepetition()
                || board.isInsufficientMaterial()) {

            return 0;
        }

        /*
         * At the leaf, enter quiescence search instead of
         * immediately evaluating the position.
         */
        if (depth <= 0) {

            return quiescence(
                    board,
                    alpha,
                    beta,
                    ply
            );
        }

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
     * Searches:
     *  captures
     *  promotions
     *  en passant
     *  CHECKS
     *
     * Searching checks is very important because otherwise the
     * evaluator can stop immediately before a forcing attack.
     */
    private int quiescence(
            Board board,
            int alpha,
            int beta,
            int ply
    ) {

        nodes++;

        checkTime();

        boolean side =
                board.isWhiteToMove();

        List<Move> legalMoves =
                board.getLegalMoves(side);

        /*
         * If we are in check, there is no stand pat.
         *
         * Every legal check evasion must be searched.
         */
        if (board.isInCheck(side)) {

            if (legalMoves.isEmpty()) {
                return -MATE_SCORE + ply;
            }

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
                                ply + 1
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

        if (standPat >= beta) {
            return beta;
        }

        if (standPat > alpha) {
            alpha = standPat;
        }

        /*
         * Search tactical moves.
         *
         * This now includes CHECKS.
         */
        List<Move> tactical =
                new ArrayList<>();

        for (Move move : legalMoves) {

            if (isTactical(board, move)) {
                tactical.add(move);
            }
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
                            ply + 1
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
     * Determines whether a move is tactical.
     *
     * A tactical move is:
     *
     *  capture
     *  promotion
     *  en passant
     *  CHECK
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
        if (board.getPiece(move.getEnd()) != null) {
            return true;
        }

        /*
         * Check detection.
         *
         * Make the move and see whether the opponent is now
         * in check.
         */
        Board child =
                board.copyAndPlayMoveForSearch(move);

        boolean opponent =
                child.isWhiteToMove();

        return child.isInCheck(opponent);
    }

    /**
     * Orders moves so the strongest forcing moves are searched first.
     *
     * Good move ordering dramatically improves alpha beta pruning.
     */
    private void orderMoves(
            Board board,
            List<Move> moves,
            Move principalVariationMove
    ) {

        moves.sort(
                Comparator.comparingInt(
                        move -> -moveOrderingScore(
                                board,
                                move,
                                principalVariationMove
                        )
                )
        );
    }

    /**
     * Move ordering score.
     */
    private int moveOrderingScore(
            Board board,
            Move move,
            Move principalVariationMove
    ) {

        int score = 0;

        /*
         * Previous best move from iterative deepening.
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

            /*
             * MVV LVA style ordering.
             *
             * Most valuable victim
             * Least valuable attacker
             */
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
         * Checks are highly forcing and should be searched early.
         */
        if (givesCheck(board, move)) {
            score += 40_000;
        }

        /*
         * Castling is useful, but not more important than tactical
         * moves.
         */
        if (move.isCastling()) {
            score += 1_000;
        }

        return score;
    }

    /**
     * Determines whether a move gives check.
     */
    private boolean givesCheck(
            Board board,
            Move move
    ) {

        Board child =
                board.copyAndPlayMoveForSearch(move);

        boolean opponent =
                child.isWhiteToMove();

        return child.isInCheck(opponent);
    }

    /**
     * Compare two moves.
     */
    private boolean sameMove(
            Move a,
            Move b
    ) {

        return a.getStart().equals(b.getStart())
                && a.getEnd().equals(b.getEnd())
                && a.isPromotion() == b.isPromotion()
                && (!a.isPromotion()
                || a.getPromotionPiece()
                .getClass()
                == b.getPromotionPiece()
                .getClass());
    }

    /**
     * Check whether the search has exceeded its time limit.
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