package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;
import chess.pieces.Queen;

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
 * Endgame extensions
 * Promotion extensions
 *
 * The evaluator handles positional decisions.
 *
 * This class focuses heavily on:
 *
 * checks
 * captures
 * queen captures
 * mating attacks
 * promotions
 * tactical sequences
 * endgames
 */
public class SearchEngine {

    public static final int MATE_SCORE = 100_000;

    private static final int INFINITY = 1_000_000;

    /*
     * Maximum number of consecutive checking extensions.
     */
    private static final int MAX_CHECK_EXTENSIONS = 12;

    /*
     * Quiescence needs to be reasonably deep because
     * checking sequences can continue for many moves.
     */
    private static final int MAX_QUIESCENCE_DEPTH = 16;

    /*
     * Extra search depth in simplified positions.
     *
     * Endgames are much more tactical because kings and
     * pawns become important.
     */
    private static final int ENDGAME_EXTENSION = 1;

    /*
     * Promotion positions deserve additional calculation.
     */
    private static final int PROMOTION_EXTENSION = 2;

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

        /*
         * Always check for an immediate mate first.
         *
         * This prevents a positional move from being selected
         * when a mate in one exists.
         */
        Move mateMove =
                findImmediateMate(
                        board,
                        legalMoves
                );

        if (mateMove != null) {

            return new SearchResult(
                    mateMove,
                    MATE_SCORE,
                    1,
                    nodes
            );
        }

        Move bestMove =
                legalMoves.get(0);

        int bestScore =
                -INFINITY;

        int completedDepth = 0;

