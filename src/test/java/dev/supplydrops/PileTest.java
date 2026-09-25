package dev.supplydrops;

import static dev.supplydrops.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PileTest {
  @Test
  void manySeedsPreserveSupportConnectivityAndClearance() {
    for (int seed = 0; seed < 200; seed++) {
      List<String> rolls = new ArrayList<>();
      for (int i = 0; i < 64; i++)
        rolls.add(
            List.of("GOLD_BLOCK", "CHEST", "WAXED_COPPER_CHEST", "PURPLE_SHULKER_BOX", "BARREL")
                .get(i % 5));
      List<Pile.Block> pile = Pile.generate(rolls, new Random(seed));
      assertEquals(64, pile.size());
      Set<Pos> positions = new HashSet<>();
      for (var b : pile) assertTrue(positions.add(b.pos()));
      Set<Pos> visited = new HashSet<>();
      ArrayDeque<Pos> frontier = new ArrayDeque<>();
      frontier.add(pile.getFirst().pos());
      while (!frontier.isEmpty()) {
        Pos p = frontier.remove();
        if (!visited.add(p)) continue;
        for (Pos n :
            List.of(
                p.add(1, 0, 0),
                p.add(-1, 0, 0),
                p.add(0, 1, 0),
                p.add(0, -1, 0),
                p.add(0, 0, 1),
                p.add(0, 0, -1)))
          if (positions.contains(n) && !visited.contains(n)) frontier.add(n);
      }
      assertEquals(positions, visited);
      for (var b : pile) {
        if (b.pos().y() > 0) assertTrue(positions.contains(b.pos().add(0, -1, 0)));
        if (Loot.needsClearTop(b.material()))
          assertFalse(
              positions.stream()
                  .anyMatch(
                      p -> p.x() == b.pos().x() && p.z() == b.pos().z() && p.y() > b.pos().y()));
      }
    }
  }

  @Test
  void randomnessAndNormalization() {
    var materials = Collections.nCopies(40, "GOLD_BLOCK");
    assertNotEquals(
        Pile.signature(Pile.generate(materials, new Random(1))),
        Pile.signature(Pile.generate(materials, new Random(2))));
    assertEquals("WAXED_COPPER_CHEST", Loot.normalize("copper_chest"));
    assertEquals("WAXED_OXIDIZED_COPPER_CHEST", Loot.normalize("minecraft:oxidized_copper_chest"));
    assertTrue(Loot.container("SHULKER_BOX"));
    assertFalse(Loot.container("GOLD_BLOCK"));
  }
}
