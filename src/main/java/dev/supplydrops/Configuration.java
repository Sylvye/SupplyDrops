package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;

public final class Configuration {
  private final SupplyDropsPlugin plugin;
  public Config current;
  public long revision;
  private boolean saving;

  /** Only global defaults migrate. Event snapshots keep their former speed. */
  static boolean migrate(Config config) {
    if (config.schemaVersion >= 1) return false;
    if (config.settings == null) config.settings = new Settings();
    if (config.settings.speed == .5) config.settings.speed = 3;
    config.schemaVersion = 1;
    return true;
  }

  public Configuration(SupplyDropsPlugin p, Config c) {
    plugin = p;
    current = c;
  }

  public void commit(Config draft, long expected, Consumer<String> done) {
    if (saving || revision != expected) {
      done.accept("Another admin saved changes. Reopen the editor.");
      return;
    }
    Config snapshot;
    try {
      Validation.config(draft);
      snapshot = Store.copy(draft, Config.class);
    } catch (Exception e) {
      if (e instanceof IllegalArgumentException && e.getMessage() != null
          && !e.getMessage().isBlank()) done.accept(e.getMessage());
      else {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not prepare editor save", e);
        done.accept("Unable to save changes. See the server log for details.");
      }
      return;
    }
    saving = true;
    plugin
        .store
        .save("config", snapshot)
        .whenComplete(
            (v, error) ->
                plugin.main(
                    () -> {
                      saving = false;
                      if (error == null) {
                        current = snapshot;
                        revision++;
                        done.accept(null);
                      } else {
                        plugin.getLogger().severe(error.toString());
                        done.accept("Storage failed; changes were not applied.");
                      }
                    }));
  }

  public static Config examples() {
    Config c = new Config();
    migrate(c);
    BlockTable b = new BlockTable();
    b.name = "Example materials";
    b.entries.add(new BlockEntry("GOLD_BLOCK", 10));
    b.entries.add(new BlockEntry("CHEST", 5));
    b.entries.add(new BlockEntry("WAXED_COPPER_CHEST", 1));
    c.blocks.put("Example", b);
    ItemTable i = new ItemTable();
    i.name = "Example treasure";
    ItemEntry diamonds = new ItemEntry(Loot.encode(new ItemStack(Material.DIAMOND)));
    diamonds.max = 3;
    i.entries.add(diamonds);
    ItemEntry bread = new ItemEntry(Loot.encode(new ItemStack(Material.BREAD)));
    bread.weight = 5;
    bread.min = 4;
    bread.max = 12;
    i.entries.add(bread);
    c.items.put("Example", i);
    EncounterTable t = new EncounterTable();
    t.name = "Example encounters";
    t.entries.add(
        encounter("Raiders", 1, "RAVAGER", 3, "EVOKER", 1, "PILLAGER", 5, "VINDICATOR", 5));
    t.entries.add(encounter("Nether patrol", 2, "PIGLIN_BRUTE", 8, "HOGLIN", 4));
    t.entries.add(encounter("Warden", 1, "WARDEN", 1));
    t.entries.add(encounter("Desert patrol", 3, "HUSK", 12, "PARCHED", 12));
    t.entries.add(encounter("EMPTY", 5));
    c.guardians.put("Example", t);
    Profile p = new Profile();
    p.name = "Example supply drop";
    p.worlds.add(Bukkit.getWorlds().getFirst().getName());
    for (String type : Loot.containers()) p.containers.put(type, "Example");
    c.profiles.put("Example", p);
    return c;
  }

  private static Encounter encounter(String name, double weight, Object... specs) {
    Encounter e = new Encounter();
    e.name = name;
    e.weight = weight;
    for (int i = 0; i < specs.length; i += 2) {
      MobSpec m = new MobSpec();
      m.type = (String) specs[i];
      m.count = (Integer) specs[i + 1];
      e.mobs.add(m);
    }
    return e;
  }
}
