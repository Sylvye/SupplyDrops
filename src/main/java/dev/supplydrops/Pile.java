package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;
import java.util.random.RandomGenerator;

/** Pure layout generator. Every cell has solid support and face connectivity. */
public final class Pile {
  private Pile() {}

  public record Block(Pos pos, String material) {}

  public static List<Block> generate(List<String> materials, RandomGenerator rng) {
    List<String> sorted = new ArrayList<>(materials);
    Collections.shuffle(sorted, new Random(rng.nextLong()));
    // Closed columns are placed last so they never become support for another roll.
    sorted.sort(Comparator.comparing(Loot::needsClearTop));
    Map<Pos, String> placed = new LinkedHashMap<>();
    for (String material : sorted) {
      List<Pos> candidates = new ArrayList<>();
      if (placed.isEmpty()) candidates.add(new Pos(0, 0, 0));
      else {
        Set<Pos> frontier = new HashSet<>();
        for (Pos p : placed.keySet()) {
          frontier.add(p.add(1, 0, 0));
          frontier.add(p.add(-1, 0, 0));
          frontier.add(p.add(0, 0, 1));
          frontier.add(p.add(0, 0, -1));
          frontier.add(p.add(0, 1, 0));
        }
        for (Pos p : frontier) {
          if (placed.containsKey(p) || p.y() > 5) continue;
          if (p.y() > 0 && !placed.containsKey(p.add(0, -1, 0))) continue;
          boolean closed =
              placed.entrySet().stream()
                  .anyMatch(
                      e ->
                          e.getKey().x() == p.x()
                              && e.getKey().z() == p.z()
                              && e.getKey().y() < p.y()
                              && Loot.needsClearTop(e.getValue()));
          if (!closed) candidates.add(p);
        }
      }
      if (candidates.isEmpty()) throw new IllegalStateException("No supported position");
      candidates.sort(Comparator.comparingInt(p -> Math.abs(p.x()) + Math.abs(p.z()) + p.y()));
      int window = Math.min(candidates.size(), Math.max(3, (int) Math.sqrt(materials.size()) * 2));
      Pos chosen = candidates.get(rng.nextInt(window));
      placed.put(chosen, material);
    }
    return placed.entrySet().stream().map(e -> new Block(e.getKey(), e.getValue())).toList();
  }

  public static String signature(List<Block> blocks) {
    return blocks.stream()
        .sorted(Comparator.comparing(b -> b.pos().toString()))
        .map(b -> b.pos() + ":" + b.material())
        .reduce("", (a, b) -> a + ";" + b);
  }
}
