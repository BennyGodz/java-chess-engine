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
  void evaluationLabelsAreMergedUsingTheirConfidence() {
    GameTrainer.PositionTable table = new GameTrainer.PositionTable(1);
    int[] indices = {0};
    float[] values = {1.0f};

    table.add(123L, indices, values, 1.0, 0.75, true);
    table.add(123L, indices, values, -1.0, 1.25, true);

    int slot = occupiedSlot(table);
    assertEquals(-0.5 / 2.05, table.targetAt(slot), 1.0e-12);
    assertEquals(1.0f, table.weightAt(slot));
  }

  @Test
  void deeperEvaluationsReceiveMoreConfidence() {
    assertEquals(0.75, GameTrainer.evaluationConfidence(0), 1.0e-12);
    assertTrue(GameTrainer.evaluationConfidence(7) > GameTrainer.evaluationConfidence(3));
    assertEquals(1.25, GameTrainer.evaluationConfidence(12), 1.0e-12);
  }

  @Test
  void checkpointCannotRegressEvaluationQuality() {
    assertTrue(GameTrainer.preservesEvaluationQuality(0.101, 0.1));
    assertFalse(GameTrainer.preservesEvaluationQuality(0.103, 0.1));
  }

  @Test
  void trainingBalancePreventsResultOnlyRowsFromDominating() {
    List<GameTrainer.SparseExample> examples = new ArrayList<>();
    for (int i = 0; i < 4; i++) examples.add(example(true, i));
    for (int i = 0; i < 8; i++) examples.add(example(false, 100 + i));

    List<GameTrainer.SparseExample> balanced = GameTrainer.balanceTrainingExamples(examples);

    assertEquals(5, balanced.size());
    assertEquals(4, balanced.stream().filter(GameTrainer.SparseExample::hasEval).count());
  }

  @Test
  void selfPlayRequiresDeepLabelsAndACompleteNonLoopingGame() {
    PgnGame good = PgnGame.parseSingle(annotatedRuyLopez(" depth 4"));
    PgnGame legacy = PgnGame.parseSingle(annotatedRuyLopez(""));
    PgnGame looping =
        PgnGame.parseSingle(
            "[Event \"test\"]\n"
                + "[Result \"1/2-1/2\"]\n"
                + "[Termination \"threefold repetition\"]\n\n"
                + "1. Nf3 { ev 0 depth 4 } Nf6 { ev 0 depth 4 } "
                + "2. Ng1 { ev 0 depth 4 } Ng8 { ev 0 depth 4 } "
                + "3. Nf3 { ev 0 depth 4 } Nf6 { ev 0 depth 4 } "
                + "4. Ng1 { ev 0 depth 4 } Ng8 { ev 0 depth 4 } 1/2-1/2");

    assertTrue(GameTrainer.isHighQualitySelfPlay(good));
    assertFalse(GameTrainer.isHighQualitySelfPlay(legacy));
    assertFalse(GameTrainer.isHighQualitySelfPlay(looping));
  }

  @Test
  void resultOnlyDuplicatesUseTheirConfidenceWhenMerged() {
    GameTrainer.PositionTable table = new GameTrainer.PositionTable(1);
    int[] indices = {0};
    float[] values = {1.0f};

    table.add(123L, indices, values, 1.0, 0.1, false);
    table.add(123L, indices, values, -1.0, 0.9, false);

    assertEquals(-0.8 / 1.05, table.targetAt(occupiedSlot(table)), 1.0e-12);
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
  void pipelineSelfPlayGrowthIsIncreasingAndBounded() {
    assertEquals(256, TrainingPipeline.progressiveValue(256, 0, 1.2, 4.0));
    assertEquals(307, TrainingPipeline.progressiveValue(256, 1, 1.2, 4.0));
    assertEquals(1024, TrainingPipeline.progressiveValue(256, 100, 1.2, 4.0));
  }

  @Test
  void pgnParserKeepsEvaluationDepthAndSupportsLegacyComments() {
    PgnGame game =
        PgnGame.parseSingle(
            "[Event \"test\"]\n[Result \"1-0\"]\n\n1. e4 { ev 24 depth 7 } e5 { ev 10 } 1-0");

    assertEquals(24.0, game.getEvalCp()[0]);
    assertEquals(7, game.getEvalDepth()[0]);
    assertEquals(10.0, game.getEvalCp()[1]);
    assertEquals(0, game.getEvalDepth()[1]);
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

  private static String annotatedRuyLopez(String depth) {
    return "[Event \"test\"]\n"
        + "[Result \"1-0\"]\n"
        + "[Termination \"evaluation adjudication\"]\n\n"
        + "1. e4 { ev 10"
        + depth
        + " } e5 { ev -10"
        + depth
        + " } 2. Nf3 { ev 12"
        + depth
        + " } Nc6 { ev -12"
        + depth
        + " } 3. Bb5 { ev 14"
        + depth
        + " } a6 { ev -14"
        + depth
        + " } 4. Ba4 { ev 16"
        + depth
        + " } Nf6 { ev -16"
        + depth
        + " } 5. O-O { ev 18"
        + depth
        + " } Be7 { ev -18"
        + depth
        + " } 6. Re1 { ev 20"
        + depth
        + " } b5 { ev -20"
        + depth
        + " } 7. Bb3 { ev 22"
        + depth
        + " } d6 { ev -22"
        + depth
        + " } 8. c3 { ev 24"
        + depth
        + " } O-O { ev -24"
        + depth
        + " } 1-0";
  }
}
