package dev.supplydrops;

import static dev.supplydrops.Model.*;

import dev.supplydrops.Model.Effect;
import dev.supplydrops.Model.Guardian;
import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

public final class Events {
  private final SupplyDropsPlugin plugin;
  public final RuntimeData data;
  public final NamespacedKey eventKey, guardianKey;
  private final RandomGenerator rng = new Random();
  private final Map<String, BarrelFlight> barrels = new HashMap<>();
  private final Map<String, BossBar> bars = new HashMap<>();
  private final Set<String> busy = new HashSet<>(), searching = new HashSet<>();
  private final Map<String, Set<Chunk>> tickets = new HashMap<>();
  private boolean failed;

  static boolean shouldGlow(long now, long startedAt, int total, int remaining, int delaySeconds) {
    return total > 0
        && remaining > 0
        && ((startedAt > 0 && now - startedAt >= delaySeconds * 1000L) || remaining * 4 <= total);
  }

  static boolean eligibleForGlow(GameMode mode, double distanceSquared, double radius) {
    return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE)
        && distanceSquared <= radius * radius;
  }

  boolean observePlayers(Drop d, Collection<? extends Player> players, long now) {
    if (d.stage != Stage.GUARDED
        || d.glowStartedAt != 0
        || d.glowRevealed
        || d.guardians.stream().allMatch(g -> g.defeated)) return false;
    for (Player player : players) {
      if (!player.getWorld().getName().equals(d.world)) continue;
      Location at = player.getLocation();
      double dx = at.getX() - (d.origin.x() + .5);
      double dy = at.getY() - d.origin.y();
      double dz = at.getZ() - (d.origin.z() + .5);
      if (eligibleForGlow(
          player.getGameMode(),
          dx * dx + dy * dy + dz * dz,
          settings(d).guardianActivationRadius)) {
        d.glowStartedAt = now;
        return true;
      }
    }
    return false;
  }

  static Settings legacy(Profile p) {
    Settings settings = new Settings();
    if (p != null) {
      settings.radius = p.radius;
      settings.speed = p.speed;
      settings.delaySeconds = p.delaySeconds;
      settings.announce = p.announce;
      settings.sound = p.sound;
      settings.titles = p.titles;
      settings.bossbar = p.bossbar;
    }
    return settings;
  }

  private Settings settings(Drop d) {
    return d.settings == null ? legacy(d.profile) : d.settings;
  }

  private int initialHeight(Drop d) {
    return d.initialHeight > 0 ? d.initialHeight : d.profile.height;
  }

  public Events(SupplyDropsPlugin plugin, RuntimeData data) {
    this.plugin = plugin;
    this.data = data;
    eventKey = new NamespacedKey(plugin, "event");
    guardianKey = new NamespacedKey(plugin, "guardian");
  }

  public boolean active(Drop d) {
    return d.stage != Stage.UNLOCKED && d.stage != Stage.CANCELLED;
  }

  public Collection<Drop> active() {
    return data.drops.values().stream().filter(this::active).toList();
  }

  public Drop find(String id) {
    List<Drop> matches = active().stream().filter(d -> d.id.startsWith(id)).toList();
    if (matches.size() != 1)
      throw new IllegalArgumentException("Use an unambiguous active event ID");
    return matches.getFirst();
  }

  private void checkpoint(Drop d, Runnable after) {
    busy.add(d.id);
    plugin
        .store
        .save("runtime", data)
        .whenComplete(
            (v, error) ->
                plugin.main(
                    () -> {
                      if (error != null) {
                        failed = true;
                        plugin.getLogger().severe("Events paused: checkpoint failed: " + error);
                        return;
                      }
                      busy.remove(d.id);
                      if (plugin.isEnabled()) after.run();
                    }));
  }

  public void recover() {
    for (Drop d : data.drops.values()) {
      if (d.settings == null) d.settings = legacy(d.profile);
      if (d.initialHeight <= 0) d.initialHeight = initialHeight(d);
      World w = Bukkit.getWorld(d.world);
      if (w == null) continue;
      if (active(d)) {
        hold(d, w);
        // Chunk loading makes persistent entities available before identity reconciliation.
        for (Chunk c : tickets.getOrDefault(d.id, Set.of())) c.getEntities();
        for (Guardian guardian : d.guardians) {
          if (guardian.defeated) continue;
          for (LivingEntity mob : w.getLivingEntities())
            if (guardian.token.equals(
                mob.getPersistentDataContainer().get(guardianKey, PersistentDataType.STRING))) {
              if (mob.isGlowing()) d.glowRevealed = true;
              var maximum = mob.getAttribute(Attribute.MAX_HEALTH);
              if (maximum != null && guardian.health > 0)
                mob.setHealth(Math.min(guardian.health, maximum.getValue()));
            }
        }
        removeVisual(d, w);
        if (d.stage == Stage.DESCENDING || d.stage == Stage.LANDING) spawnBarrel(d, w);
      } else if (d.stage == Stage.CANCELLED) cleanCancelled(d, w);
    }
    for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) reconcileEntity(e);
    plugin.store.save("runtime", data).join();
  }

  public void reconcileEntity(Entity e) {
    String id = e.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING);
    if (id == null) return;
    Drop d = data.drops.get(id);
    if (d == null || !active(d)) {
      e.remove();
      return;
    }
    Guardian guardian = guardian(e);
    if (guardian != null && guardian.defeated) {
      e.remove();
      return;
    }
    if (guardian != null && !guardian.defeated && e.isGlowing()) d.glowRevealed = true;
    if (e instanceof FallingBlock) e.remove();
    if (e instanceof BlockDisplay display
        && (barrels.get(id) == null || barrels.get(id).display != display)) e.remove();
  }

  private void hold(Drop d, World w) {
    if (tickets.containsKey(d.id)) return;
    Set<Chunk> chunks = new HashSet<>();
    int radius =
        (int) Math.ceil(d.guardians.stream().mapToDouble(g -> g.spec.radius).max().orElse(4)) + 16;
    for (int x = (d.origin.x() - radius) >> 4; x <= (d.origin.x() + radius) >> 4; x++)
      for (int z = (d.origin.z() - radius) >> 4; z <= (d.origin.z() + radius) >> 4; z++) {
        Chunk c = w.getChunkAt(x, z);
        c.addPluginChunkTicket(plugin);
        chunks.add(c);
      }
    tickets.put(d.id, chunks);
  }

  private void release(Drop d) {
    Set<Chunk> chunks = tickets.remove(d.id);
    if (chunks != null)
      for (Chunk c : chunks)
        if (tickets.values().stream().noneMatch(s -> s.contains(c)))
          c.removePluginChunkTicket(plugin);
    BossBar b = bars.remove(d.id);
    if (b != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(b));
  }

  public void spawn(String profileId, Location explicit, Consumer<String> reply) {
    if (failed) {
      reply.accept("Storage failure: events paused. Check console.");
      return;
    }
    Config c = plugin.config.current;
    Profile p = c.profiles.get(profileId);
    try {
      Validation.require(p != null, "Unknown profile");
      Validation.profile(p, c);
      Validation.require(p.enabled, "Enable this profile first");
    } catch (Exception e) {
      reply.accept(e.getMessage());
      return;
    }
    World w =
        explicit == null
            ? Bukkit.getWorld(p.worlds.get(rng.nextInt(p.worlds.size())))
            : explicit.getWorld();
    if (w == null || !p.worlds.contains(w.getName())) {
      reply.accept("World is not enabled for this profile");
      return;
    }
    if (searching.contains(w.getName())
        || active().stream().filter(d -> d.world.equals(w.getName())).count()
            >= c.maxActivePerWorld) {
      reply.accept("World already has its maximum active events");
      return;
    }
    Drop d = new Drop();
    d.profileId = profileId;
    d.profile = Store.copy(p, Profile.class);
    d.settings = Store.copy(c.settings, Settings.class);
    d.world = w.getName();
    d.cells = Placement.cells(c, p, rng, data.lastLayouts.get(profileId));
    Encounter encounter =
        Weighted.pick(c.guardians.get(p.guardianTable).entries, e -> e.weight, rng);
    for (MobSpec spec : encounter.mobs)
      for (int n = 0; n < spec.count; n++) {
        Guardian g = new Guardian();
        g.spec = Store.copy(spec, MobSpec.class);
        // Snapshot guardian drops along with the event, independent of later table edits.
        d.guardians.add(g);
      }
    d.guardianLoot = new HashMap<>();
    for (Guardian g : d.guardians)
      if (!g.spec.dropTable.isBlank())
        d.guardianLoot.put(g.token, Loot.roll(c.items.get(g.spec.dropTable), rng));
    searching.add(w.getName());
    search(d, w, explicit, 0, new LinkedHashMap<>(), reply);
  }

  private void search(
      Drop d,
      World w,
      Location explicit,
      int attempt,
      Map<String, Integer> reasons,
      Consumer<String> reply) {
    if (!plugin.isEnabled()) {
      searching.remove(w.getName());
      return;
    }
    long deadline = System.nanoTime() + 4_000_000L;
    for (int n = 0; n < 8 && attempt < 100; n++, attempt++) {
      Pos origin =
          explicit == null
              ? Placement.random(w, d.settings.radius, rng)
              : new Pos(explicit.getBlockX(), explicit.getBlockY(), explicit.getBlockZ());
      String error =
          origin == null
              ? "Outside world border"
              : Math.hypot(origin.x(), origin.z()) > d.settings.radius
                  ? "Outside global radius"
                  : null;
      Placement.Site site = error == null ? Placement.site(w, origin, d.cells, true) : null;
      if (error == null) error = site.error();
      if (error == null
          && active().stream()
              .anyMatch(
                  other ->
                      other.world.equals(d.world)
                          && Math.abs(other.origin.x() - origin.x()) < 64
                          && Math.abs(other.origin.z() - origin.z()) < 64))
        error = "Too near another event";
      if (error == null) {
        d.origin = origin;
        d.cells = site.cells();
        d.initialHeight = d.settings.launchDistance;
        d.remainingHeight = d.initialHeight;
        d.announceAt = System.currentTimeMillis();
        data.drops.put(d.id, d);
        data.lastLayouts.put(d.profileId, Placement.signature(d.cells));
        hold(d, w);
        checkpoint(
            d,
            () -> {
              searching.remove(w.getName());
              announce(d);
              reply.accept("Event " + shortId(d) + " announced at " + coordinates(d));
            });
        return;
      }
      reasons.merge(error, 1, Integer::sum);
      if (explicit != null) {
        searching.remove(w.getName());
        reply.accept("No safe landing site: " + error);
        return;
      }
      if (System.nanoTime() >= deadline) {
        attempt++;
        break;
      }
    }
    if (attempt >= 100) {
      searching.remove(w.getName());
      String top =
          reasons.entrySet().stream()
              .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
              .limit(3)
              .map(e -> e.getKey() + " (" + e.getValue() + ")")
              .reduce((a, b) -> a + ", " + b)
              .orElse("No candidates");
      reply.accept("No safe landing site after 100 attempts: " + top);
      return;
    }
    int next = attempt;
    Bukkit.getScheduler().runTaskLater(plugin, () -> search(d, w, null, next, reasons, reply), 1);
  }

  public static String shortId(Drop d) {
    return d.id.substring(0, 8);
  }

  public static String coordinates(Drop d) {
    return d.world + " " + d.origin.x() + ", " + d.origin.y() + ", " + d.origin.z();
  }

  private void announce(Drop d) {
    String msg =
        settings(d)
            .announce
            .replace("{world}", d.world)
            .replace("{x}", "" + d.origin.x())
            .replace("{y}", "" + d.origin.y())
            .replace("{z}", "" + d.origin.z())
            .replace(
                "{eta}",
                ""
                    + (settings(d).delaySeconds
                        + BarrelFlight.eta(initialHeight(d), settings(d).speed)));
    Component text = MiniMessage.miniMessage().deserialize(msg);
    for (Player p : Bukkit.getOnlinePlayers()) {
      p.sendMessage(text);
      if (settings(d).titles)
        p.showTitle(
            Title.title(Component.text("Supply drop incoming"), Component.text(coordinates(d))));
      if (!settings(d).sound.isBlank()) p.playSound(p.getLocation(), settings(d).sound, 1, 1);
    }
  }

  public void tick() {
    if (failed) return;
    long now = System.currentTimeMillis();
    for (Drop d : new ArrayList<>(active())) {
      if (busy.contains(d.id)) continue;
      World w = Bukkit.getWorld(d.world);
      if (w == null) {
        d.error = "World unavailable";
        continue;
      }
      hold(d, w);
      if (!d.error.isBlank()) {
        if (d.stage == Stage.LANDING
            && (barrels.get(d.id) == null || !barrels.get(d.id).display.isValid()))
          spawnBarrel(d, w);
        continue;
      }
      try {
        switch (d.stage) {
          case ANNOUNCED -> {
            if (now - d.announceAt >= settings(d).delaySeconds * 1000L) {
              d.stage = Stage.DESCENDING;
              checkpoint(d, () -> spawnBarrel(d, w));
            }
          }
          case DESCENDING -> {
            BarrelFlight flight = barrels.get(d.id);
            if (flight == null || !flight.display.isValid()) {
              spawnBarrel(d, w);
              flight = barrels.get(d.id);
            }
            if (flight.advance(d)) {
              d.stage = Stage.LANDING;
              checkpoint(d, () -> land(d, w));
            }
          }
          case LANDING -> land(d, w);
          case GUARDED -> reconcileGuardians(d, w);
          default -> {}
        }
      } catch (Exception e) {
        pause(d, e.getMessage());
        plugin.getLogger().warning("Event " + d.id + ": " + e);
      }
    }
    if (plugin.getServer().getCurrentTick() % 20 == 0) {
      schedule(now);
      for (Drop d : active()) updateBar(d);
      if (!active().isEmpty())
        plugin
            .store
            .save("runtime", data)
            .exceptionally(
                e -> {
                  plugin.main(() -> failed = true);
                  plugin.getLogger().severe(e.toString());
                  return null;
                });
    }
  }

  private void schedule(long now) {
    boolean changed = false;
    for (var entry : plugin.config.current.profiles.entrySet()) {
      Profile p = entry.getValue();
      if (!p.enabled || !p.scheduled) {
        changed |= data.nextRuns.remove(entry.getKey()) != null;
        continue;
      }
      Long next = data.nextRuns.putIfAbsent(entry.getKey(), now + p.intervalMinutes * 60000L);
      if (next == null) {
        changed = true;
        continue;
      }
      if (now >= next) {
        data.nextRuns.put(entry.getKey(), now + p.intervalMinutes * 60000L);
        changed = true;
        if (Weighted.due(now, next, Bukkit.getOnlinePlayers().size(), p.minPlayers, true))
          spawn(entry.getKey(), null, msg -> plugin.getLogger().info(msg));
      }
    }
    if (changed) plugin.store.save("runtime", data);
  }

  private void spawnBarrel(Drop d, World w) {
    removeVisual(d, w);
    if (d.settings == null) d.settings = legacy(d.profile);
    if (d.stage == Stage.LANDING) d.remainingHeight = 0;
    var barrel = (org.bukkit.block.data.type.Barrel) Material.BARREL.createBlockData();
    barrel.setFacing(BlockFace.UP);
    barrel.setOpen(false);
    BlockDisplay display =
        w.spawn(
            new Location(w, d.origin.x() + .5, d.origin.y(), d.origin.z() + .5),
            BlockDisplay.class,
            entity -> {
              entity.setBlock(barrel);
              entity.setTransformation(
                  BarrelFlight.transform(d.remainingHeight, d.settings.barrelSize));
              entity.setInterpolationDuration(0);
              entity.setInterpolationDelay(0);
              entity.setDisplayWidth(0);
              entity.setDisplayHeight(0);
              entity.setViewRange(4);
              entity.setGravity(false);
              entity.setInvulnerable(true);
              entity.setPersistent(false);
              entity.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, d.id);
            });
    barrels.put(d.id, new BarrelFlight(display));
  }

  private void removeVisual(Drop d, World w) {
    BarrelFlight existing = barrels.remove(d.id);
    if (existing != null) existing.display.remove();
    for (Entity e : w.getEntities())
      if ((e instanceof FallingBlock || e instanceof BlockDisplay)
          && d.id.equals(e.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING)))
        e.remove();
  }

  private void land(Drop d, World w) {
    if (barrels.get(d.id) == null || !barrels.get(d.id).display.isValid()) spawnBarrel(d, w);
    Set<Pos> planned = new HashSet<>();
    for (Cell cell : d.cells) planned.add(cell.pos);
    Map<Long, Integer> columnTops = new HashMap<>();
    for (Cell cell : d.cells) {
      int x = d.origin.x() + cell.pos.x(), z = d.origin.z() + cell.pos.z();
      long key = ((long) x << 32) ^ (z & 0xffffffffL);
      columnTops.merge(key, d.origin.y() + cell.pos.y(), Math::max);
    }
    for (var column : columnTops.entrySet()) {
      int x = (int) (column.getKey() >> 32), z = (int) (long) column.getKey();
      if (w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) > column.getValue()) {
        pause(d, "Landing column is obstructed above " + x + ", " + z + "; clear it and retry");
        return;
      }
    }
    for (Cell c : d.cells) {
      Block b = Placement.block(w, d, c);
      if (!Placement.soft(b) && !b.getBlockData().getAsString().equals(c.blockData)) {
        pause(
            d,
            "Landing obstructed at "
                + b.getX()
                + ", "
                + b.getY()
                + ", "
                + b.getZ()
                + "; clear it and retry");
        return;
      }
      if (!planned.contains(c.pos.add(0, -1, 0))
          && !Placement.natural(b.getRelative(BlockFace.DOWN))) {
        pause(d, "Landing support was removed; repair it and retry");
        return;
      }
      if (Loot.needsClearTop(Bukkit.createBlockData(c.blockData).getMaterial().name())
          && !planned.contains(c.pos.add(0, 1, 0))
          && !Placement.soft(b.getRelative(BlockFace.UP))) {
        pause(d, "Container opening column is obstructed; clear it and retry");
        return;
      }
    }
    removeVisual(d, w);
    for (Cell c : d.cells) {
      Block b = Placement.block(w, d, c);
      b.setBlockData(Bukkit.createBlockData(c.blockData), false);
      if (b.getState() instanceof Container container) {
        container.getSnapshotInventory().clear();
        for (Slot slot : c.loot)
          container.getSnapshotInventory().setItem(slot.slot(), Loot.decode(slot.item()));
        container.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, d.id);
        container.update(true, false);
      }
    }
    // Flush the world before the durable GUARDED checkpoint; a crash during LANDING replays only
    // locked contents.
    w.save();
    d.stage = Stage.GUARDED;
    d.guardedAt = System.currentTimeMillis();
    checkpoint(d, () -> reconcileGuardians(d, w));
  }

  private void reconcileGuardians(Drop d, World w) {
    int remaining = (int) d.guardians.stream().filter(g -> !g.defeated).count();
    if (remaining == 0) {
      unlock(d, false);
      return;
    }
    long now = System.currentTimeMillis();
    if (observePlayers(d, w.getPlayers(), now)) {
      checkpoint(d, () -> {});
      return;
    }
    if (!d.glowRevealed
        && shouldGlow(
            now, d.glowStartedAt, d.guardians.size(), remaining, settings(d).guardianGlowSeconds)) {
      d.glowRevealed = true;
      checkpoint(d, () -> {});
      return;
    }
    for (Guardian g : d.guardians) {
      if (g.defeated) {
        if (g.entityId != null) {
          Entity ghost = Bukkit.getEntity(UUID.fromString(g.entityId));
          if (ghost != null) ghost.remove();
        }
        continue;
      }
      LivingEntity mob =
          g.entityId == null
              ? null
              : (Bukkit.getEntity(UUID.fromString(g.entityId)) instanceof LivingEntity l
                  ? l
                  : null);
      if (mob == null || !mob.isValid()) {
        List<LivingEntity> found =
            w.getLivingEntities().stream()
                .filter(
                    e ->
                        g.token.equals(
                            e.getPersistentDataContainer()
                                .get(guardianKey, PersistentDataType.STRING)))
                .toList();
        mob = found.isEmpty() ? null : found.getFirst();
        for (int i = 1; i < found.size(); i++) found.get(i).remove();
        if (mob == null) mob = spawnGuardian(d, g, w);
        g.entityId = mob.getUniqueId().toString();
      }
      if (d.glowRevealed) mob.setGlowing(true);
      g.health = mob.getHealth();
      Location anchor = guardianLocation(d, g, w);
      if (mob.getLocation().distanceSquared(anchor) > g.spec.radius * g.spec.radius
          || mob.getLocation().getY() < d.origin.y() - 16) mob.teleport(anchor);
      if (mob instanceof Warden ward)
        for (Player player : w.getPlayers())
          if (player.getGameMode() == GameMode.SURVIVAL
              && player.getLocation().distanceSquared(anchor) < g.spec.radius * g.spec.radius)
            ward.setAnger(player, 150);
    }
  }

  private Location guardianLocation(Drop d, Guardian g, World w) {
    int index = d.guardians.indexOf(g);
    double angle = index * 2.399963229728653;
    int x = d.origin.x() + (int) Math.round(Math.cos(angle) * 4),
        z = d.origin.z() + (int) Math.round(Math.sin(angle) * 4);
    int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    Block ground = w.getBlockAt(x, y - 1, z);
    if (!Placement.natural(ground) || Math.abs(y - d.origin.y()) > 5)
      return new Location(w, d.origin.x() + .5, d.origin.y() + 7, d.origin.z() + .5);
    return new Location(w, x + .5, y, z + .5);
  }

  private LivingEntity spawnGuardian(Drop d, Guardian g, World w) {
    Mob mob = (Mob) w.spawnEntity(guardianLocation(d, g, w), EntityType.valueOf(g.spec.type));
    mob.setPersistent(true);
    mob.setRemoveWhenFarAway(false);
    mob.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, d.id);
    mob.getPersistentDataContainer().set(guardianKey, PersistentDataType.STRING, g.token);
    if (!g.spec.name.isBlank()) {
      mob.customName(MiniMessage.miniMessage().deserialize(g.spec.name));
      mob.setCustomNameVisible(true);
    }
    if (g.spec.health > 0 && mob.getAttribute(Attribute.MAX_HEALTH) != null)
      mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(g.spec.health);
    double max = Objects.requireNonNull(mob.getAttribute(Attribute.MAX_HEALTH)).getValue();
    mob.setHealth(Math.max(1, Math.min(max, g.health > 0 ? g.health : max)));
    if (mob instanceof PiglinAbstract p) p.setImmuneToZombification(true);
    if (mob instanceof Hoglin h) h.setImmuneToZombification(true);
    if (mob instanceof Ageable a) a.setAdult();
    EntityEquipment equipment = mob.getEquipment();
    if (equipment != null)
      for (var e : g.spec.equipment.entrySet()) {
        EquipmentSlot slot = EquipmentSlot.valueOf(e.getKey());
        equipment.setItem(slot, Loot.decode(e.getValue()));
        equipment.setDropChance(slot, g.spec.vanillaDrops ? 1 : 0);
      }
    for (Effect effect : g.spec.effects)
      mob.addPotionEffect(
          new PotionEffect(
              Objects.requireNonNull(Registry.EFFECT.get(NamespacedKey.fromString(effect.type))),
              effect.seconds * 20,
              effect.amplifier));
    if (mob.getType() == EntityType.PILLAGER
        && equipment != null
        && equipment.getItemInMainHand().getType().isAir())
      equipment.setItemInMainHand(new ItemStack(Material.CROSSBOW));
    return mob;
  }

  public Guardian guardian(Entity entity) {
    String id = entity.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING),
        token = entity.getPersistentDataContainer().get(guardianKey, PersistentDataType.STRING);
    Drop d = data.drops.get(id);
    return d == null
        ? null
        : d.guardians.stream().filter(g -> g.token.equals(token)).findFirst().orElse(null);
  }

  public Drop owner(Entity entity) {
    return data.drops.get(
        entity.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING));
  }

  public void died(LivingEntity entity, List<ItemStack> drops) {
    Drop d = owner(entity);
    Guardian g = guardian(entity);
    if (d == null || g == null || g.defeated) return;
    g.defeated = true;
    g.health = 0;
    if (!g.spec.vanillaDrops) drops.clear();
    for (Slot slot : d.guardianLoot.getOrDefault(g.token, List.of()))
      drops.add(Loot.decode(slot.item()));
    checkpoint(d, () -> {});
  }

  public void unlock(Drop d, boolean force) {
    if (busy.contains(d.id))
      throw new IllegalArgumentException("Event is saving; try again shortly");
    if (d.stage != Stage.GUARDED)
      throw new IllegalArgumentException("Only landed events can be unlocked");
    World w = Bukkit.getWorld(d.world);
    if (w == null) throw new IllegalArgumentException("World unavailable");
    if (force)
      for (Guardian g : d.guardians) {
        if (g.entityId != null) {
          Entity e = Bukkit.getEntity(UUID.fromString(g.entityId));
          if (e != null) e.remove();
        }
        g.defeated = true;
      }
    w.save();
    d.stage = Stage.UNLOCKED;
    // Keep reservations until the checkpoint completes, including interaction protection.
    checkpoint(
        d,
        () -> {
          release(d);
          Bukkit.broadcast(Component.text("Supply drop unlocked • " + coordinates(d)));
        });
  }

  public void cancel(Drop d) {
    if (busy.contains(d.id))
      throw new IllegalArgumentException("Event is saving; try again shortly");
    d.stage = Stage.CANCELLED;
    checkpoint(
        d,
        () -> {
          World w = Bukkit.getWorld(d.world);
          if (w != null) cleanCancelled(d, w);
          release(d);
        });
  }

  private void cleanCancelled(Drop d, World w) {
    removeVisual(d, w);
    for (Entity e : w.getEntities())
      if (d.id.equals(e.getPersistentDataContainer().get(eventKey, PersistentDataType.STRING)))
        e.remove();
    if (!d.cleaned) {
      for (Cell c : d.cells) {
        Block b = Placement.block(w, d, c);
        if (b.getBlockData().getAsString().equals(c.blockData)) b.setType(Material.AIR, false);
      }
      w.save();
      d.cleaned = true;
      plugin.store.save("runtime", data);
    }
  }

  public void retry(Drop d) {
    if (busy.contains(d.id)) throw new IllegalArgumentException("Event is saving");
    d.error = "";
    checkpoint(d, () -> {});
  }

  private void pause(Drop d, String error) {
    d.error = error == null ? "Unknown event failure" : error;
    plugin.getLogger().warning("Paused " + shortId(d) + ": " + d.error);
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.hasPermission("supplydrops.manage"))
        p.sendMessage(Component.text("SupplyDrops • " + shortId(d) + ": " + d.error));
    checkpoint(d, () -> {});
  }

  public boolean protectedBlock(Block b) {
    for (Drop d : data.drops.values()) {
      if ((!active(d) && !busy.contains(d.id)) || !d.world.equals(b.getWorld().getName())) continue;
      for (Cell c : d.cells) {
        int x = d.origin.x() + c.pos.x(),
            z = d.origin.z() + c.pos.z(),
            y = d.origin.y() + c.pos.y();
        if (b.getX() == x
            && b.getZ() == z
            && b.getY() >= y - 1
            && b.getY() <= y + initialHeight(d) + 1) return true;
        if (b.getY() == y && Math.abs(b.getX() - x) + Math.abs(b.getZ() - z) == 1) return true;
      }
    }
    return false;
  }

  private void updateBar(Drop d) {
    if (!settings(d).bossbar) return;
    BossBar bar =
        bars.computeIfAbsent(
            d.id,
            k ->
                BossBar.bossBar(
                    Component.empty(), 1, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));
    long remaining = d.guardians.stream().filter(g -> !g.defeated).count();
    String status =
        !d.error.isBlank()
            ? "Paused"
            : d.stage == Stage.GUARDED
                ? remaining + " guardians"
                : d.stage == Stage.ANNOUNCED
                    ? "Announced"
                    : BarrelFlight.eta(d.remainingHeight, settings(d).speed) + "s to landing";
    bar.name(Component.text("Supply drop • " + coordinates(d) + " • " + status));
    bar.progress(
        (float)
            Math.clamp(
                d.stage == Stage.GUARDED
                    ? (double) remaining / Math.max(1, d.guardians.size())
                    : d.remainingHeight / initialHeight(d),
                0,
                1));
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (p.getWorld().getName().equals(d.world)) p.showBossBar(bar);
      else p.hideBossBar(bar);
    }
  }

  public void stop() {
    for (Drop d : data.drops.values()) {
      World w = Bukkit.getWorld(d.world);
      if (w != null && active(d)) {
        removeVisual(d, w);
        w.save();
      }
      release(d);
    }
    plugin.store.save("runtime", data).join();
  }
}
