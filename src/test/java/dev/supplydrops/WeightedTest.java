package dev.supplydrops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WeightedTest {
  @Test
  void weightedDistributionAndBounds() {
    Random rng = new Random(87);
    int rare = 0;
    for (int i = 0; i < 100000; i++)
      if (Weighted.pick(List.of(1, 9), Integer::doubleValue, rng) == 1) rare++;
    assertTrue(rare > 9500 && rare < 10500, "Actual rare count " + rare);
    for (int i = 0; i < 1000; i++) {
      int n = Weighted.between(3, 7, rng);
      assertTrue(n >= 3 && n <= 7);
    }
    assertEquals(5, Weighted.between(5, 5, rng));
  }

  @Test
  void rejectsInvalidTables() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Weighted.pick(List.<Integer>of(), Integer::doubleValue, new Random()));
    for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () -> Weighted.pick(List.of(bad), Double::doubleValue, new Random()));
    assertThrows(IllegalArgumentException.class, () -> Weighted.between(9, 2, new Random()));
  }

  @Test
  void scheduleRequiresAllConditions() {
    assertTrue(Weighted.due(100, 99, 1, 1, true));
    assertFalse(Weighted.due(98, 99, 1, 1, true));
    assertFalse(Weighted.due(100, 99, 0, 1, true));
    assertFalse(Weighted.due(100, 99, 1, 1, false));
  }
}
