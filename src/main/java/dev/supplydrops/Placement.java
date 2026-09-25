package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Chest;

public final class Placement {
  private Placement() {}

  public record Site(Pos origin, List<Cell> cells, int fallDistance, String error) {
    static Site rejected(String error) {
      return new Site(null, List.of(), 0, error);
    }

    public boolean valid() {
      return error == null;
    }
  }

  public static List<Cell> cells(Config config, Profile p, RandomGenerator rng, String previous) {
    List<Pile.Block> layout = List.of();
    for (int attempt = 0; attempt < 8; attempt++) {
      List<String> materials = new ArrayList<>();
      for (int n = Weighted.between(p.minRolls, p.maxRolls, rng); n > 0; n--)
        materials.add(
            Weighted.pick(config.blocks.get(p.blockTable).entries, e -> e.weight, rng).material);
      layout = Pile.generate(materials, rng);
      if (!Pile.signature(layout).equals(previous)) break;
    }
    List<Cell> cells = new ArrayList<>();
    BlockFace[] directions = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    for (Pile.Block b : layout) {
      BlockData data = Material.valueOf(b.material()).createBlockData();
      if (data instanceof Directional d) {
        BlockFace face =
            b.material().endsWith("SHULKER_BOX") ? BlockFace.UP : directions[rng.nextInt(4)];
        if (d.getFaces().contains(face)) d.setFacing(face);
      }
      if (data instanceof Chest c) c.setType(Chest.Type.SINGLE);
      Cell cell = new Cell(b.pos(), data.getAsString());
      if (Loot.container(b.material()))
        cell.loot = Loot.roll(config.items.get(p.containers.get(b.material())), rng);
      cells.add(cell);
    }
    return cells;
  }

  public static String signature(List<Cell> cells) {
    return Pile.signature(
        cells.stream()
            .map(
                c ->
                    new Pile.Block(c.pos, Bukkit.createBlockData(c.blockData).getMaterial().name()))
            .toList());
  }

  /** Stable, dry support includes gravity blocks such as sand and gravel. */
  public static boolean natural(Block b) {
    Material m = b.getType();
    return m.isSolid()
        && !b.isLiquid()
        && !m.name().contains("LEAVES")
        && !m.name().contains("LOG")
        && !m.name().contains("WOOD")
        && !m.name().contains("MUSHROOM")
        && !(b.getState() instanceof TileState);
  }

  /** Only harmless, non-colliding blocks may be displaced by the final pile. */
  public static boolean soft(Block b) {
    if (b.getType().isAir()) return true;
    if (b.isLiquid() || b.getState() instanceof TileState) return false;
    return b.getBlockData().isReplaceable() || b.getCollisionShape().getBoundingBoxes().isEmpty();
  }

