package chess.lichess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LichessGameTest {

  @Test
  void bulletUsesHalfToOneSecondWhileTheClockIsHealthy() {
    assertEquals(750, LichessGame.calculateSearchTime(60_000, 0, 60_000));
    assertEquals(1_000, LichessGame.calculateSearchTime(60_000, 1_000, 60_000));
  }

  @Test
  void longerModesReceiveMoreSearchTime() {
    assertEquals(3_600, LichessGame.calculateSearchTime(180_000, 0, 180_000));
    assertEquals(6_666, LichessGame.calculateSearchTime(300_000, 0, 300_000));
  }

  @Test
  void timeTroubleMovesAlmostInstantlyInEveryMode() {
    assertEquals(200, LichessGame.calculateSearchTime(10_000, 0, 300_000));
    assertEquals(75, LichessGame.calculateSearchTime(5_000, 0, 300_000));
    assertEquals(20, LichessGame.calculateSearchTime(2_000, 0, 300_000));
    assertEquals(1, LichessGame.calculateSearchTime(100, 0, 300_000));
  }
}
