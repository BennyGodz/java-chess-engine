package chess.engine.evaluation;

import chess.board.Board;
import chess.engine.evaluation.nnue.NNUEEvaluator;

/**
 * Evaluation facade.
 *
 * The chess engine now relies entirely on the trained NNUE
 * for positional evaluation.
 *
 * Checkmate and draw detection remain chess rules and are
 * handled separately from the neural evaluation.
 */
public class Evaluator {

    private final NNUEEvaluator nnue;

    public Evaluator() {
        nnue = new NNUEEvaluator();
    }

    /**
     * Evaluate the position from White's perspective.
     *
     * Positive = White is better.
     * Negative = Black is better.
     */
    public int evaluate(Board board) {

        if (board.isCheckmate(true)) {
            return -100_000;
        }

        if (board.isCheckmate(false)) {
            return 100_000;
        }

        if (board.isStalemate(true)
                || board.isStalemate(false)
                || board.isSeventyFiveMoveRule()
                || board.isFivefoldRepetition()
                || board.isInsufficientMaterial()) {
            return 0;
        }

        return nnue.evaluate(board);
    }
}