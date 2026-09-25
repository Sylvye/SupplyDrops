package dev.supplydrops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ChanceTest {
  record Entry(double weight) {}

  @Test
  void exactAndTinyPercentagesUseWholeTable() {
    var values = List.of(new Entry(10), new Entry(5), new Entry(1));
    assertEquals("62.5%", Chance.percent(values.get(0), values, Entry::weight));
    assertEquals("31.25%", Chance.percent(values.get(1), values, Entry::weight));
    assertEquals("6.25%", Chance.percent(values.get(2), values, Entry::weight));
    var tiny = List.of(new Entry(1), new Entry(1e9));
    assertEquals("1.00e-07%", Chance.percent(tiny.getFirst(), tiny, Entry::weight));
  }

  @Test
  void thresholdIsTimeOrAtMostQuarterOfOriginal() {
    assertFalse(Events.shouldGlow(179999, 0, 8, 3, 180));
    assertTrue(Events.shouldGlow(179999, 0, 8, 2, 180));
    assertTrue(Events.shouldGlow(180000, 0, 8, 8, 180));
    assertFalse(Events.shouldGlow(180000, 0, 0, 0, 180));
    assertFalse(Events.shouldGlow(180000, 0, 3, 0, 180));
  }
}