  private static long columnKey(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  /** Uses one heightmap lookup per footprint column instead of scanning to build height. */
  public static Site site(World world, Pos origin, List<Cell> layout, boolean adapt) {
    if (origin == null) return Site.rejected("Outside world border");
    Map<Long, Integer> surface = new HashMap<>();
    int anchorSurface =
        world.getHighestBlockYAt(origin.x(), origin.z(), HeightMap.MOTION_BLOCKING) + 1;
    if (origin.y() != anchorSurface) return Site.rejected("Base Y differs from surface");
    if (!natural(world.getBlockAt(origin.x(), anchorSurface - 1, origin.z())))
      return Site.rejected("Requires dry solid ground");
    surface.put(columnKey(origin.x(), origin.z()), anchorSurface);
    int minimum = anchorSurface, maximum = anchorSurface;
    for (Cell c : layout) {
      int x = origin.x() + c.pos.x(), z = origin.z() + c.pos.z();
      if (!world.getWorldBorder().isInside(new Location(world, x + .5, origin.y(), z + .5)))
        return Site.rejected("Outside world border");
      long key = columnKey(x, z);
      if (!surface.containsKey(key)) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) + 1;
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1)
          return Site.rejected("Insufficient sky clearance");
        if (!natural(world.getBlockAt(x, y - 1, z)))
          return Site.rejected("Requires dry solid ground");
        surface.put(key, y);
        minimum = Math.min(minimum, y);
        maximum = Math.max(maximum, y);
      }
    }
    if (maximum - minimum > 2) return Site.rejected("Terrain varies by more than two blocks");
    if (origin.y() != surface.get(columnKey(origin.x(), origin.z())))
      return Site.rejected("Base Y differs from surface");
    List<Cell> adjusted = new ArrayList<>();
    Set<Pos> planned = new HashSet<>();
    int highest = Integer.MIN_VALUE;
    for (Cell c : layout) {
      int ground = surface.get(columnKey(origin.x() + c.pos.x(), origin.z() + c.pos.z()));
      int delta = adapt ? ground - origin.y() : 0;
      Pos next = c.pos.add(0, delta, 0);
      if (!planned.add(next)) return Site.rejected("Pile overlaps itself on this slope");
      Cell copy = new Cell(next, c.blockData);
      copy.loot = new ArrayList<>(c.loot);
      adjusted.add(copy);
      highest = Math.max(highest, origin.y() + next.y());
    }
    if (highest >= world.getMaxHeight() - 1) return Site.rejected("Insufficient sky clearance");
    if (!connected(planned)) return Site.rejected("Slope disconnects the pile");
    for (Cell c : adjusted) {
      Block target =
          world.getBlockAt(origin.x() + c.pos.x(), origin.y() + c.pos.y(), origin.z() + c.pos.z());
      if (!soft(target)) return Site.rejected("Landing cell is obstructed");
      if (!planned.contains(c.pos.add(0, -1, 0)) && !natural(target.getRelative(BlockFace.DOWN)))
        return Site.rejected("Requires dry solid ground");
      if (Loot.needsClearTop(Bukkit.createBlockData(c.blockData).getMaterial().name())) {
        Block above = target.getRelative(BlockFace.UP);
        if (!planned.contains(c.pos.add(0, 1, 0)) && !soft(above))
          return Site.rejected("Container opening is obstructed");
      }
    }
    return new Site(origin, adjusted, world.getMaxHeight() - 1 - highest, null);
  }

  public static boolean connected(Set<Pos> cells) {
    if (cells.isEmpty()) return false;
    Set<Pos> seen = new HashSet<>();
    ArrayDeque<Pos> queue = new ArrayDeque<>();
    queue.add(cells.iterator().next());
    while (!queue.isEmpty()) {
      Pos p = queue.remove();
      if (!seen.add(p)) continue;
      for (Pos q :
          List.of(
              p.add(1, 0, 0),
              p.add(-1, 0, 0),
              p.add(0, 0, 1),
              p.add(0, 0, -1),
              p.add(0, 1, 0),
              p.add(0, -1, 0))) if (cells.contains(q) && !seen.contains(q)) queue.add(q);
    }
    return seen.size() == cells.size();
  }

  public static String check(World world, Pos origin, List<Cell> cells, int ignoredHeight) {
    return site(world, origin, cells, true).error();
  }

  public static Pos random(World world, int radius, RandomGenerator rng) {
    double r = radius * Math.sqrt(rng.nextDouble()), angle = rng.nextDouble(Math.PI * 2);
    int x = (int) Math.floor(r * Math.cos(angle)), z = (int) Math.floor(r * Math.sin(angle));
    if (!world
        .getWorldBorder()
        .isInside(new Location(world, x + .5, world.getMinHeight() + 1, z + .5))) return null;
    int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) + 1;
    return new Pos(x, y, z);
  }

  public static Pos random(World world, Profile p, RandomGenerator rng) {
    return random(world, p.radius, rng);
  }

  public static Block block(World w, Drop d, Cell c) {
    return w.getBlockAt(
        d.origin.x() + c.pos.x(), d.origin.y() + c.pos.y(), d.origin.z() + c.pos.z());
  }
}
