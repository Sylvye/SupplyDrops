package dev.supplydrops;

import static dev.supplydrops.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path temp;

  @Test
  void snapshotOrderingAndReopen() throws Exception {
    Path file = temp.resolve("data.db");
    RuntimeData runtime = new RuntimeData();
    Drop drop = new Drop();
    drop.stage = Stage.DESCENDING;
    drop.remainingHeight = 37.2;
    drop.glowStartedAt = 123456789;
    drop.glowRevealed = true;
    runtime.drops.put(drop.id, drop);
    try (Store s = new Store(file)) {
      s.save("runtime", runtime).join();
      drop.remainingHeight = 2;
    }
    try (Store s = new Store(file)) {
      RuntimeData loaded = s.read("runtime", RuntimeData.class, null);
      assertEquals(37.2, loaded.drops.get(drop.id).remainingHeight);
      assertEquals(123456789, loaded.drops.get(drop.id).glowStartedAt);
      assertTrue(loaded.drops.get(drop.id).glowRevealed);
      loaded.drops.get(drop.id).stage = Stage.UNLOCKED;
      s.save("runtime", loaded).join();
    }
    try (Store s = new Store(file)) {
      assertEquals(
          Stage.UNLOCKED, s.read("runtime", RuntimeData.class, null).drops.get(drop.id).stage);
    }
  }

  @Test
  void immunityDefaultsAndEventSnapshotsSurviveReopen() throws Exception {
    assertFalse(Store.JSON.fromJson("{}", MobSpec.class).damageImmune);
    Drop legacy = Store.JSON.fromJson("{\"guardians\":[{\"spec\":{}}]}", Drop.class);
    assertFalse(legacy.guardians.getFirst().spec.damageImmune);
    MobSpec source = new MobSpec();
    source.damageImmune = true;
    Guardian on = new Guardian();
    on.spec = Store.copy(source, MobSpec.class);
    source.damageImmune = false;
    Guardian off = new Guardian();
    off.spec = Store.copy(source, MobSpec.class);
    Drop drop = new Drop();
    drop.guardians.add(on);
    drop.guardians.add(off);
    Path file = temp.resolve("immunity.db");
    try (Store store = new Store(file)) { store.save("drop", drop).join(); }
    try (Store store = new Store(file)) {
      Drop reopened = store.read("drop", Drop.class, null);
      assertTrue(reopened.guardians.getFirst().spec.damageImmune);
      assertFalse(reopened.guardians.getLast().spec.damageImmune);
      assertFalse(source.damageImmune);
    }
  }

  @Test
  void copyDoesNotShareMutableTables() {
    Config original = new Config();
    Profile p = new Profile();
    p.worlds.add("world");
    original.profiles.put("a", p);
    Config draft = Store.copy(original, Config.class);
    draft.profiles.get("a").worlds.add("nether");
    assertEquals(1, original.profiles.get("a").worlds.size());
  }

  @Test
  void oldConfigurationReceivesGlobalDefaultsAndOldEventKeepsTiming() {
    Config migrated = Store.JSON.fromJson("{\"profiles\":{}}", Config.class);
    assertEquals(1000, migrated.settings.radius);
    assertEquals(60, migrated.settings.delaySeconds);
    assertEquals(180, migrated.settings.guardianGlowSeconds);
    assertEquals(3, migrated.settings.speed);
    assertEquals(64, migrated.settings.launchDistance);
    assertEquals(4, migrated.settings.barrelSize);
    assertEquals(32, migrated.settings.guardianActivationRadius);
    Profile former = new Profile();
    former.radius = 80;
    former.speed = 2;
    former.delaySeconds = 13;
    Settings snapshot = Events.legacy(former);
    assertEquals(80, snapshot.radius);
    assertEquals(2, snapshot.speed);
    assertEquals(13, snapshot.delaySeconds);
  }

  @Test
  void speedMigrationIsOneTimeAndKeepsCustomValuesAndEventSnapshots() {
    Config config = Store.JSON.fromJson("{\"settings\":{\"speed\":0.5}}", Config.class);
    Drop active = new Drop();
    active.settings = Store.copy(config.settings, Settings.class);
    assertTrue(Configuration.migrate(config));
    assertEquals(3, config.settings.speed);
    assertEquals(.5, active.settings.speed);
    config.settings.speed = .5;
    assertFalse(Configuration.migrate(config));
    assertEquals(.5, config.settings.speed);
    Config custom = Store.JSON.fromJson("{\"settings\":{\"speed\":2}}", Config.class);
    Configuration.migrate(custom);
    assertEquals(2, custom.settings.speed);
    Drop old = Store.JSON.fromJson("{\"guardedAt\":1}", Drop.class);
    assertEquals(0, old.glowStartedAt);
    assertFalse(old.glowRevealed);
  }
}
