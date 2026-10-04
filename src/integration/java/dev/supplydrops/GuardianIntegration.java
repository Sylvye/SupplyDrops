package dev.supplydrops;

import static dev.supplydrops.Model.*;

import dev.supplydrops.Model.Guardian;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/** Attribute support, real spawns, wither lifecycle, and replacement snapshots on Paper. */
final class GuardianIntegration {
  private final IntegrationPlugin plugin;
  private final Drop group = new Drop();
  private MobSpec source;
  private LivingEntity custom, fractional, vanilla;
  private Wither wither;
  private Guardian witherSpec;
  private Runnable done;

  GuardianIntegration(IntegrationPlugin plugin) { this.plugin = plugin; }

  void run(Runnable complete) {
    done = complete;
    verifyValidation();
    for (int x = -152; x <= -128; x++)
      for (int z = -152; z <= -128; z++) {
        plugin.world.getBlockAt(x, 89, z).setType(Material.STONE, false);
        for (int y = 90; y < 112; y++) plugin.world.getBlockAt(x, y, z).setType(Material.AIR, false);
      }
    group.profileId = "attribute-tests";
    group.profile = new Profile();
    group.world = plugin.world.getName();
    group.origin = new Pos(-140, 90, -140);
    group.stage = Stage.GUARDED;
    group.cells.add(new Cell(new Pos(0, 0, 0), Material.GOLD_BLOCK.createBlockData().getAsString()));
    plugin.world.getBlockAt(-140, 90, -140).setType(Material.GOLD_BLOCK, false);
    source = new MobSpec();
    source.attributes.put(GuardianAttributes.MAX_HEALTH, 60.5);
    source.attributes.put("minecraft:movement_speed", .3);
    source.attributes.put("minecraft:attack_damage", 7.0);
    source.attributes.put("minecraft:knockback_resistance", 2.0);
    Model.Effect speed = new Model.Effect();
    source.effects.add(speed);
    add(source);
    MobSpec small = new MobSpec();
    small.attributes.put(GuardianAttributes.MAX_HEALTH, .5);
    add(small);
    add(new MobSpec());
    MobSpec boss = new MobSpec();
    boss.type = "WITHER";
    boss.attributes.put(GuardianAttributes.MAX_HEALTH, 90.0);
    boss.attributes.put("minecraft:movement_speed", .45);
    boss.vanillaDrops = true;
    witherSpec = add(boss);
    plugin.events.data.drops.put(group.id, group);
    plugin.await(() -> group.guardians.stream().allMatch(g -> g.entityId != null), this::verifySpawns, 100);
  }

  private Guardian add(MobSpec source) {
    Guardian guardian = new Guardian();
    guardian.spec = Store.copy(source, MobSpec.class);
    guardian.spec.radius = 8;
    guardian.spec.damageImmune = true; // Keep attribute fixtures alive through the wither's charge explosion.
    group.guardians.add(guardian);
    return guardian;
  }

  private LivingEntity mob(int index) {
    return (LivingEntity) Bukkit.getEntity(UUID.fromString(group.guardians.get(index).entityId));
  }

  private void verifyValidation() {
    MobSpec spec = new MobSpec();
    check(GuardianAttributes.supported(spec).contains("minecraft:movement_speed"), "Husk supports movement speed");
    reject(() -> GuardianAttributes.validate(spec, "minecraft:no_such_attribute", 1.0), "Unknown attribute rejected");
    reject(() -> GuardianAttributes.validate(spec, "minecraft:block_break_speed", 1.0), "Unsupported attribute rejected");
    for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      reject(() -> GuardianAttributes.validate(spec, "minecraft:movement_speed", value), "Non-finite attribute rejected");
    reject(() -> GuardianAttributes.validate(spec, GuardianAttributes.MAX_HEALTH, 0.0), "Zero max health rejected");
    reject(() -> GuardianAttributes.validate(spec, GuardianAttributes.MAX_HEALTH, 1025.0), "Excess max health rejected");
    reject(() -> GuardianAttributes.validate(spec, "minecraft:armor", null), "Null attribute value rejected");
    GuardianAttributes.validate(spec, GuardianAttributes.MAX_HEALTH, .5);
    check(Validation.guardianType(EntityType.WITHER), "Wither is an eligible guardian");
    check(!Validation.guardianType(EntityType.ENDER_DRAGON), "Ender dragon stays excluded");
    EncounterTable table = new EncounterTable();
    Encounter encounter = new Encounter();
    MobSpec boss = new MobSpec();
    boss.type = "WITHER";
    encounter.mobs.add(boss);
    table.entries.add(encounter);
    Validation.encounters(table, plugin.config.current);
    boss.type = "ENDER_DRAGON";
    reject(() -> Validation.encounters(table, plugin.config.current), "Validation excludes Ender dragon");
    Config disabled = Store.copy(plugin.config.current, Config.class);
    disabled.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes
        .put("minecraft:no_such_attribute", 1.0);
    reject(() -> Validation.config(disabled), "Invalid attributes rejected even in disabled profiles");
    Drop unavailable = new Drop();
    unavailable.profileId = "unavailable-attribute-migration";
    unavailable.profile = new Profile();
    unavailable.world = "absent-world";
    unavailable.stage = Stage.UNLOCKED;
    Guardian guardian = new Guardian();
    guardian.spec = new MobSpec();
    guardian.spec.health = 31.5;
    unavailable.guardians.add(guardian);
    plugin.events.data.drops.put(unavailable.id, unavailable);
    plugin.events.recover();
    check(guardian.spec.health == 0 && guardian.spec.attributes.get(GuardianAttributes.MAX_HEALTH) == 31.5,
        "Runtime health migrates before checking world availability");
  }

