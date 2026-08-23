package chess.engine.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import chess.engine.evaluation.nnue.NNUEWeights;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameTrainerTest {

  @Test
  void evaluationTargetKeepsMoverPerspectiveOnBothTurns() {
    double whiteTarget = GameTrainer.blendedSideToMoveTarget(0.0, 80, 20, true, 200.0, true);
    double blackTarget = GameTrainer.blendedSideToMoveTarget(0.0, 80, 20, false, 200.0, true);

    assertEquals(whiteTarget, blackTarget, 1.0e-12);
    assertTrue(whiteTarget > 0.0);
  }

  @Test
  void resultOnlyTargetChangesPerspectiveOnBlackTurn() {
    double whiteTarget = GameTrainer.blendedSideToMoveTarget(1.0, 80, 20, true, 0.0, false);
    double blackTarget = GameTrainer.blendedSideToMoveTarget(1.0, 80, 20, false, 0.0, false);

    assertEquals(whiteTarget, -blackTarget, 1.0e-12);
  }

  @Test
  void evaluationLabelsTakePriorityWhenDuplicatePositionsAreMerged() {
    GameTrainer.PositionTable table = new GameTrainer.PositionTable(1);
    int[] indices = {0};
    float[] values = {1.0f};

    table.add(123L, indices, values, -0.9, 0.1, false);
    table.add(123L, indices, values, 0.6, 1.0, true);
    table.add(123L, indices, values, -0.8, 0.1, false);

    int slot = occupiedSlot(table);
    assertEquals(0.6 / 1.05, table.targetAt(slot), 1.0e-12);
    assertEquals(1.0f, table.weightAt(slot));
    assertTrue(table.hasEvalAt(slot));
  }

  @Test
  void trainingBalancePreventsResultOnlyRowsFromDominating() {
    List<GameTrainer.SparseExample> examples = new ArrayList<>();
    for (int i = 0; i < 2; i++) examples.add(example(true, i));
    for (int i = 0; i < 8; i++) examples.add(example(false, 100 + i));

    List<GameTrainer.SparseExample> balanced = GameTrainer.balanceTrainingExamples(examples);

    assertEquals(4, balanced.size());
    assertEquals(2, balanced.stream().filter(GameTrainer.SparseExample::hasEval).count());
  }

  @Test
  void pipelineReadsTheLatestObjectivesAllTimeMetric() {
    double mse =
        TrainingPipeline.parseMetric(
            "2026-08-22 | RUN DONE | runBest 0.1910 | allTime 0.1908 | baseline 0.2132",
            "allTime ");

    assertEquals(0.1908, mse, 1.0e-12);
  }

  @Test
  void timeoutSentinelEvaluationsAreRejected() {
    assertTrue(GameTrainer.isUsableEvaluation(900.0));
    assertTrue(GameTrainer.isUsableEvaluation(-950.0));
    assertFalse(GameTrainer.isUsableEvaluation(1000.0));
    assertFalse(GameTrainer.isUsableEvaluation(Double.POSITIVE_INFINITY));
    assertFalse(GameTrainer.isUsableEvaluation(Double.NaN));
  }

  @Test
  void copiedWeightsDoNotShareArrays() {
    NNUEWeights weights = NNUEWeights.random(1);
    NNUEWeights copy = weights.copy();

    assertNotSame(weights.inputWeights, copy.inputWeights);
    assertEquals(weights.inputWeights[0][0], copy.inputWeights[0][0]);
    copy.inputWeights[0][0]++;
    assertNotEquals(weights.inputWeights[0][0], copy.inputWeights[0][0]);
  }

  private static int occupiedSlot(GameTrainer.PositionTable table) {
    for (int slot = 0; slot < table.capacity(); slot++) {
      if (table.isOccupied(slot)) return slot;
    }
    throw new AssertionError("Expected an occupied position-table slot.");
  }

  private static GameTrainer.SparseExample example(boolean hasEval, long key) {
    return new GameTrainer.SparseExample(
        new int[] {0}, new float[] {1.0f}, 0.25, 1.0f, key, hasEval);
  }
}
