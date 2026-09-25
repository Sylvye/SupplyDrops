package dev.supplydrops;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

public final class Chance {
  private Chance() {}

  public static <T> String percent(T value, List<T> table, ToDoubleFunction<T> weight) {
    double total = table.stream().mapToDouble(weight).sum();
    double share = weight.applyAsDouble(value) / total * 100;
    if (!Double.isFinite(share) || share < 0) return "Invalid";
    if (share > 0 && share < .0001) return String.format(Locale.US, "%.3g%%", share);
    return new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.US)).format(share)
        + "%";
  }

  public static boolean valid(double weight) {
    return Double.isFinite(weight) && weight > 0 && weight <= 1e9;
  }
}
