package dev.supplydrops;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;

public final class Protection implements Listener {
  private final Events events;

  public Protection(Events events) {
    this.events = events;
  }

  private boolean locked(Inventory inv) {
    if (inv.getHolder() instanceof BlockState state) return events.protectedBlock(state.getBlock());
    if (inv.getHolder() instanceof DoubleChest chest)
      return events.protectedBlock(chest.getLocation().getBlock());
    return inv.getLocation() != null && events.protectedBlock(inv.getLocation().getBlock());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void transport(ItemTransportingEntityValidateTargetEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setAllowed(false);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void multiPlace(BlockMultiPlaceEvent e) {
    if (e.getReplacedBlockStates().stream().anyMatch(s -> events.protectedBlock(s.getBlock())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (e.getClickedBlock() != null && events.protectedBlock(e.getClickedBlock()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucket(PlayerBucketEmptyEvent e) {
    if (events.protectedBlock(e.getBlock()) || events.protectedBlock(e.getBlockClicked()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucketFill(PlayerBucketFillEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    if (locked(e.getInventory())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void move(InventoryMoveItemEvent e) {
    if (locked(e.getSource()) || locked(e.getDestination())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explode(EntityExplodeEvent e) {
    e.blockList().removeIf(events::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explode(BlockExplodeEvent e) {
    e.blockList().removeIf(events::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void extend(BlockPistonExtendEvent e) {
    if (events.protectedBlock(e.getBlock())
        || e.getBlocks().stream()
            .anyMatch(
                b ->
                    events.protectedBlock(b)
                        || events.protectedBlock(b.getRelative(e.getDirection()))))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void retract(BlockPistonRetractEvent e) {
    if (events.protectedBlock(e.getBlock())
        || e.getBlocks().stream()
            .anyMatch(
                b ->
                    events.protectedBlock(b)
                        || events.protectedBlock(b.getRelative(e.getDirection()))))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void flow(BlockFromToEvent e) {
    if (events.protectedBlock(e.getToBlock()) || events.protectedBlock(e.getBlock()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void burn(BlockBurnEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void fade(BlockFadeEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void grow(BlockGrowEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void spread(BlockSpreadEvent e) {
    if (events.protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void structure(StructureGrowEvent e) {
    if (e.getBlocks().stream().anyMatch(b -> events.protectedBlock(b.getBlock())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void change(EntityChangeBlockEvent e) {
    if (events.owner(e.getEntity()) != null || events.protectedBlock(e.getBlock()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    var d = events.owner(e.getEntity());
    if (d == null) return;
    if (e.getEntity() instanceof FallingBlock || e.getEntity() instanceof BlockDisplay) {
      e.setCancelled(true);
      return;
    }
    if (events.guardian(e.getEntity()) != null) {
      // Only combat kills can advance the encounter; terrain cannot silently defeat it.
      boolean combat =
          e instanceof EntityDamageByEntityEvent by
              && (by.getDamager() instanceof Player
                  || by.getDamager() instanceof Projectile projectile
                      && projectile.getShooter() instanceof Player
                  || by.getDamager() instanceof Tameable tame && tame.getOwner() != null);
      if (!combat) e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void target(EntityTargetLivingEntityEvent e) {
    if (events.guardian(e.getEntity()) != null
        && e.getTarget() != null
        && !(e.getTarget() instanceof Player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void transform(EntityTransformEvent e) {
    if (events.guardian(e.getEntity()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(EntityPortalEvent e) {
    if (events.owner(e.getEntity()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void death(EntityDeathEvent e) {
    if (events.guardian(e.getEntity()) != null) {
      if (!events.guardian(e.getEntity()).spec.vanillaDrops) e.setDroppedExp(0);
      events.died(e.getEntity(), e.getDrops());
    }
  }

  @EventHandler
  public void loaded(EntitiesLoadEvent e) {
    e.getEntities().forEach(events::reconcileEntity);
  }
}