        /*
         * Iterative deepening.
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
     * Find a checkmate in one.
     */
    private Move findImmediateMate(
            Board board,
            List<Move> moves
    ) {

        for (Move move : moves) {

            checkTime();

            Board child =
                    board.copyAndPlayMoveForSearch(
                            move
                    );

            boolean enemyToMove =
                    child.isWhiteToMove();

            if (
                    child.isInCheck(
                            enemyToMove
                    )
                            &&
                            child.getLegalMoves(
                                    enemyToMove
                            ).isEmpty()
            ) {

                return move;
            }
        }

        return null;
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

            if (
                    board.isInCheck(
                            board.isWhiteToMove()
                    )
            ) {

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
                    board.copyAndPlayMoveForSearch(
                            move
                    );

            boolean givesCheck =
                    child.isInCheck(
                            child.isWhiteToMove()
                    );

            int extension = 0;

            /*
             * Checks deserve an extra ply.
             */
            if (givesCheck) {

                extension += 1;
            }

            /*
             * Promotions deserve extra calculation.
             */
            if (move.isPromotion()) {

                extension +=
                        PROMOTION_EXTENSION;
            }

            /*
             * Simplified endgames deserve extra calculation.
             */
            if (isEndgame(child)) {

                extension +=
                        ENDGAME_EXTENSION;
            }

            int score =
                    -negamax(
                            child,
                            Math.max(
                                    0,
                                    depth - 1 + extension
                            ),
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
                 * Lower ply means faster mate.
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

            boolean givesCheck =
                    child.isInCheck(
                            child.isWhiteToMove()
                    );

            int extension = 0;

            /*
             * Check extension.
             */
            if (
                    givesCheck
                            &&
                            checkExtensions
                                    < MAX_CHECK_EXTENSIONS
            ) {

                extension = 1;
            }

            /*
             * Promotions are extremely tactical.
             */
            if (move.isPromotion()) {

                extension +=
                        PROMOTION_EXTENSION;
            }

            /*
             * Endgames need more calculation because
             * king moves and pawn races are critical.
             */
            if (isEndgame(child)) {

                extension +=
                        ENDGAME_EXTENSION;
            }

            /*
             * IMPORTANT:
             *
             * If this move does not give check, reset the
             * checking sequence.
             *
             * Your previous version did not do this.
             */
            int newCheckExtensions =
                    givesCheck
                            ? checkExtensions + 1
                            : 0;

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
     * Tactical quiescence search.
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
         * Mate or stalemate must always be checked first.
         */
        if (legalMoves.isEmpty()) {

            if (board.isInCheck(side)) {

                return -MATE_SCORE + ply;
            }

            return 0;
        }

        /*
         * If the side to move is in check, ALL evasions
         * must be searched.
         */
        if (board.isInCheck(side)) {

            if (
                    quiescenceDepth
                            >= MAX_QUIESCENCE_DEPTH
            ) {

                /*
                 * Never blindly evaluate a checked position.
                 * Search at least the legal evasions.
                 */
                return side
                        ? evaluator.evaluate(board)
                        : -evaluator.evaluate(board);
            }

            orderMoves(
                    board,
                    legalMoves,
                    null
            );

            for (Move move : legalMoves) {

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
         * Stop eventually.
         */
        if (
                quiescenceDepth
                        >= MAX_QUIESCENCE_DEPTH
        ) {

            return alpha;
        }

        /*
         * Collect forcing moves.
         */
        List<Move> tactical =
                new ArrayList<>();

        for (Move move : legalMoves) {

            if (
                    isTactical(
                            board,
                            move
                    )
            ) {

                tactical.add(move);
            }
        }

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
     * Determine whether a position should be treated
     * as an endgame.
     *
     * This is deliberately more aggressive than the old
     * evaluator test.
     */
    private boolean isEndgame(Board board) {

        int queens = 0;
        int rooks = 0;
        int minors = 0;
        int pawns = 0;

        for (int r = 0; r < 8; r++) {

            for (int c = 0; c < 8; c++) {

                Piece piece =
                        board.getPiece(
                                new chess.board.Position(r, c)
                        );

                if (piece == null) {
                    continue;
                }

                if (piece instanceof Queen) {
                    queens++;
                }

                else if (
                        piece instanceof chess.pieces.Rook
                ) {
                    rooks++;
                }

                else if (
                        piece instanceof chess.pieces.Bishop
                                ||
                                piece instanceof chess.pieces.Knight
                ) {
                    minors++;
                }

                else if (
                        piece instanceof chess.pieces.Pawn
                ) {
                    pawns++;
                }
            }
        }

        /*
         * No queens and at most one rook total.
         */
        if (
                queens == 0
                        &&
                        rooks <= 1
        ) {

            return true;
        }

        /*
         * Queenless positions with limited material.
         */
        if (
                queens == 0
                        &&
                        rooks <= 2
                        &&
                        minors <= 3
        ) {

            return true;
        }

        /*
         * Very low total material.
         */
        if (
                rooks + minors <= 2
                        &&
                        pawns <= 8
        ) {

            return true;
        }

        return false;
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

        Piece captured =
                board.getPiece(
                        move.getEnd()
                );

        if (captured != null) {

            return true;
        }

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
     *
     * This is extremely important for alpha beta.
     *
     * Good tactical moves are searched first.
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
     * Calculate move ordering score.
     *
     * IMPORTANT:
     *
     * This does NOT decide which move is best.
     *
     * It only decides which move gets searched first.
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
                        &&
                        sameMove(
                                move,
                                principalVariationMove
                        )
        ) {

            score += 2_000_000;
        }

        /*
         * Promotions.
         */
        if (move.isPromotion()) {

            score += 300_000;

            if (
                    move.getPromotionPiece()
                            != null
            ) {

                score +=
                        move.getPromotionPiece()
                                .getValue();
            }
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

            int capturedValue =
                    captured.getValue();

            /*
             * Capturing a queen should be extremely high
             * priority.
             */
            if (captured instanceof Queen) {

                score += 500_000;

            } else {

                score +=
                        100_000
                                +
                                capturedValue * 100
                                -
                                attackerValue;
            }
        }

        /*
         * En passant.
         */
        if (move.isEnPassant()) {

            score += 100_000;
        }

        /*
         * Castling.
         */
        if (move.isCastling()) {

            score += 3_000;
        }

        /*
         * Check detection.
         */
        Board child =
                board.copyAndPlayMoveForSearch(
                        move
                );

        boolean givesCheck =
                child.isInCheck(
                        child.isWhiteToMove()
                );

        if (givesCheck) {

            /*
             * Checks are extremely forcing.
             */
            score += 250_000;

            /*
             * A checking capture is even stronger.
             */
            if (captured != null) {

                score += 150_000;
            }
        }

        /*
         * Promotion that also checks.
         */
        if (
                move.isPromotion()
                        &&
                        givesCheck
        ) {

            score += 200_000;
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