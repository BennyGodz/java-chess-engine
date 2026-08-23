package chess.engine.evaluation;

import chess.board.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluatorTest {

    @Test
    void kingShieldEvaluationIsColorSymmetric() {
        Board blackShieldOnly = board(
                "3q2k1/5ppp/8/8/8/8/8/3Q2K1 w - - 0 1"
        );
        Board whiteShieldOnly = board(
                "3q2k1/8/8/8/8/8/5PPP/3Q2K1 w - - 0 1"
        );

        Evaluator evaluator = new Evaluator();
        assertEquals(
                -evaluator.evaluate(whiteShieldOnly),
                evaluator.evaluate(blackShieldOnly)
        );
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.loadFEN(fen);
        return board;
    }
}
