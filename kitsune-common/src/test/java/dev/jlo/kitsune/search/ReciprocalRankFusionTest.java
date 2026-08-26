package dev.jlo.kitsune.search;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
  @Test
  void displayScore() {
    assertEquals(1.0, ReciprocalRankFusion.displayScore(60, 1, 1), 1e-9);
    assertEquals(0.5, ReciprocalRankFusion.displayScore(60, 1, null), 1e-9);
    assertEquals(0.5, ReciprocalRankFusion.displayScore(60, null, 1), 1e-9);
    assertTrue(ReciprocalRankFusion.displayScore(60, 1, 2)
        > ReciprocalRankFusion.displayScore(60, 1, null));
    assertTrue(ReciprocalRankFusion.displayScore(60, 1, 1) <= 1.0);
    assertThrows(IllegalArgumentException.class,
        () -> ReciprocalRankFusion.displayScore(0, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> ReciprocalRankFusion.displayScore(60, 0, 1));
    assertEquals(0.0, ReciprocalRankFusion.displayScore(60, null, null), 1e-9);
  }
}
