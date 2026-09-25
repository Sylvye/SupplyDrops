package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Only packaged in the integration classifier, never the distributable plugin. */
public final class IntegrationPlugin extends SupplyDropsPlugin {
  int assertions;
  World world;
  int phase = Integer.getInteger("supplydrops.phase", 0);

  @Override
  public void onEnable() {
    super.onEnable();
    if (!isEnabled()) return;
    getServer().getScheduler().runTaskLater(this, () -> guard(this::run), 20);
    getServer()
        .getScheduler()
        .runTaskLater(this, () -> fail(new AssertionError("Integration timeout")), 2400);
  }

  void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    assertions++;
  }

  void guard(Runnable work) {
    try {
      work.run();
    } catch (Throwable t) {
      fail(t);
    }
  }

  void fail(Throwable t) {
    getLogger().log(java.util.logging.Level.SEVERE, "INTEGRATION FAIL phase=" + phase, t);
    try {
      Files.writeString(Path.of("integration-failure.txt"), t.toString());
    } catch (Exception ignored) {
    }
    Bukkit.shutdown();
  }

  void pass() {
    getLogger().info("INTEGRATION PASS phase=" + phase + " assertions=" + assertions);
    try {
      Files.writeString(Path.of("integration-phase-" + phase + ".txt"), "PASS " + assertions);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    Bukkit.shutdown();
  }

  void later(int ticks, Runnable work) {
    Bukkit.getScheduler().runTaskLater(this, () -> guard(work), ticks);
  }

  void await(BooleanSupplier ready, Runnable work, int attempts) {
    if (ready.getAsBoolean()) {
      later(4, work);
      return;
    }
    check(attempts > 0, "Timed out waiting for event stage");
    later(5, () -> await(ready, work, attempts - 1));
  }

  void run() {
    world = Bukkit.getWorlds().getFirst();
    if (phase == 0) {
      baseline();
      return;
    }
    Drop d =
        events.data.drops.values().stream()
            .filter(v -> v.profileId.equals("restart"))
            .findFirst()
            .orElseThrow();
    switch (phase) {
      case 1 -> {
        check(d.stage == Stage.ANNOUNCED, "Announcement survived restart");
        d.announceAt = 0;
        await(
            () -> d.stage == Stage.DESCENDING,
            () -> {
              check(d.cells.size() == 24, "All container snapshots recovered");
              d.remainingHeight = 5;
              d.profile.speed = .15;
              later(
                  650,
                  () -> {
                    check(d.stage == Stage.DESCENDING, "Descent exceeds vanilla expiry");
                    check(
                        world.getEntitiesByClass(FallingBlock.class).size() == d.cells.size(),
                        "All falling entities persist after 30 seconds");
                    d.remainingHeight = 1;
                    pass();
                  });
            },
            100);
      }
      case 2 -> {
        check(d.stage == Stage.DESCENDING, "Descent survived restart");
        check(d.remainingHeight < 1.1, "Descent progress restored");
        d.remainingHeight = .1;
        d.profile.speed = 20;
        await(
            () -> d.stage == Stage.GUARDED,
            () -> {
              verifyContainers(d);
              check(!d.guardians.getFirst().defeated, "Guardian encounter restored");
              Entity mob = Bukkit.getEntity(UUID.fromString(d.guardians.getFirst().entityId));
              check(mob != null, "Guardian spawned");
              mob.remove();
              later(
                  10,
                  () -> {
                    check(
                        Bukkit.getEntity(UUID.fromString(d.guardians.getFirst().entityId)) != null,
                        "Missing guardian respawned");
                    pass();
                  });
            },
            100);
      }
      case 3 -> {
        check(d.stage == Stage.GUARDED, "Guarded event survived restart");
        verifyContainers(d);
        var guardian = d.guardians.getFirst();
        LivingEntity mob = (LivingEntity) Bukkit.getEntity(UUID.fromString(guardian.entityId));
        check(mob != null, "Recovered guardian identity");
        events.died(mob, new ArrayList<>());
        mob.remove();
        await(
            () -> d.stage == Stage.UNLOCKED,
            () -> {
              check(
                  !events.protectedBlock(Placement.block(world, d, d.cells.getFirst())),
                  "Unlocked protection released");
              ((Container) Placement.block(world, d, d.cells.getFirst()).getState())
                  .getInventory()
                  .clear();
              world.save();
              pass();
            },
            100);
      }
      case 4 -> {
        check(d.stage == Stage.UNLOCKED, "Unlock survived restart");
        check(
            ((Container) Placement.block(world, d, d.cells.getFirst()).getState())
                .getInventory()
                .isEmpty(),
            "Loot not refilled on restart");
        check(
            Placement.block(world, d, d.cells.get(1)).getType() != Material.AIR,
            "Permanent blocks retained");
        spawnEmptyAndCancel();
      }
      case 5 -> {
        check(d.stage == Stage.UNLOCKED, "Prior pile stays unlocked");
        Drop partial = Store.copy(d, Drop.class);
        partial.id = UUID.randomUUID().toString();
        partial.profileId = "partial";
        partial.stage = Stage.LANDING;
        partial.origin = new Pos(0, 90, -20);
        partial.error = "";
        for (var g : partial.guardians) {
          g.defeated = false;
          g.entityId = null;
          g.token = UUID.randomUUID().toString();
          g.health = -1;
        }
        for (Cell cell : partial.cells)
          Placement.block(world, partial, cell).setType(Material.AIR, false);
        Cell first = partial.cells.getFirst();
        Placement.block(world, partial, first)
            .setBlockData(Bukkit.createBlockData(first.blockData), false);
        events.data.drops.put(partial.id, partial);
        world.save();
        store.save("runtime", events.data).join();
        pass();
      }
      case 6 -> {
        Drop partial =
            events.data.drops.values().stream()
                .filter(v -> v.profileId.equals("partial"))
                .findFirst()
                .orElseThrow();
        await(
            () -> partial.stage == Stage.GUARDED,
            () -> {
              verifyContainers(partial);
              events.cancel(partial);
              await(
                  () -> partial.cleaned,
                  () -> {
                    check(
                        partial.cells.stream()
                            .allMatch(
                                cell -> Placement.block(world, partial, cell).getType().isAir()),
                        "Cancelled landed blocks removed");
                    pass();
                  },
                  100);
            },
            100);
      }
      case 7 -> {
        Drop partial =
            events.data.drops.values().stream()
                .filter(v -> v.profileId.equals("partial"))
                .findFirst()
                .orElseThrow();
        check(partial.stage == Stage.CANCELLED && partial.cleaned, "Cancelled state persisted");
        check(
            partial.cells.stream()
                .allMatch(cell -> Placement.block(world, partial, cell).getType().isAir()),
            "Cancelled blocks remain absent");
        check(
            world.getLivingEntities().stream()
                .noneMatch(entity -> entity.getPersistentDataContainer().has(events.guardianKey)),
            "No orphan guardians");
        check(events.active().isEmpty(), "No active events remain");
        pass();
      }
      case 8 -> adminAndGuardians();
      case 9 -> failurePaths();
      case 10 -> terrainAndGlow();
      case 11 -> guiCloseWarnings();
      default -> throw new AssertionError("Unknown phase");
    }
  }

  void baseline() {
    for (int x = -30; x <= 40; x++)
      for (int z = -30; z <= 30; z++) {
        world.getBlockAt(x, 89, z).setType(Material.STONE, false);
        for (int y = 90; y < 105; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
      }
    var item = new ItemStack(Material.DIAMOND_SWORD);
    item.editMeta(
        meta -> {
          meta.displayName(Component.text("Integration sword"));
          meta.getPersistentDataContainer()
              .set(new NamespacedKey(this, "custom"), PersistentDataType.STRING, "preserved");
        });
    var decoded = Loot.decode(Loot.encode(item));
    check(item.isSimilar(decoded), "Copied item metadata round-trip");
    var items = new ItemTable();
    items.minRolls = 1;
    items.maxRolls = 1;
    items.entries.add(new ItemEntry(Loot.encode(item)));
    Validation.itemTable(items);
    check(Loot.maxSlots(items) == 1, "Unstackable capacity");
    items.maxRolls = 27;
    items.entries.getFirst().max = 2;
    try {
      Validation.itemTable(items);
      throw new AssertionError("Capacity should fail");
    } catch (IllegalArgumentException expected) {
      assertions++;
    }
    items.maxRolls = 1;
    items.entries.getFirst().max = 1;
    Config c = config.current;
    c.items.put("integration", items);
    check(Loot.containers().size() == 23, "23 container types supported");
    Drop d = new Drop();
    d.profileId = "restart";
    d.world = world.getName();
    d.profile = new Profile();
    d.profile.delaySeconds = 3600;
    d.profile.height = 5;
    d.profile.speed = .15;
    d.profile.worlds = List.of(world.getName());
    d.origin = new Pos(0, 90, 0);
    d.announceAt = System.currentTimeMillis();
    d.remainingHeight = 5;
    for (String type : Loot.containers()) {
      BlockTable table = new BlockTable();
      table.entries.add(new BlockEntry(type, 1));
      Validation.blockTable(table);
      assertions++;
      var block = Material.valueOf(type).createBlockData();
      if (block instanceof org.bukkit.block.data.Directional dir
          && dir.getFaces().contains(BlockFace.UP)) dir.setFacing(BlockFace.UP);
      Cell cell =
          new Cell(new Pos(d.cells.size() % 6 * 2, 0, d.cells.size() / 6 * 2), block.getAsString());
      cell.loot = Loot.roll(items, new Random(1));
      d.cells.add(cell);
    }
    d.cells.add(new Cell(new Pos(12, 0, 0), Material.GOLD_BLOCK.createBlockData().getAsString()));
    var g = new Model.Guardian();
    g.spec = new MobSpec();
    g.spec.type = "HUSK";
    g.spec.health = 40;
    d.guardians.add(g);
    for (Cell cell : d.cells)
      check(
          Placement.site(world, d.origin, List.of(cell), true).valid(),
          "Container site accepted: " + cell.blockData);
    world.getBlockAt(0, 91, 0).setType(Material.STONE);
    check(
        !Placement.site(world, d.origin, List.of(d.cells.getFirst()), true).valid(),
        "Obstructed site rejected");
    world.getBlockAt(0, 91, 0).setType(Material.AIR);
    events.data.drops.put(d.id, d);
    world.save();
    store.save("runtime", events.data).join();
    // Optimistic editor conflict: a stale snapshot must not overwrite a newer save.
    Config draft = Store.copy(c, Config.class);
    config.commit(
        draft,
        0,
        error -> {
          guard(
              () -> {
                check(error == null, "Initial config commit");
                config.commit(
                    draft,
                    0,
                    stale -> {
                      guard(
                          () -> {
                            check(stale != null, "Stale editor rejected");
                            pass();
                          });
                    });
              });
        });
  }

  void verifyContainers(Drop d) {
    for (Cell cell : d.cells) {
      Block b = Placement.block(world, d, cell);
      check(
          b.getBlockData().getAsString().equals(cell.blockData),
          "Block data materialized: " + cell.blockData);
      if (b.getState() instanceof Container chest) {
        check(!chest.getInventory().isEmpty(), "Loot materialized");
        InventoryMoveItemEvent move =
            new InventoryMoveItemEvent(
                chest.getInventory(),
                new ItemStack(Material.DIAMOND),
                Bukkit.createInventory(null, 9),
                true);
        Bukkit.getPluginManager().callEvent(move);
        check(move.isCancelled(), "Hopper extraction blocked");
      }
    }
    Block target = Placement.block(world, d, d.cells.getFirst());
    BlockExplodeEvent explosion =
        new BlockExplodeEvent(
            target,
            target.getState(),
            new ArrayList<>(List.of(target)),
            1,
            ExplosionResult.DESTROY);
    Bukkit.getPluginManager().callEvent(explosion);
    check(explosion.blockList().isEmpty(), "Explosion protection");
    check(events.protectedBlock(target.getRelative(BlockFace.UP)), "Opening clearance protected");
  }

  void spawnEmptyAndCancel() {
    Config c = config.current;
    Profile p = Store.copy(c.profiles.get("Example"), Profile.class);
    p.enabled = true;
    p.height = 5;
    p.speed = 20;
    p.delaySeconds = 0;
    c.settings.delaySeconds = 0;
    c.settings.speed = 20;
    p.minRolls = 2;
    p.maxRolls = 2;
    EncounterTable t = new EncounterTable();
    t.entries.add(new Encounter());
    c.guardians.put("empty", t);
    p.guardianTable = "empty";
    c.profiles.put("empty", p);
    events.spawn(
        "empty",
        new Location(world, -15, 90, -15),
        msg ->
            guard(
                () -> {
                  check(msg.startsWith("Event"), "Real spawn accepted: " + msg);
                  Drop empty =
                      events.data.drops.values().stream()
                          .filter(v -> v.profileId.equals("empty"))
                          .findFirst()
                          .orElseThrow();
                  await(
                      () -> empty.stage == Stage.UNLOCKED,
                      () -> {
                        check(empty.cells.size() == 2, "Master roll count");
                        p.delaySeconds = 3600;
                        events.spawn(
                            "empty",
                            new Location(world, -15, 90, 15),
                            reply ->
                                guard(
                                    () -> {
                                      check(reply.startsWith("Event"), "Second spawn accepted");
                                      Drop cancel = events.active().iterator().next();
                                      events.cancel(cancel);
                                      await(
                                          () -> cancel.cleaned,
                                          () -> {
                                            check(
                                                events.active().isEmpty(),
                                                "Cancellation clears active event");
                                            pass();
                                          },
                                          100);
                                    }));
                      },
                      100);
                }));
  }

  void adminAndGuardians() {
    try {
      ItemStack source = new ItemStack(Material.DIAMOND, 5);
      source.editMeta(meta -> meta.displayName(Component.text("Copied custom diamond")));
      ItemStack before = source.clone();
      var top = new java.util.concurrent.atomic.AtomicReference<org.bukkit.inventory.Inventory>();
      var dialog = new java.util.concurrent.atomic.AtomicReference<Object>();
      UUID id = UUID.randomUUID();
      var inventory =
          (org.bukkit.inventory.PlayerInventory)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClassLoader(),
                  new Class<?>[] {org.bukkit.inventory.PlayerInventory.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "getContents" -> new ItemStack[] {source};
                        case "getItemInMainHand" -> source;
                        case "getSize" -> 41;
                        default -> null;
                      });
      Player actor =
          (Player)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClassLoader(),
                  new Class<?>[] {Player.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "hasPermission", "isOnline" -> true;
                        case "getUniqueId" -> id;
                        case "getWorld" -> world;
                        case "getInventory" -> inventory;
                        case "openInventory" -> {
                          top.set((org.bukkit.inventory.Inventory) args[0]);
                          yield null;
                        }
                        case "showDialog" -> {
                          dialog.set(args[0]);
                          yield null;
                        }
                        case "getName" -> "IntegrationAdmin";
                        case "hashCode" -> id.hashCode();
                        case "equals" -> proxy == args[0];
                        default -> null;
                      });
      menus.open(actor);
      check(top.get() != null && top.get().getSize() == 54, "Dashboard builds");
      check(
          top.get()
                  .getItem(0)
                  .getItemMeta()
                  .displayName()
                  .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC)
              == net.kyori.adventure.text.format.TextDecoration.State.FALSE,
          "Menu labels nonitalic");
      var view =
          (org.bukkit.inventory.InventoryView)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClassLoader(),
                  new Class<?>[] {org.bukkit.inventory.InventoryView.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "getTopInventory" -> top.get();
                        case "getBottomInventory" -> inventory;
                        case "getPlayer" -> actor;
                        case "getInventory" -> (int) args[0] < 54 ? top.get() : inventory;
                        case "convertSlot" -> args[0];
                        case "getType" -> org.bukkit.event.inventory.InventoryType.CHEST;
                        default -> null;
                      });
      for (var click :
          List.of(
              org.bukkit.event.inventory.ClickType.LEFT,
              org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
              org.bukkit.event.inventory.ClickType.NUMBER_KEY)) {
        var ev =
            new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 49, click, InventoryAction.PICKUP_ALL);
        menus.click(ev);
        check(ev.isCancelled(), "Menu click cannot steal items: " + click);
      }
      var selected = new java.util.concurrent.atomic.AtomicReference<ItemStack>();
      var picker =
          Menus.class.getDeclaredMethod(
              "pickItem", Player.class, java.util.function.Consumer.class, Runnable.class);
      picker.setAccessible(true);
      picker.invoke(
          menus,
          actor,
          (java.util.function.Consumer<ItemStack>) selected::set,
          (Runnable) () -> {});
      Object holder = top.get().getHolder();
      var actions = holder.getClass().getDeclaredField("actions");
      actions.setAccessible(true);
      ((Runnable) ((Map<?, ?>) actions.get(holder)).get(0)).run();
      check(source.equals(before), "GUI copying does not consume or alter source");
      check(selected.get().isSimilar(source), "GUI copying preserves item metadata");
      var input =
          Menus.class.getDeclaredMethod(
              "input",
              Player.class,
              String.class,
              String.class,
              java.util.function.Consumer.class,
              Runnable.class);
      input.setAccessible(true);
      input.invoke(
          menus,
          actor,
          "Test number",
          "20",
          (java.util.function.Consumer<String>) v -> {},
          (Runnable) () -> {});
      check(dialog.get() != null, "Native input dialog built");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    Validation.encounters(config.current.guardians.get("Example"), config.current);
    assertions++;
    Drop group = new Drop();
    group.profileId = "guardian-tests";
    group.profile = new Profile();
    group.world = world.getName();
    group.origin = new Pos(-20, 90, 0);
    group.stage = Stage.GUARDED;
    for (String type :
        List.of(
            "RAVAGER",
            "EVOKER",
            "PILLAGER",
            "VINDICATOR",
            "PIGLIN_BRUTE",
            "HOGLIN",
            "WARDEN",
            "HUSK",
            "PARCHED")) {
      var g = new Model.Guardian();
      g.spec = new MobSpec();
      g.spec.type = type;
      g.spec.health = 40;
      g.spec.radius = 8;
      g.spec.equipment.put("HEAD", Loot.encode(new ItemStack(Material.DIAMOND_HELMET)));
      var effect = new Model.Effect();
      effect.type = "minecraft:speed";
      g.spec.effects.add(effect);
      group.guardians.add(g);
    }
    events.data.drops.put(group.id, group);
    later(
        10,
        () -> {
          for (var g : group.guardians) {
            LivingEntity mob = (LivingEntity) Bukkit.getEntity(UUID.fromString(g.entityId));
            check(mob != null, "Guardian type spawned: " + g.spec.type);
            check(mob.getHealth() == 40, "Custom health applied");
            check(
                mob.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED),
                "Custom effect applied");
            mob.damage(5);
            check(mob.getHealth() == 40, "Environmental damage blocked");
          }
          var first = group.guardians.getFirst();
          Entity mob = Bukkit.getEntity(UUID.fromString(first.entityId));
          mob.teleport(new Location(world, 20, 90, 20));
          later(
              5,
              () -> {
                check(mob.getLocation().getX() < 0, "Guardian confined to encounter");
                events.unlock(group, true);
                later(5, this::pass);
              });
        });
  }

  void failurePaths() {
    Drop d = new Drop();
    d.profileId = "failure-paths";
    d.profile = new Profile();
    d.profile.height = 5;
    d.world = world.getName();
    d.origin = new Pos(-20, 90, -20);
    d.stage = Stage.LANDING;
    Cell cell = new Cell(new Pos(0, 0, 0), Material.CHEST.createBlockData().getAsString());
    cell.loot = Loot.roll(config.current.items.get("Example"), new Random(1));
    d.cells.add(cell);
    Block base = Placement.block(world, d, cell);
    base.setType(Material.AIR);
    base.getRelative(BlockFace.UP, 4).setType(Material.STONE);
    events.data.drops.put(d.id, d);
    later(
        8,
        () -> {
          check(
              !d.error.isBlank() && d.stage == Stage.LANDING, "Solid overhead pauses landing");
          check(base.getType().isAir(), "Obstructed landing does not overwrite world");
          Entity golem =
              world.spawnEntity(new Location(world, -22, 90, -20), EntityType.COPPER_GOLEM);
          var transport =
              new io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent(
                  golem, base);
          Bukkit.getPluginManager().callEvent(transport);
          check(!transport.isAllowed(), "Copper golem extraction blocked");
          golem.remove();
          base.getRelative(BlockFace.UP, 4).setType(Material.AIR);
          events.retry(d);
          await(
              () -> d.stage == Stage.UNLOCKED,
              () -> {
                check(
                    !((Container) base.getState()).getInventory().isEmpty(),
                    "Retry fills container once");
                var testCells =
                    List.of(
                        new Cell(
                            new Pos(0, 0, 0), Material.GOLD_BLOCK.createBlockData().getAsString()));
                Pos point = new Pos(-25, 90, -25);
                world.getBlockAt(-25, 89, -25).setType(Material.WATER);
                check(Placement.check(world, point, testCells, 5) != null, "Water rejected");
                world.getBlockAt(-25, 89, -25).setType(Material.STONE);
                double size = world.getWorldBorder().getSize();
                world.getWorldBorder().setSize(10);
                check(Placement.check(world, point, testCells, 5) != null, "World border rejected");
                world.getWorldBorder().setSize(size);
                Profile missing = Store.copy(config.current.profiles.get("Example"), Profile.class);
                missing.worlds = List.of("absent-test-world");
                try {
                  Validation.profile(missing, config.current);
                  throw new AssertionError("Missing world accepted");
                } catch (IllegalArgumentException expected) {
                  assertions++;
                }
                pass();
              },
              100);
        });
  }

  void terrainAndGlow() {
    int x = 25, z = 25;
    Pos origin = new Pos(x, 90, z);
    List<Cell> one =
        List.of(new Cell(new Pos(0, 0, 0), Material.GOLD_BLOCK.createBlockData().getAsString()));
    world.getBlockAt(x, 89, z).setType(Material.SAND, false);
    world.getBlockAt(x, 90, z).setType(Material.SHORT_GRASS, false);
    check(Placement.site(world, origin, one, true).valid(), "Dry sand and grass accepted");
    Entity occupant = world.spawnEntity(new Location(world, x + .5, 90, z + .5), EntityType.ARMOR_STAND);
    check(Placement.site(world, origin, one, true).valid(), "Entity does not obstruct site");
    occupant.remove();
    world.getBlockAt(x, 89, z).setType(Material.WATER, false);
    check(!Placement.site(world, origin, one, true).valid(), "Water support rejected");
    world.getBlockAt(x, 89, z).setType(Material.GRAVEL, false);
    world.getBlockAt(x, 90, z).setType(Material.AIR, false);
    check(Placement.site(world, origin, one, true).valid(), "Dry gravel accepted");
    world.getBlockAt(x, 91, z).setType(Material.STONE, false);
    check(!Placement.site(world, origin, one, true).valid(), "Solid overhead obstruction rejected");
    world.getBlockAt(x, 91, z).setType(Material.AIR, false);
    List<Cell> slope =
        List.of(
            one.getFirst(),
            new Cell(new Pos(0, 1, 0), Material.GOLD_BLOCK.createBlockData().getAsString()),
            new Cell(new Pos(1, 0, 0), Material.GOLD_BLOCK.createBlockData().getAsString()));
    world.getBlockAt(x + 1, 90, z).setType(Material.STONE, false);
    Placement.Site site = Placement.site(world, origin, slope, true);
    check(site.valid(), "Two-level connected slope accepted: " + site.error());
    check(Placement.connected(site.cells().stream().map(cell -> cell.pos).collect(java.util.stream.Collectors.toSet())), "Adjusted slope stays connected");
    check(origin.y() + site.cells().stream().mapToInt(cell -> cell.pos.y()).max().orElseThrow() + site.fallDistance() == world.getMaxHeight() - 1, "Launches at world height");
    for (int sx = 14; sx <= 46; sx++)
      for (int sz = 14; sz <= 46; sz++) {
        world.getBlockAt(sx, 89, sz)
            .setType(sz > 38 ? Material.WATER : sx < 30 ? Material.SAND : Material.STONE, false);
        world.getBlockAt(sx, 90, sz).setType(Material.AIR, false);
      }
    int sand = 0, inland = 0;
    long searchStarted = System.nanoTime();
    Random random = new Random(81247);
    for (int i = 0; i < 200; i++) {
      Pos sampled = Placement.random(world, 16, random);
      int sx = sampled.x() + 30, sz = sampled.z() + 30;
      Pos shifted = new Pos(sx, world.getHighestBlockYAt(sx, sz, HeightMap.MOTION_BLOCKING) + 1, sz);
      if (Placement.site(world, shifted, one, true).valid()) {
        if (sx < 30) sand++;
        else inland++;
      }
    }
    long elapsedMillis = (System.nanoTime() - searchStarted) / 1_000_000;
    check(sand > 35 && inland > 35, "Mixed-terrain sample reaches sand and inland");
    check(elapsedMillis < 1000, "200 loaded candidate checks finish in one second: " + elapsedMillis);
    getLogger().info("Mixed-terrain site sample: sand=" + sand + " inland=" + inland + " elapsed=" + elapsedMillis + "ms");
    long now = System.currentTimeMillis();
    check(!Events.shouldGlow(now, now, 8, 3, 180), "Above 25 percent remains unlit");
    check(Events.shouldGlow(now, now, 8, 2, 180), "Exactly 25 percent glows");
    check(Events.shouldGlow(now, now - 180_000, 8, 8, 180), "Timer glows all survivors");
    check(!Events.shouldGlow(now, now - 179_999, 8, 8, 180), "Timer waits for boundary");
    Drop glow = new Drop();
    glow.profileId = "glow-test";
    glow.profile = new Profile();
    glow.settings = new Settings();
    glow.world = world.getName();
    glow.origin = new Pos(25, 90, 20);
    glow.stage = Stage.GUARDED;
    glow.guardedAt = now;
    for (int i = 0; i < 4; i++) {
      var guardian = new Model.Guardian();
      guardian.spec = new MobSpec();
      guardian.spec.type = "HUSK";
      glow.guardians.add(guardian);
    }
    events.data.drops.put(glow.id, glow);
    later(
        6,
        () -> {
          for (var guardian : glow.guardians) {
            LivingEntity mob = (LivingEntity) Bukkit.getEntity(UUID.fromString(guardian.entityId));
            check(mob != null && !mob.isGlowing(), "Fresh guardian is not glowing");
          }
          for (int i = 0; i < 3; i++) glow.guardians.get(i).defeated = true;
          later(
              6,
              () -> {
                var survivor = glow.guardians.get(3);
                LivingEntity mob = (LivingEntity) Bukkit.getEntity(UUID.fromString(survivor.entityId));
                check(mob != null && mob.isGlowing(), "Last quarter glows in world");
                mob.remove();
                later(
                    6,
                    () -> {
                      LivingEntity replacement =
                          (LivingEntity) Bukkit.getEntity(UUID.fromString(survivor.entityId));
                      check(replacement != null && replacement.isGlowing(), "Replacement regains glow");
                      events.unlock(glow, true);
                      pass();
                    });
              });
        });
  }

  void guiCloseWarnings() {
    try {
      UUID id = UUID.randomUUID();
      var shown = new java.util.concurrent.atomic.AtomicReference<org.bukkit.inventory.Inventory>();
      var open = new java.util.concurrent.atomic.AtomicReference<org.bukkit.inventory.InventoryView>();
      var actorRef = new java.util.concurrent.atomic.AtomicReference<Player>();
      var messages = new ArrayList<String>();
      var inventory =
          (org.bukkit.inventory.PlayerInventory)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClassLoader(),
                  new Class<?>[] {org.bukkit.inventory.PlayerInventory.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "getContents" -> new ItemStack[41];
                        case "getSize" -> 41;
                        default -> null;
                      });
      Player actor =
          (Player)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClassLoader(),
                  new Class<?>[] {Player.class},
                  (proxy, method, args) ->
                      switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getWorld" -> world;
                        case "getInventory" -> inventory;
                        case "isOnline", "hasPermission" -> true;
                        case "getName" -> "DraftTester";
                        case "getOpenInventory" -> open.get();
                        case "openInventory" -> {
                          shown.set((org.bukkit.inventory.Inventory) args[0]);
                          open.set(mockView(shown.get(), inventory, actorRef));
                          yield open.get();
                        }
                        case "sendMessage" -> {
                          messages.add(String.valueOf(args[0]));
                          yield null;
                        }
                        case "hashCode" -> id.hashCode();
                        case "equals" -> proxy == args[0];
                        default -> null;
                      });
      actorRef.set(actor);
      menus.open(actor);
      var field = Menus.class.getDeclaredField("sessions");
      field.setAccessible(true);
      Object session = ((Map<?, ?>) field.get(menus)).get(id);
      var draftField = session.getClass().getDeclaredField("draft");
      draftField.setAccessible(true);
      Config draft = (Config) draftField.get(session);
      int savedRadius = draft.settings.radius;
      draft.settings.radius++;
      org.bukkit.inventory.InventoryView closing = open.get();
      open.set(mockView(Bukkit.createInventory(null, 9), inventory, actorRef));
      menus.inventoryClose(new InventoryCloseEvent(closing));
      later(
          3,
          () -> {
            check(messages.size() == 1 && messages.getFirst().contains("NOT been applied"), "Dirty close warns once");
            menus.open(actor);
            org.bukkit.inventory.InventoryView navigating = open.get();
            menus.inventoryClose(new InventoryCloseEvent(navigating));
            menus.open(actor);
            later(
                3,
                () -> {
                  check(messages.size() == 1, "Menu navigation does not warn");
                  check(draft.settings.radius == savedRadius + 1, "Draft retained on reopen");
                  draft.settings.radius = savedRadius;
                  org.bukkit.inventory.InventoryView clean = open.get();
                  open.set(mockView(Bukkit.createInventory(null, 9), inventory, actorRef));
                  menus.inventoryClose(new InventoryCloseEvent(clean));
                  later(3, () -> {
                    check(messages.size() == 1, "Clean close does not warn");
                    pass();
                  });
                });
          });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  org.bukkit.inventory.InventoryView mockView(
      org.bukkit.inventory.Inventory top,
      org.bukkit.inventory.PlayerInventory bottom,
      java.util.concurrent.atomic.AtomicReference<Player> actor) {
    return (org.bukkit.inventory.InventoryView)
        java.lang.reflect.Proxy.newProxyInstance(
            getClassLoader(),
            new Class<?>[] {org.bukkit.inventory.InventoryView.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getTopInventory" -> top;
                  case "getBottomInventory" -> bottom;
                  case "getPlayer" -> actor.get();
                  case "getType" -> org.bukkit.event.inventory.InventoryType.CHEST;
                  default -> null;
                });
  }
}
