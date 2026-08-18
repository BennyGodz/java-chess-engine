package chess.engine.search;

import chess.board.Board;
import chess.board.Move;
import chess.engine.evaluation.Evaluator;
import chess.pieces.Piece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Engine search: iterative deepening, negamax alpha-beta, quiescence,
 * and basic capture/promotion/castling move ordering.
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

    public SearchResult findBestMove(Board board, int maxDepth, long timeLimitMillis) {
        List<Move> legalMoves = board.getLegalMoves(board.isWhiteToMove());
        if (legalMoves.isEmpty()) return new SearchResult(null, 0, 0, 0);

        nodes = 0;
        deadlineNanos = System.nanoTime() + Math.max(1, timeLimitMillis) * 1_000_000L;

        Move bestMove = legalMoves.get(0);
        int bestScore = board.isWhiteToMove() ? -INFINITY : INFINITY;
        int completedDepth = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            try {
                RootResult result = searchRoot(board, depth, bestMove);
                bestMove = result.move;
                bestScore = result.score;
                completedDepth = depth;
            } catch (SearchTimeoutException e) {
                break;
            }
        }

        return new SearchResult(bestMove, bestScore, completedDepth, nodes);
    }

    private RootResult searchRoot(Board board, int depth, Move previousBest) {
        checkTime();

        List<Move> moves = board.getLegalMoves(board.isWhiteToMove());
        orderMoves(board, moves, previousBest);

        Move bestMove = moves.get(0);
        int bestScore = -INFINITY;
        int alpha = -INFINITY;
        int beta = INFINITY;

        // Negamax means every score here is from the current side's perspective,
        // so the root ALWAYS maximizes after negating the child score.
        for (Move move : moves) {
            checkTime();
            Board child = board.copyAndPlayMoveForSearch(move);
            int score = -negamax(child, depth - 1, -beta, -alpha, 1);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            alpha = Math.max(alpha, bestScore);
        }

        return new RootResult(bestMove, bestScore);
    }

    /** Return score from the side-to-move's point of view. */
    private int negamax(Board board, int depth, int alpha, int beta, int ply) {
        nodes++;
        checkTime();

        boolean side = board.isWhiteToMove();
        List<Move> moves = board.getLegalMoves(side);

        if (moves.isEmpty()) {
            if (board.isInCheck(side)) return -MATE_SCORE + ply;
            return 0;
        }

        if (board.isSeventyFiveMoveRule()
                || board.isFivefoldRepetition()
                || board.isFiftyMoveRule()
                || board.isThreefoldRepetition()
                || board.isInsufficientMaterial()) {
            return 0;
        }

        if (depth <= 0) return quiescence(board, alpha, beta, ply);

        orderMoves(board, moves, null);

        int best = -INFINITY;
        for (Move move : moves) {
            Board child = board.copyAndPlayMoveForSearch(move);
            int score = -negamax(child, depth - 1, -beta, -alpha, ply + 1);

            if (score > best) best = score;
            if (score > alpha) alpha = score;
            if (alpha >= beta) break;
        }

        return best;
    }

    private int quiescence(Board board, int alpha, int beta, int ply) {
        nodes++;
        checkTime();

        boolean side = board.isWhiteToMove();
        List<Move> legalMoves = board.getLegalMoves(side);

        // If the side to move is in check, quiescence cannot stand-pat.
        // It must consider every legal check-evasion move.
        if (board.isInCheck(side)) {
            if (legalMoves.isEmpty()) return -MATE_SCORE + ply;
            orderMoves(board, legalMoves, null);
            for (Move move : legalMoves) {
                Board child = board.copyAndPlayMoveForSearch(move);
                int score = -quiescence(child, -beta, -alpha, ply + 1);
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
            return alpha;
        }

        int standPat = side ? evaluator.evaluate(board) : -evaluator.evaluate(board);

        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        List<Move> tactical = new ArrayList<>();
        for (Move move : legalMoves) {
            if (isTactical(board, move)) tactical.add(move);
        }
        orderMoves(board, tactical, null);

        for (Move move : tactical) {
            Board child = board.copyAndPlayMoveForSearch(move);
            int score = -quiescence(child, -beta, -alpha, ply + 1);
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }

        return alpha;
    }

    private boolean isTactical(Board board, Move move) {
        return move.isPromotion()
                || move.isEnPassant()
                || board.getPiece(move.getEnd()) != null;
    }

    private void orderMoves(Board board, List<Move> moves, Move principalVariationMove) {
        moves.sort(Comparator.comparingInt(
                move -> -moveOrderingScore(board, move, principalVariationMove)
        ));
    }

    private int moveOrderingScore(Board board, Move move, Move principalVariationMove) {
        int score = 0;

        if (principalVariationMove != null && sameMove(move, principalVariationMove)) {
            score += 1_000_000;
        }

        if (move.isPromotion()) score += 80_000 + move.getPromotionPiece().getValue();

        Piece captured = board.getPiece(move.getEnd());
        if (captured != null) {
            Piece attacker = board.getPiece(move.getStart());
            int attackerValue = attacker == null ? 0 : attacker.getValue();
            score += 50_000 + captured.getValue() * 10 - attackerValue;
        }

        if (move.isEnPassant()) score += 50_000;
        if (move.isCastling()) score += 1_000;
        return score;
    }

    private boolean sameMove(Move a, Move b) {
        return a.getStart().equals(b.getStart())
                && a.getEnd().equals(b.getEnd())
                && a.isPromotion() == b.isPromotion()
                && (!a.isPromotion()
                || a.getPromotionPiece().getClass() == b.getPromotionPiece().getClass());
    }

    private void checkTime() {
        if (System.nanoTime() >= deadlineNanos) throw new SearchTimeoutException();
    }

    private static class SearchTimeoutException extends RuntimeException {}

    private record RootResult(Move move, int score) {}

    public record SearchResult(Move bestMove, int score, int depth, long nodes) {}
}
