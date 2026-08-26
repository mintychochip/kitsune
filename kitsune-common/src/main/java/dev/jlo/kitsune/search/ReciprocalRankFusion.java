package dev.jlo.kitsune.search;

public final class ReciprocalRankFusion {
  private ReciprocalRankFusion() {}

  public static double displayScore(int k, Integer fullTextRank, Integer semanticRank) {
    if (k < 1) {
      throw new IllegalArgumentException("RRF k must be at least 1");
    }
    double rrf = 0.0;
    rrf += contribution(k, fullTextRank);
    rrf += contribution(k, semanticRank);
    return Math.min(1.0, rrf * ((double) k + 1.0) / 2.0);
  }

  private static double contribution(int k, Integer rank) {
    if (rank == null) {
      return 0.0;
    }
    if (rank < 1) {
      throw new IllegalArgumentException("Rank must be at least 1");
    }
    return 1.0 / ((double) k + rank);
  }
}
