package chess.engine.evaluation.nnue;

import chess.board.Board;

import java.io.File;
import java.io.IOException;

/**
 * Facade around {@link NNUE} for use by {@link chess.engine.evaluation.Evaluator}.
 */
public class NNUEEvaluator {

    private static final String[] WEIGHT_CANDIDATES = {
            "nnue_weights_best.bin",
            "nnue_weights.bin",
            "nnue.weights"
    };

    private final NNUE nnue;

    public NNUEEvaluator() {
        this(loadDefaultWeights());
    }

    public NNUEEvaluator(File weightsFile) {
        try {
            this.nnue = NNUE.load(weightsFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load NNUE weights: "
                            + weightsFile.getPath(),
                    e
            );
        }
    }

    public NNUEEvaluator(NNUE nnue) {
        this.nnue = nnue;
    }

    /**
     * Evaluate from White's perspective in centipawns.
     */
    public int evaluate(Board board) {
        return nnue.evaluate(board);
    }

    private static NNUE loadDefaultWeights() {
        for (String name : WEIGHT_CANDIDATES) {
            File file = new File(name);
            if (!file.isFile()) {
                continue;
            }

            try {
                System.out.println(
                        "Loaded NNUE weights: "
                                + file.getPath()
                );
                return NNUE.load(file);
            } catch (IOException ignored) {
                // try next candidate
            }
        }

        System.err.println(
                "WARNING: No NNUE weights found. "
                        + "Using random weights — play quality will be poor. "
                        + "Run NNUETrainer to generate weights."
        );
        return new NNUE(NNUEWeights.random());
    }
}