  private void verifySpawns() {
    custom = mob(0);
    fractional = mob(1);
    vanilla = mob(2);
    wither = (Wither) mob(3);
    for (LivingEntity entity : List.of(custom, fractional, vanilla)) ((Mob) entity).setAI(false);
    check(custom.getHealth() == 60.5, "Custom max health initializes health");
    check(custom.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue() == .3, "Movement base applied");
    check(custom.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue() == 7, "Attack damage base applied");
    check(custom.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getBaseValue() == 2
        && custom.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue() == 1, "Effective value uses vanilla clamping");
    check(custom.hasPotionEffect(PotionEffectType.SPEED)
        && custom.getAttribute(Attribute.MOVEMENT_SPEED).getValue() > .3, "Potion modifiers remain effective");
    check(fractional.getAttribute(Attribute.MAX_HEALTH).getBaseValue() == .5
        && fractional.getHealth() == fractional.getAttribute(Attribute.MAX_HEALTH).getValue(),
        "Fractional max health accepted with vanilla effective limit");
    check(vanilla.getAttribute(Attribute.MAX_HEALTH).getBaseValue()
        == GuardianAttributes.defaultValue(new MobSpec(), GuardianAttributes.MAX_HEALTH), "Absent override retains vanilla default");
    check(wither.getAttribute(Attribute.MAX_HEALTH).getBaseValue() == 90, "Wither attributes applied before charge");
    check(wither.getInvulnerableTicks() > 0 && wither.getHealth() < 90, "Fresh wither enters normal charging phase");
    verifyTerrainProtection();
    source.attributes.put("minecraft:movement_speed", .9);
    custom.setHealth(12.25);
    plugin.later(3, () -> {
      check(group.guardians.getFirst().health == 12.25, "Current health recorded for replacement");
      UUID oldId = custom.getUniqueId();
      custom.remove();
      plugin.await(() -> !group.guardians.getFirst().entityId.equals(oldId.toString()), () -> {
        custom = mob(0);
        ((Mob) custom).setAI(false);
        check(custom.getHealth() == 12.25, "Replacement preserves damaged health");
        check(custom.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue() == .3, "Replacement uses snapshot overrides");
        verifyReplacementLimits();
      }, 100);
    });
  }

  private void verifyReplacementLimits() {
    fractional.setHealth(.25);
    plugin.later(3, () -> {
      UUID smallId = fractional.getUniqueId();
      fractional.remove();
      plugin.await(() -> !group.guardians.get(1).entityId.equals(smallId.toString()), () -> {
        fractional = mob(1);
        ((Mob) fractional).setAI(false);
        check(fractional.getHealth() == .25, "Replacement preserves current health below one");
        UUID customId = custom.getUniqueId();
        group.guardians.getFirst().health = 100;
        custom.remove();
        plugin.await(() -> !group.guardians.getFirst().entityId.equals(customId.toString()), () -> {
          custom = mob(0);
          ((Mob) custom).setAI(false);
          check(custom.getHealth() == 60.5, "Replacement caps saved health at effective maximum");
          plugin.await(() -> wither.getInvulnerableTicks() == 0, this::replaceWither, 100);
        }, 100);
      }, 100);
    });
  }

  private void verifyTerrainProtection() {
    var locked = plugin.world.getBlockAt(-140, 90, -140);
    var nearby = plugin.world.getBlockAt(-146, 89, -140);
    EntityChangeBlockEvent protectedChange = new EntityChangeBlockEvent(wither, locked, Material.AIR.createBlockData());
    Bukkit.getPluginManager().callEvent(protectedChange);
    check(protectedChange.isCancelled(), "Wither cannot break locked pile directly");
    EntityChangeBlockEvent terrain = new EntityChangeBlockEvent(wither, nearby, Material.AIR.createBlockData());
    Bukkit.getPluginManager().callEvent(terrain);
    check(!terrain.isCancelled(), "Wither can break unprotected nearby terrain");
    EntityExplodeEvent explosion = new EntityExplodeEvent(wither, wither.getLocation(),
        new ArrayList<>(List.of(locked, nearby)), 1, ExplosionResult.DESTROY);
    Bukkit.getPluginManager().callEvent(explosion);
    check(!explosion.isCancelled() && !explosion.blockList().contains(locked)
        && explosion.blockList().contains(nearby), "Wither explosion protects pile and retains surrounding damage");
    WitherSkull skull = plugin.world.spawn(wither.getLocation(), WitherSkull.class);
    skull.setShooter(wither);
    EntityExplodeEvent skullExplosion = new EntityExplodeEvent(skull, skull.getLocation(),
        new ArrayList<>(List.of(locked, nearby)), 1, ExplosionResult.DESTROY);
    Bukkit.getPluginManager().callEvent(skullExplosion);
    check(!skullExplosion.blockList().contains(locked) && skullExplosion.blockList().contains(nearby),
        "Wither skull explosion respects locked pile protection");
    skull.remove();
  }

