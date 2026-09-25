package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;

public final class Loot {
  private Loot() {}

  public static String encode(ItemStack item) {
    return Base64.getEncoder().encodeToString(item.serializeAsBytes());
  }

  public static ItemStack decode(String item) {
    return ItemStack.deserializeBytes(Base64.getDecoder().decode(item));
  }

  public static String normalize(String material) {
    String m = material.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
    if (m.endsWith("COPPER_CHEST") && !m.startsWith("WAXED_")) m = "WAXED_" + m;
    return m;
  }

  public static boolean container(String m) {
    return m.equals("CHEST")
        || m.equals("BARREL")
        || m.endsWith("COPPER_CHEST")
        || m.endsWith("SHULKER_BOX");
  }

  public static boolean needsClearTop(String m) {
    return m.equals("CHEST") || m.endsWith("COPPER_CHEST") || m.endsWith("SHULKER_BOX");
  }

  public static List<String> containers() {
    return Arrays.stream(Material.values())
        .filter(Material::isBlock)
        .map(Enum::name)
        .filter(Loot::container)
        .filter(m -> !m.endsWith("COPPER_CHEST") || m.startsWith("WAXED_"))
        .sorted()
        .toList();
  }

  public static int maxSlots(ItemTable table) {
    int max = 0;
    for (ItemEntry e : table.entries) {
      int stack = decode(e.item).getMaxStackSize();
      max = Math.max(max, (e.max + stack - 1) / stack);
    }
    return Math.multiplyExact(table.maxRolls, max);
  }

  public static List<Slot> roll(ItemTable table, RandomGenerator rng) {
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < 27; i++) slots.add(i);
    Collections.shuffle(slots, new Random(rng.nextLong()));
    List<Slot> result = new ArrayList<>();
    int count = Weighted.between(table.minRolls, table.maxRolls, rng);
    for (int i = 0; i < count; i++) {
      ItemEntry entry = Weighted.pick(table.entries, e -> e.weight, rng);
      ItemStack template = decode(entry.item);
      int amount = Weighted.between(entry.min, entry.max, rng);
      while (amount > 0) {
        ItemStack stack = template.clone();
        stack.setAmount(Math.min(amount, stack.getMaxStackSize()));
        amount -= stack.getAmount();
        if (result.size() >= 27)
          throw new IllegalArgumentException("Item table exceeds container capacity");
        result.add(new Slot(slots.get(result.size()), encode(stack)));
      }
    }
    return result;
  }
}
