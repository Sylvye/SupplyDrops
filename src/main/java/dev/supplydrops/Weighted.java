package dev.supplydrops;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

public final class Weighted {
  private Weighted() {}

  public static <T> T pick(List<T> values, ToDoubleFunction<T> weight, RandomGenerator random) {
    double total = 0;
    for (T value : values) {
      double w = weight.applyAsDouble(value);
      if (!Double.isFinite(w) || w <= 0)
        throw new IllegalArgumentException("Weights must be positive and finite");
      total += w;
    }
    if (values.isEmpty() || !Double.isFinite(total))
      throw new IllegalArgumentException("A weighted table must contain entries");
    double roll = random.nextDouble(total);
    for (T value : values) {
      roll -= weight.applyAsDouble(value);
      if (roll < 0) return value;
    }
    return values.getLast();
  }

  public static int between(int min, int max, RandomGenerator random) {
    if (min < 0 || max < min || max == Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid range");
    return random.nextInt(min, max + 1);
  }

  public static boolean due(long now, long next, int online, int minimum, boolean enabled) {
    return enabled && now >= next && online >= minimum;
  }
}