  private void replaceWither() {
    check(wither.getHealth() == 90, "Wither charge heals to configured maximum");
    wither.setHealth(42);
    plugin.later(3, () -> {
      UUID oldId = wither.getUniqueId();
      wither.remove();
      group.glowRevealed = true;
      plugin.await(() -> !witherSpec.entityId.equals(oldId.toString()), () -> {
        wither = (Wither) mob(3);
        check(wither.getHealth() >= 42 && wither.getHealth() < 44, "Replacement wither preserves health, allowing vanilla regeneration");
        check(wither.getInvulnerableTicks() == 0, "Replacement wither does not restart charge");
        check(wither.isGlowing(), "Replacement wither inherits revealed glow");
        check(wither.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue() == .45, "Replacement wither keeps attributes");
        wither.teleport(wither.getLocation().add(40, 0, 0));
        plugin.later(3, this::defeatWither);
      }, 100);
    });
  }

  private void defeatWither() {
    check(wither.getLocation().getX() < -128, "Wither confined to encounter");
    group.guardianLoot.put(witherSpec.token, List.of(new Slot(0, Loot.encode(new ItemStack(Material.DIAMOND)))));
    List<ItemStack> drops = new ArrayList<>();
    Listener listener = new Listener() {
      @EventHandler(priority = EventPriority.MONITOR)
      public void died(EntityDeathEvent event) {
        if (event.getEntity().equals(wither)) drops.addAll(event.getDrops());
      }
    };
    Bukkit.getPluginManager().registerEvents(listener, plugin);
    wither.setHealth(0);
    plugin.await(() -> witherSpec.defeated, () -> {
      HandlerList.unregisterAll(listener);
      check(drops.stream().anyMatch(item -> item.getType() == Material.NETHER_STAR), "Wither vanilla drops retained when enabled");
      check(drops.stream().anyMatch(item -> item.getType() == Material.DIAMOND), "Wither custom drops applied");
      for (int i = 0; i < 3; i++) {
        LivingEntity entity = mob(i);
        plugin.events.died(entity, new ArrayList<>());
        entity.remove();
      }
      plugin.await(() -> group.stage == Stage.UNLOCKED, () -> {
        check(!plugin.events.protectedBlock(plugin.world.getBlockAt(-140, 90, -140)), "Wither defeat participates in encounter unlock");
        prepareRestart();
      }, 100);
    }, 100);
  }

  private void prepareRestart() {
    Drop restart = new Drop();
    restart.profileId = "wither-restart";
    restart.profile = new Profile();
    restart.world = group.world;
    restart.origin = group.origin;
    restart.stage = Stage.GUARDED;
    restart.glowRevealed = true;
    Guardian guardian = new Guardian();
    guardian.spec = Store.copy(witherSpec.spec, MobSpec.class);
    guardian.health = 42;
    restart.guardians.add(guardian);
    plugin.events.data.drops.put(restart.id, restart);
    plugin.await(() -> guardian.entityId != null, () -> {
      check(((Wither) Bukkit.getEntity(UUID.fromString(guardian.entityId))).getInvulnerableTicks() == 0,
          "Saved-health wither prepared without charge for restart");
      plugin.world.save();
      plugin.store.save("runtime", plugin.events.data).join();
      done.run();
    }, 100);
  }

  static void verifyRestart(IntegrationPlugin plugin, Runnable done) {
    Drop restart = plugin.events.data.drops.values().stream()
        .filter(drop -> "wither-restart".equals(drop.profileId)).findFirst().orElseThrow();
    Guardian guardian = restart.guardians.getFirst();
    Wither wither = (Wither) Bukkit.getEntity(UUID.fromString(guardian.entityId));
    plugin.check(wither != null && wither.isValid(), "Existing wither recovered after actual server restart");
    plugin.check(wither.getInvulnerableTicks() == 0, "Server restart does not restart wither charge");
    plugin.check(wither.isGlowing(), "Wither glow survives actual server restart");
    plugin.check(wither.getAttribute(Attribute.MAX_HEALTH).getBaseValue() == 90
        && wither.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue() == .45,
        "Wither attributes survive actual server restart");
    plugin.check(wither.getHealth() >= 42 && wither.getHealth() <= 90, "Wither retains damaged health and normal regeneration after restart");
    plugin.events.unlock(restart, true);
    plugin.later(5, () -> {
      plugin.check(Bukkit.getEntity(UUID.fromString(guardian.entityId)) == null, "Forced unlock removes recovered wither");
      done.run();
    });
  }

  private void reject(Runnable action, String message) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { check(true, message); return; }
    throw new AssertionError(message);
  }

  private void check(boolean condition, String message) { plugin.check(condition, message); }
}
