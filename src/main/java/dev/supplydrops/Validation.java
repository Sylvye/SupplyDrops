package dev.supplydrops;

import static dev.supplydrops.Model.*;

import dev.supplydrops.Model.Effect;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;

public final class Validation {
  private Validation() {}

  public static void require(boolean b, String message) {
    if (!b) throw new IllegalArgumentException(message);
  }

  public static void range(int value, int min, int max, String label) {
    require(value >= min && value <= max, label + " must be " + min + "–" + max);
  }

  public static void weight(double value) {
    require(
        Double.isFinite(value) && value > 0 && value <= 1e9,
        "Weight must be positive and at most 1 billion");
  }

  public static void itemTable(ItemTable t) {
    range(t.minRolls, 0, 27, "Minimum rolls");
    range(t.maxRolls, t.minRolls, 27, "Maximum rolls");
    require(t.maxRolls == 0 || !t.entries.isEmpty(), "Item table needs an entry");
    for (ItemEntry e : t.entries) {
      weight(e.weight);
      range(e.min, 1, 1728, "Minimum amount");
      range(e.max, e.min, 1728, "Maximum amount");
      require(!Loot.decode(e.item).getType().isAir(), "Air is not loot");
    }
    require(
        Loot.maxSlots(t) <= 27, "Worst-case contents exceed 27 slots; lower quantities or rolls");
  }

  public static void blockTable(BlockTable t) {
    require(!t.entries.isEmpty(), "Block table needs an entry");
    for (BlockEntry e : t.entries) {
      weight(e.weight);
      e.material = Loot.normalize(e.material);
      Material m = Material.matchMaterial(e.material);
      require(
          m != null && m.isBlock() && m.isSolid() && !m.hasGravity(),
          "Choose a solid, non-gravity block: " + e.material);
      require(
          m != Material.BEDROCK && m != Material.BARRIER && m != Material.END_PORTAL_FRAME,
          "Unmineable block is not allowed");
      if (m != null) {
        var data = m.createBlockData();
        require(
            !(data instanceof org.bukkit.block.data.Bisected)
                && !(data instanceof org.bukkit.block.data.type.Bed),
            "Multi-block materials are unsupported");
        require(
            !(data.createBlockState() instanceof org.bukkit.block.TileState)
                || Loot.container(e.material),
            "Choose a supported container or a plain material block");
        require(m.getHardness() >= 0, "Unmineable blocks are unsupported");
        require(
            !m.name().contains("COMMAND_BLOCK")
                && !m.name().equals("STRUCTURE_BLOCK")
                && !m.name().equals("JIGSAW"),
            "Administrative blocks are unsupported");
      }
    }
  }

  public static void encounters(EncounterTable t, Config config) {
    require(!t.entries.isEmpty(), "Guardian table needs an encounter (EMPTY is allowed)");
    for (Encounter e : t.entries) {
      weight(e.weight);
      int total = 0;
      for (MobSpec m : e.mobs) {
        EntityType type = EntityType.valueOf(m.type);
        require(
            type.isSpawnable()
                && type.getEntityClass() != null
                && Mob.class.isAssignableFrom(type.getEntityClass()),
            "Choose a living mob");
        require(
            type != EntityType.ENDER_DRAGON && type != EntityType.WITHER,
            "Destructive bosses are unsupported");
        range(m.count, 1, 64, "Mob count");
        total += m.count;
        require(
            Double.isFinite(m.health) && m.health >= 0 && m.health <= 1024,
            "Health must be 0 (vanilla) to 1024");
        require(
            Double.isFinite(m.radius) && m.radius >= 4 && m.radius <= 128, "Radius must be 4–128");
        for (var eq : m.equipment.entrySet()) {
          EquipmentSlot.valueOf(eq.getKey());
          Loot.decode(eq.getValue());
        }
        for (Effect f : m.effects) {
          require(Registry.EFFECT.get(NamespacedKey.fromString(f.type)) != null, "Unknown effect");
          range(f.amplifier, 0, 10, "Effect amplifier");
          range(f.seconds, 1, 86400, "Effect duration");
        }
        if (!m.dropTable.isBlank()) {
          require(config.items.containsKey(m.dropTable), "Missing guardian drop table");
          itemTable(config.items.get(m.dropTable));
        }
      }
      require(total <= 128, "At most 128 guardians per encounter");
    }
  }

  public static void profile(Profile p, Config c) {
    range(p.minRolls, 1, 256, "Minimum blocks");
    range(p.maxRolls, p.minRolls, 256, "Maximum blocks");
    range(p.intervalMinutes, 1, 10080, "Interval minutes");
    range(p.minPlayers, 0, 10000, "Minimum players");
    require(!p.worlds.isEmpty(), "Choose at least one world");
    for (String w : p.worlds) require(Bukkit.getWorld(w) != null, "World is unavailable: " + w);
    require(c.blocks.containsKey(p.blockTable), "Choose a block table");
    blockTable(c.blocks.get(p.blockTable));
    require(c.guardians.containsKey(p.guardianTable), "Choose a guardian table");
    encounters(c.guardians.get(p.guardianTable), c);
    for (BlockEntry e : c.blocks.get(p.blockTable).entries)
      if (Loot.container(e.material)) {
        String table = p.containers.get(e.material);
        require(
            table != null && c.items.containsKey(table), "Assign an item table to " + e.material);
        itemTable(c.items.get(table));
      }
  }

  public static void config(Config c) {
    range(c.maxActivePerWorld, 1, 10, "Concurrent events");
    require(c.settings != null, "Global settings are missing");
    Settings settings = c.settings;
    range(settings.radius, 1, 100000, "Radius");
    range(settings.delaySeconds, 0, 3600, "Announcement delay");
    range(settings.guardianGlowSeconds, 0, 86400, "Guardian glow delay");
    require(
        Double.isFinite(settings.speed) && settings.speed >= .05 && settings.speed <= 20,
        "Speed must be 0.05–20 blocks/second");
    require(
        settings.announce != null && settings.announce.length() <= 1000,
        "Announcement is too long");
    require(
        settings.sound != null
            && (settings.sound.isBlank()
                || Registry.SOUNDS.get(NamespacedKey.fromString(settings.sound)) != null),
        "Unknown sound key");
    for (Profile p : c.profiles.values()) if (p.enabled) profile(p, c);
  }
}
