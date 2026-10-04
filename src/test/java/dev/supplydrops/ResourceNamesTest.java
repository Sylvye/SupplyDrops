package dev.supplydrops;

import static dev.supplydrops.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceNamesTest {
  @Test
  void namesAreSafeCommandArguments() {
    for (String valid : List.of("Desert_Raid", "Raid-2", "a".repeat(48)))
      assertDoesNotThrow(() -> ResourceNames.validate(valid));
    for (String invalid : List.of("", "Desert Raid", "a.b", "<red>Raid", "Raid\n", "é", "a".repeat(49)))
      assertThrows(IllegalArgumentException.class, () -> ResourceNames.validate(invalid));
    assertThrows(IllegalArgumentException.class, () -> ResourceNames.validate(null));
  }

  @Test
  void uniquenessIsCaseInsensitiveAndRenameExcludesItself() {
    Map<String, Profile> profiles = new LinkedHashMap<>();
    Profile profile = new Profile();
    profile.name = "Desert_Raid";
    profiles.put("id", profile);
    assertDoesNotThrow(() -> ResourceNames.available(profiles, "id", "desert_raid"));
    assertThrows(IllegalArgumentException.class, () -> ResourceNames.available(profiles, null, "desert_raid"));
    Profile duplicate = new Profile();
    duplicate.name = "DESERT_RAID";
    profiles.put("other", duplicate);
    assertThrows(IllegalArgumentException.class, () -> Validation.config(config(profiles)));
    assertThrows(IllegalArgumentException.class, () -> ResourceNames.resolveProfile(profiles, "desert_raid"));
  }

  @Test
  void migrationReservesValidNamesAndKeepsReferences() {
    Config config = new Config();
    config.schemaVersion = 1;
    config.settings.speed = .5;
    Map<String, String> before = Map.of("a", "Raid!", "z", "Raid_", "b", "raid_",
        "c", "", "d", "a".repeat(49), "e", "a".repeat(48));
    before.forEach((id, name) -> {
      Profile p = new Profile();
      p.name = name;
      p.blockTable = "block-id";
      p.guardianTable = "guardian-id";
      p.containers.put("CHEST", "item-id");
      config.profiles.put(id, p);
    });
    BlockTable blocks = new BlockTable();
    blocks.name = "Block table";
    config.blocks.put("block-id", blocks);
    ItemTable items = new ItemTable();
    items.name = "Item table";
    config.items.put("item-id", items);
    EncounterTable guardians = new EncounterTable();
    guardians.name = "Guardian table";
    Encounter encounter = new Encounter();
    encounter.name = "Desert patrol";
    MobSpec mob = new MobSpec();
    mob.name = "<red>Custom mob";
    mob.dropTable = "item-id";
    encounter.mobs.add(mob);
    guardians.entries.add(encounter);
    config.guardians.put("guardian-id", guardians);
    assertTrue(Configuration.migrate(config));
    assertEquals(.5, config.settings.speed);
    assertEquals(3, config.schemaVersion);
    assertEquals("raid_", config.profiles.get("b").name);
    assertEquals("Raid__2", config.profiles.get("a").name);
    assertEquals("Raid__3", config.profiles.get("z").name);
    assertEquals("Resource", config.profiles.get("c").name);
    assertEquals(48, config.profiles.get("d").name.length());
    assertTrue(config.profiles.get("d").name.endsWith("_2"));
    ResourceNames.validateAll(config.profiles);
    assertEquals("Block_table", ResourceNames.display(config.blocks, "block-id"));
    assertEquals("Desert_patrol", encounter.name);
    assertEquals("<red>Custom mob", mob.name);
    assertEquals("item-id", mob.dropTable);
    config.profiles.values().forEach(p -> {
      assertEquals("block-id", p.blockTable);
      assertEquals("guardian-id", p.guardianTable);
      assertEquals("item-id", p.containers.get("CHEST"));
    });
    String migrated = Store.JSON.toJson(config);
    assertFalse(Configuration.migrate(config));
    assertEquals(migrated, Store.JSON.toJson(config));
    assertEquals("Missing table", ResourceNames.display(config.items, "missing"));
    assertEquals("Unassigned", ResourceNames.display(config.items, ""));
  }

  @Test
  void profileNamesTakePrecedenceOverLegacyIds() {
    Map<String, Profile> profiles = new LinkedHashMap<>();
    Profile first = new Profile();
    first.name = "Desert_Raid";
    profiles.put("f78e1f19", first);
    assertEquals("f78e1f19", ResourceNames.resolveProfile(profiles, "DESERT_RAID"));
    assertEquals("f78e1f19", ResourceNames.resolveProfile(profiles, "F78E1F19"));
    Profile second = new Profile();
    second.name = "f78e1f19";
    profiles.put("other-id", second);
    assertEquals("other-id", ResourceNames.resolveProfile(profiles, "f78e1f19"));
    assertThrows(IllegalArgumentException.class, () -> ResourceNames.resolveProfile(profiles, "missing"));
  }

  private Config config(Map<String, Profile> profiles) {
    Config config = new Config();
    config.profiles = profiles;
    return config;
  }
}
