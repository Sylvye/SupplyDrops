package dev.supplydrops;

import static dev.supplydrops.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianAttributesTest {
  @TempDir Path temp;

  @Test
  void legacyHealthMigratesWithExplicitOverridePrecedence() {
    MobSpec vanilla = Store.JSON.fromJson("{}", MobSpec.class);
    assertTrue(vanilla.attributes.isEmpty());
    GuardianAttributes.migrate(vanilla);
    assertTrue(vanilla.attributes.isEmpty());
    MobSpec custom = Store.JSON.fromJson("{\"health\":50.5}", MobSpec.class);
    GuardianAttributes.migrate(custom);
    assertEquals(50.5, custom.attributes.get(GuardianAttributes.MAX_HEALTH));
    assertEquals(0, custom.health);
    custom.health = 20;
    GuardianAttributes.migrate(custom);
    assertEquals(50.5, custom.attributes.get(GuardianAttributes.MAX_HEALTH));
    String migrated = Store.JSON.toJson(custom);
    GuardianAttributes.migrate(custom);
    assertEquals(migrated, Store.JSON.toJson(custom));
    MobSpec nullable = Store.JSON.fromJson("{\"attributes\":null,\"health\":0.5}", MobSpec.class);
    GuardianAttributes.migrate(nullable);
    assertEquals(.5, nullable.attributes.get(GuardianAttributes.MAX_HEALTH));
  }

  @Test
  void schemaTwoUpgradeOnlyChangesGuardianHealth() {
    Config config = new Config();
    config.schemaVersion = 2;
    config.settings.speed = .5;
    EncounterTable table = new EncounterTable();
    table.name = "Already_saved";
    Encounter encounter = new Encounter();
    MobSpec mob = new MobSpec();
    mob.health = 42;
    encounter.mobs.add(mob);
    table.entries.add(encounter);
    config.guardians.put("stable-id", table);
    assertTrue(Configuration.migrate(config));
    assertEquals(3, config.schemaVersion);
    assertEquals(.5, config.settings.speed);
    assertEquals("Already_saved", table.name);
    assertEquals(42.0, mob.attributes.get(GuardianAttributes.MAX_HEALTH));
    assertFalse(Configuration.migrate(config));
  }

  @Test
  void eventOverridesAreIsolatedAndSurviveReopen() throws Exception {
    MobSpec source = new MobSpec();
    source.attributes.put("minecraft:movement_speed", .35);
    source.attributes.put(GuardianAttributes.MAX_HEALTH, 60.5);
    Guardian guardian = new Guardian();
    guardian.spec = Store.copy(source, MobSpec.class);
    guardian.health = 12.25;
    source.attributes.put("minecraft:movement_speed", 1.0);
    Drop drop = new Drop();
    drop.guardians.add(guardian);
    Path file = temp.resolve("attributes.db");
    try (Store store = new Store(file)) { store.save("drop", drop).join(); }
    try (Store store = new Store(file)) {
      Guardian loaded = store.read("drop", Drop.class, null).guardians.getFirst();
      assertEquals(.35, loaded.spec.attributes.get("minecraft:movement_speed"));
      assertEquals(60.5, loaded.spec.attributes.get(GuardianAttributes.MAX_HEALTH));
      assertEquals(12.25, loaded.health);
    }
  }
}
