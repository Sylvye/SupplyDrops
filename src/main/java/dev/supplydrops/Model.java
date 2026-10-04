package dev.supplydrops;

import java.util.*;

/** Persistence DTOs deliberately contain no live Bukkit objects. */
public final class Model {
  private Model() {}

  public static class Config {
    public int schemaVersion;
    public Map<String, Profile> profiles = new LinkedHashMap<>();
    public Map<String, BlockTable> blocks = new LinkedHashMap<>();
    public Map<String, ItemTable> items = new LinkedHashMap<>();
    public Map<String, EncounterTable> guardians = new LinkedHashMap<>();
    public int maxActivePerWorld = 1;
    public Settings settings = new Settings();
  }

  public static class Settings {
    public int radius = 1000, delaySeconds = 60, guardianGlowSeconds = 180;
    public int launchDistance = 64, barrelSize = 4, guardianActivationRadius = 32;
    public double speed = 3;
    public boolean titles = true, bossbar = true;
    public String announce =
        "<aqua><bold>Supply drop</bold></aqua> <gray>• {world} • {x}, {y}, {z} • arriving in"
            + " {eta}s</gray>";
    public String sound = "minecraft:block.note_block.bell";
  }

  public static class Profile {
    public String name = "New_profile", blockTable = "Example", guardianTable = "Example";
    public List<String> worlds = new ArrayList<>();
    public Map<String, String> containers = new LinkedHashMap<>();
    public int radius = 1000, minRolls = 20, maxRolls = 40, delaySeconds = 60, height = 80;
    public double speed = .5;
    public boolean enabled, scheduled, titles = true, bossbar = true;
    public int intervalMinutes = 60, minPlayers = 1;
    public String announce =
        "<aqua><bold>Supply drop</bold></aqua> <gray>• {world} • {x}, {y}, {z} • arriving in"
            + " {eta}s</gray>";
    public String sound = "minecraft:block.note_block.bell";
  }

  public static class BlockTable {
    public String name = "New_block_table";
    public List<BlockEntry> entries = new ArrayList<>();
  }

  public static class BlockEntry {
    public String material;
    public double weight = 1;

    public BlockEntry(String m, double w) {
      material = m;
      weight = w;
    }
  }

  public static class ItemTable {
    public String name = "New_item_table";
    public int minRolls = 3, maxRolls = 8;
    public List<ItemEntry> entries = new ArrayList<>();
  }

  public static class ItemEntry {
    public String item;
    public double weight = 1;
    public int min = 1, max = 1;

    public ItemEntry(String value) {
      item = value;
    }
  }

  public static class EncounterTable {
    public String name = "New_guardian_table";
    public List<Encounter> entries = new ArrayList<>();
  }

  public static class Encounter {
    public String name = "EMPTY";
    public double weight = 1;
    public List<MobSpec> mobs = new ArrayList<>();
  }

  public static class MobSpec {
    public String type = "HUSK", name = "";
    public int count = 1;
    public double health = 0; // Legacy input; migrated to the max_health attribute on load.
    public double radius = 24;
    public Map<String, Double> attributes = new LinkedHashMap<>();
    public boolean vanillaDrops, damageImmune;
    public Map<String, String> equipment = new LinkedHashMap<>();
    public List<Effect> effects = new ArrayList<>();
    public String dropTable = "";
  }

  public static class Effect {
    public String type = "minecraft:speed";
    public int amplifier, seconds = 3600;
  }

  public enum Stage {
    ANNOUNCED,
    DESCENDING,
    LANDING,
    GUARDED,
    UNLOCKED,
    CANCELLED
  }

  public record Pos(int x, int y, int z) {
    public Pos add(int dx, int dy, int dz) {
      return new Pos(x + dx, y + dy, z + dz);
    }
  }

  public static class Cell {
    public Pos pos;
    public String blockData;
    public List<Slot> loot = new ArrayList<>();

    public Cell(Pos p, String b) {
      pos = p;
      blockData = b;
    }
  }

  public record Slot(int slot, String item) {}

  public static class Guardian {
    public MobSpec spec;
    public String token = UUID.randomUUID().toString();
    public boolean defeated;
    public double health = -1;
    public String entityId;
  }

  public static class Drop {
    public String id = UUID.randomUUID().toString(), profileId, world;
    public Profile profile;
    public Settings settings;
    public int initialHeight;
    public long guardedAt;
    public long glowStartedAt;
    public boolean glowRevealed;
    public Stage stage = Stage.ANNOUNCED;
    public long announceAt;
    public double remainingHeight;
    public Pos origin;
    public List<Cell> cells = new ArrayList<>();
    public List<Guardian> guardians = new ArrayList<>();
    public String error = "";
    public boolean cleaned;
    public Map<String, List<Slot>> guardianLoot = new HashMap<>();
  }

  public static class RuntimeData {
    public Map<String, Drop> drops = new LinkedHashMap<>();
    public Map<String, Long> nextRuns = new HashMap<>();
    public Map<String, String> lastLayouts = new HashMap<>();
  }
}
