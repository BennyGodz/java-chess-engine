package chess.lichess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LichessGameTest {

  @Test
  void bulletThinksEarlyAndTapersWithItsClock() {
    assertEquals(1_333, LichessGame.calculateSearchTime(60_000, 0, 60_000));
    assertEquals(1_500, LichessGame.calculateSearchTime(60_000, 1_000, 60_000));
    assertEquals(500, LichessGame.calculateSearchTime(30_000, 0, 60_000));
  }

  @Test
  void longerModesReceiveMoreSearchTime() {
    assertEquals(3_600, LichessGame.calculateSearchTime(180_000, 0, 180_000));
    assertEquals(6_666, LichessGame.calculateSearchTime(300_000, 0, 300_000));
  }

  @Test
  void timeTroubleMovesAlmostInstantlyInEveryMode() {
    assertEquals(100, LichessGame.calculateSearchTime(10_000, 0, 300_000));
    assertEquals(20, LichessGame.calculateSearchTime(5_000, 0, 300_000));
    assertEquals(5, LichessGame.calculateSearchTime(2_000, 0, 300_000));
    assertEquals(1, LichessGame.calculateSearchTime(100, 0, 300_000));
  }

  @Test
  void importantMovesReceiveAClockSafeExtension() {
    assertEquals(666, LichessGame.calculateCriticalExtension(60_000, 1_333, 60_000));
    assertEquals(0, LichessGame.calculateCriticalExtension(10_000, 100, 60_000));
  }
}
