package chess.engine.evaluation.nnue;

import chess.board.Board;

import java.io.File;
import java.io.IOException;

/**
 * Neural network chess evaluator.
 *
 * The NNUE returns a score from White's perspective.
 *
 * Positive = White is better.
 * Negative = Black is better.
 *
 * Mate is handled by the search, not by this network.
 */
public class NNUE {

    private static final int OUTPUT_SCALE = 400;

    private final NNUEWeights weights;

    private final NNUEFeatureExtractor extractor;

    public NNUE(
            NNUEWeights weights
    ) {

        this.weights = weights;

        this.extractor =
                new NNUEFeatureExtractor();
    }

    /**
     * Evaluate a board.
     *
     * Returns centipawns from White's perspective.
     */
    public int evaluate(Board board) {

        double[] features =
                extractor.extract(board);

        double[] hidden =
                new double[
                        NNUEWeights.HIDDEN_SIZE
                        ];

        /*
         * Input -> first hidden.
         *
         * The feature vector is sparse.
         * Most of the 769 inputs are zero.
         */
        for (
                int h = 0;
                h < NNUEWeights.HIDDEN_SIZE;
                h++
        ) {

            double sum =
                    weights.hiddenBias[h];

            for (
                    int i = 0;
                    i < NNUEWeights.INPUT_SIZE;
                    i++
            ) {

                if (features[i] == 0.0) {
                    continue;
                }

                sum +=
                        features[i]
                                *
                                weights.inputWeights[i][h];
            }

            hidden[h] =
                    relu(sum);
        }

        /*
         * First hidden -> second hidden.
         */
        double[] hidden2 =
                new double[
                        NNUEWeights.SECOND_HIDDEN_SIZE
                        ];

        for (
                int h2 = 0;
                h2 < NNUEWeights.SECOND_HIDDEN_SIZE;
                h2++
        ) {

            double sum =
                    weights.secondHiddenBias[h2];

            for (
                    int h = 0;
                    h < NNUEWeights.HIDDEN_SIZE;
                    h++
            ) {

                sum +=
                        hidden[h]
                                *
                                weights.hiddenWeights[h][h2];
            }

            hidden2[h2] =
                    relu(sum);
        }

        /*
         * Second hidden -> output.
         */
        double rawOutput =
                weights.outputBias;

        for (
                int h2 = 0;
                h2 < NNUEWeights.SECOND_HIDDEN_SIZE;
                h2++
        ) {

            rawOutput +=
                    hidden2[h2]
                            *
                            weights.outputWeights[h2];
        }

        /*
         * Keep the output bounded.
         */
        double normalized =
                Math.tanh(rawOutput);

        return (int) Math.round(
                normalized * OUTPUT_SCALE
        );
    }

    private double relu(
            double value
    ) {

        return Math.max(
                0.0,
                value
        );
    }

    public NNUEWeights getWeights() {
        return weights;
    }

    public void save(File file)
            throws IOException {

        weights.save(file);
    }

    public static NNUE load(File file)
            throws IOException {

        return new NNUE(
                NNUEWeights.load(file)
        );
    }
}