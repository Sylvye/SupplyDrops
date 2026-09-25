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
    runtime.drops.put(drop.id, drop);
    try (Store s = new Store(file)) {
      s.save("runtime", runtime).join();
      drop.remainingHeight = 2;
    }
    try (Store s = new Store(file)) {
      RuntimeData loaded = s.read("runtime", RuntimeData.class, null);
      assertEquals(37.2, loaded.drops.get(drop.id).remainingHeight);
      loaded.drops.get(drop.id).stage = Stage.UNLOCKED;
      s.save("runtime", loaded).join();
    }
    try (Store s = new Store(file)) {
      assertEquals(
          Stage.UNLOCKED, s.read("runtime", RuntimeData.class, null).drops.get(drop.id).stage);
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
    assertEquals(.5, migrated.settings.speed);
    Profile former = new Profile();
    former.radius = 80;
    former.speed = 2;
    former.delaySeconds = 13;
    Settings snapshot = Events.legacy(former);
    assertEquals(80, snapshot.radius);
    assertEquals(2, snapshot.speed);
    assertEquals(13, snapshot.delaySeconds);
  }
}
