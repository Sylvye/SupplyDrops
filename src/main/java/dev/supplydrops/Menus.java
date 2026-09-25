package dev.supplydrops;

import static dev.supplydrops.Model.*;

import dev.supplydrops.Model.Effect;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.*;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/** Session-bound menus use isolated drafts. No UI item can leave its inventory. */
public final class Menus implements Listener {
  private final SupplyDropsPlugin plugin;
  private final Map<UUID, Session> sessions = new HashMap<>();
  private final Set<UUID> pendingDialogs = new HashSet<>();
  private boolean closing;

  private static final class Session {
    Config draft;
    long revision;
    UUID token = UUID.randomUUID();
    boolean saving;
    String baseline;
    Map<String, String> blockFilters = new HashMap<>();

    Session(Configuration c) {
      draft = Store.copy(c.current, Config.class);
      baseline = Store.JSON.toJson(draft);
      revision = c.revision;
    }
  }

  private record Button(ItemStack icon, Runnable action) {}

  private static final class View implements InventoryHolder {
    Inventory inventory;
    Map<Integer, Runnable> actions = new HashMap<>();
    UUID player;
    String permission;

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  public Menus(SupplyDropsPlugin plugin) {
    this.plugin = plugin;
  }

  public static Component text(String value) {
    return Component.text(value, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
  }

  private ItemStack icon(Material material, String title, String... lore) {
    ItemStack item = new ItemStack(material);
    item.editMeta(
        m -> {
          m.displayName(text(title));
          m.lore(
              Arrays.stream(lore)
                  .map(
                      s ->
                          Component.text(s, NamedTextColor.GRAY)
                              .decoration(TextDecoration.ITALIC, false))
                  .toList());
        });
    return item;
  }

  private static final net.kyori.adventure.text.format.TextColor PINK =
      net.kyori.adventure.text.format.TextColor.color(0xF7A8D8);

  private Button weighted(
      Material material,
      String name,
      double weight,
      String chance,
      Runnable action,
      String... extra) {
    ItemStack item = icon(material, name, extra);
    item.editMeta(
        meta -> {
          List<Component> lore = new ArrayList<>(Objects.requireNonNull(meta.lore()));
          lore.add(
              Component.text("Weight: " + weight, NamedTextColor.GOLD)
                  .decoration(TextDecoration.ITALIC, false));
          lore.add(
              Component.text("Chance: " + chance, PINK)
                  .decoration(TextDecoration.ITALIC, false));
          meta.lore(lore);
        });
    return new Button(item, action);
  }

  private <T> void weightField(
      Player p,
      List<Button> list,
      T entry,
      List<T> entries,
      java.util.function.ToDoubleFunction<T> weight,
      Runnable refresh) {
    double current = weight.applyAsDouble(entry);
    list.add(
        weighted(
            Material.COMPARATOR,
            "Weight / chance",
            current,
            Chance.percent(entry, entries, weight),
            () ->
                input(
                    p,
                    "Weight",
                    String.valueOf(current),
                    v -> {
                      double next = Double.parseDouble(v);
                      Validation.weight(next);
                      try {
                        entry.getClass().getField("weight").setDouble(entry, next);
                      } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(error);
                      }
                      refresh.run();
                    },
                    refresh)));
  }

  private Button button(Material icon, String label, Runnable action, String... lore) {
    return new Button(icon(icon, label, lore), action);
  }

  private Session session(Player p) {
    return sessions.computeIfAbsent(p.getUniqueId(), id -> new Session(plugin.config));
  }

  public void open(Player p) {
    if (!p.hasPermission("supplydrops.edit")) {
      active(p);
      return;
    }
    session(p);
    dashboard(p);
  }

  private void show(
      Player p, String title, List<Button> buttons, int page, Runnable back, boolean edit) {
    View view = new View();
    view.player = p.getUniqueId();
    view.permission = edit ? "supplydrops.edit" : "supplydrops.view";
    view.inventory = Bukkit.createInventory(view, 54, text("SupplyDrops › " + title));
    for (int i = 45; i < 54; i++)
      view.inventory.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " "));
    int pages = Math.max(1, (buttons.size() + 44) / 45);
    int current = Math.clamp(page, 0, pages - 1);
    for (int i = current * 45; i < Math.min(buttons.size(), current * 45 + 45); i++)
      put(view, i - current * 45, buttons.get(i));
    if (back != null) put(view, 45, button(Material.ARROW, "Back", back));
    if (current > 0)
      put(
          view,
          46,
          button(
              Material.PAPER,
              "Previous page",
              () -> show(p, title, buttons, current - 1, back, edit)));
    if (current + 1 < pages)
      put(
          view,
          52,
          button(
              Material.PAPER, "Next page", () -> show(p, title, buttons, current + 1, back, edit)));
    view.inventory.setItem(
        49,
        icon(
            Material.COMPASS,
            (current + 1) + " / " + pages,
            edit ? "Draft • changes apply only after Save" : "Live events"));
    if (edit) {
      put(view, 48, button(Material.LIME_DYE, "Save changes", () -> save(p)));
      put(
          view,
          50,
          button(
              Material.RED_DYE,
              "Discard & close",
              () ->
                  confirm(
                      p,
                      "Discard all draft changes?",
                      () -> {
                        sessions.remove(p.getUniqueId());
                        p.closeInventory();
                      },
                      () -> dashboard(p))));
    }
    p.openInventory(view.inventory);
  }

  private void put(View v, int slot, Button b) {
    v.inventory.setItem(slot, b.icon());
    v.actions.put(slot, b.action());
  }

  private void save(Player p) {
    if (!p.hasPermission("supplydrops.edit")) return;
    Session s = session(p);
    if (s.saving) return;
    try {
      for (BlockTable t : s.draft.blocks.values())
        if (!t.entries.isEmpty()) Validation.blockTable(t);
      for (ItemTable t : s.draft.items.values()) if (!t.entries.isEmpty()) Validation.itemTable(t);
      for (EncounterTable t : s.draft.guardians.values())
        if (!t.entries.isEmpty()) Validation.encounters(t, s.draft);
    } catch (Exception e) {
      message(p, e.getMessage());
      return;
    }
    s.saving = true;
    plugin.config.commit(
        s.draft,
        s.revision,
        error -> {
          s.saving = false;
          if (error != null) message(p, error);
          else {
            sessions.remove(p.getUniqueId());
            message(p, "Changes saved.");
            open(p);
          }
        });
  }

  public static void message(Player p, String message) {
    p.sendMessage(text("SupplyDrops • " + message));
  }

  private void dashboard(Player p) {
    List<Button> list = new ArrayList<>();
    list.add(
        button(
            Material.CHEST,
            "Profiles",
            () -> listing(p, "profiles"),
            "Worlds, tables & schedules"));
    list.add(
        button(
            Material.GOLD_BLOCK,
            "Block Tables",
            () -> listing(p, "blocks"),
            "Weighted materials and containers"));
    list.add(
        button(
            Material.DIAMOND,
            "Item Tables",
            () -> listing(p, "items"),
            "Copy items • weights • quantities"));
    list.add(
        button(
            Material.IRON_SWORD,
            "Guardian Encounters",
            () -> listing(p, "guardians"),
            "Weighted groups of customized mobs"));
    list.add(
        button(
            Material.CLOCK,
            "Active Events",
            () -> active(p),
            "Inspect, spawn, retry, unlock & cancel"));
    list.add(button(Material.COMPARATOR, "Settings", () -> settings(p), "Global drop behavior"));
    show(p, "Dashboard", list, 0, null, true);
  }

  private Map<String, ?> map(Config c, String kind) {
    return switch (kind) {
      case "profiles" -> c.profiles;
      case "blocks" -> c.blocks;
      case "items" -> c.items;
      default -> c.guardians;
    };
  }

  private String name(Object obj) {
    try {
      return (String) obj.getClass().getField("name").get(obj);
    } catch (Exception e) {
      return "Entry";
    }
  }

  private void rename(Object obj, String value) {
    try {
      Validation.require(
          !value.isBlank() && value.length() <= 48, "Name must contain 1–48 characters");
      obj.getClass().getField("name").set(obj, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private Material kindIcon(String kind) {
    return switch (kind) {
      case "profiles" -> Material.CHEST;
      case "blocks" -> Material.GOLD_BLOCK;
      case "items" -> Material.DIAMOND;
      default -> Material.IRON_SWORD;
    };
  }

  private void listing(Player p, String kind) {
    Session s = session(p);
    List<Button> list = new ArrayList<>();
    map(s.draft, kind)
        .forEach(
            (id, obj) ->
                list.add(
                    button(kindIcon(kind), name(obj), () -> detail(p, kind, id), "ID: " + id)));
    list.add(
        button(
            Material.LIME_DYE,
            "Create",
            () ->
                input(
                    p,
                    "Name",
                    "New " + kind,
                    value -> {
                      String id = UUID.randomUUID().toString().substring(0, 8);
                      Object obj;
                      switch (kind) {
                        case "profiles" -> {
                          Profile profile = new Profile();
                          profile.worlds.add(p.getWorld().getName());
                          s.draft.profiles.put(id, profile);
                          obj = profile;
                        }
                        case "blocks" -> {
                          BlockTable t = new BlockTable();
                          s.draft.blocks.put(id, t);
                          obj = t;
                        }
                        case "items" -> {
                          ItemTable t = new ItemTable();
                          s.draft.items.put(id, t);
                          obj = t;
                        }
                        default -> {
                          EncounterTable t = new EncounterTable();
                          s.draft.guardians.put(id, t);
                          obj = t;
                        }
                      }
                      rename(obj, value);
                      detail(p, kind, id);
                    },
                    () -> listing(p, kind))));
    show(p, kind, list, 0, () -> dashboard(p), true);
  }

  private void detail(Player p, String kind, String id) {
    Object obj = map(session(p).draft, kind).get(id);
    if (obj == null) {
      listing(p, kind);
      return;
    }
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> detail(p, kind, id);
    list.add(
        button(
            Material.NAME_TAG,
            "Rename • " + name(obj),
            () ->
                input(
                    p,
                    "Display name",
                    name(obj),
                    v -> {
                      rename(obj, v);
                      refresh.run();
                    },
                    refresh)));
    list.add(
        button(
            Material.PAPER,
            "Duplicate",
            () ->
                input(
                    p,
                    "Copy name",
                    name(obj) + " copy",
                    v -> {
                      duplicate(p, kind, id, v);
                      listing(p, kind);
                    },
                    refresh)));
    list.add(
        button(
            Material.BARRIER,
            "Delete",
            () ->
                confirm(
                    p,
                    "Delete " + name(obj) + "?",
                    () -> {
                      delete(p, kind, id);
                      listing(p, kind);
                    },
                    refresh)));
    switch (kind) {
      case "profiles" -> profileButtons(p, id, (Profile) obj, list, refresh);
      case "blocks" -> {
        BlockTable t = (BlockTable) obj;
        String filter = session(p).blockFilters.getOrDefault(id, "");
        list.add(
            button(
                Material.SPYGLASS,
                "Search blocks • " + filter,
                () ->
                    input(
                        p,
                        "Search block table",
                        filter,
                        v -> {
                          session(p).blockFilters.put(id, v);
                          refresh.run();
                        },
                        refresh)));
        if (!filter.isEmpty())
          list.add(
              button(
                  Material.BARRIER,
                  "Clear search",
                  () -> {
                    session(p).blockFilters.remove(id);
                    refresh.run();
                  }));
        for (BlockEntry e : t.entries)
          if (e.material.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
            Material m = Material.matchMaterial(e.material);
            list.add(
                weighted(
                    m != null && m.isItem() ? m : Material.STONE,
                    e.material,
                    e.weight,
                    Chance.percent(e, t.entries, v -> v.weight),
                    () -> blockEntry(p, t, e, refresh)));
          }
        list.add(
            button(
                Material.LIME_DYE,
                "Add from block catalog",
                () ->
                    catalog(
                        p,
                        "Block material",
                        Arrays.stream(Material.values())
                            .filter(m -> m.isBlock() && m.isSolid() && !m.hasGravity())
                            .map(Enum::name)
                            .toList(),
                        v -> {
                          t.entries.add(new BlockEntry(Loot.normalize(v), 1));
                          refresh.run();
                        },
                        refresh)));
        list.add(
            button(
                Material.CHEST,
                "Add from inventory",
                () ->
                    pickItem(
                        p,
                        item -> {
                          t.entries.add(new BlockEntry(Loot.normalize(item.getType().name()), 1));
                          refresh.run();
                        },
                        refresh,
                        Menus::validBlockItem),
                "Copies the block type without consuming your item"));
      }
      case "items" -> {
        ItemTable t = (ItemTable) obj;
        field(p, list, t, "minRolls", "Minimum rolls", refresh);
        field(p, list, t, "maxRolls", "Maximum rolls", refresh);
        for (ItemEntry e : t.entries) {
          ItemStack icon = Loot.decode(e.item);
          icon.editMeta(
              m ->
                  m.lore(
                      List.of(
                          Component.text("Weight: " + e.weight, NamedTextColor.GOLD)
                              .decoration(TextDecoration.ITALIC, false),
                          Component.text(
                                  "Chance: " + Chance.percent(e, t.entries, v -> v.weight), PINK)
                              .decoration(TextDecoration.ITALIC, false),
                          Component.text("Amount " + e.min + "–" + e.max, NamedTextColor.GRAY)
                              .decoration(TextDecoration.ITALIC, false))));
          list.add(new Button(icon, () -> itemEntry(p, t, e, refresh)));
        }
        list.add(
            button(
                Material.LIME_DYE,
                "Copy inventory item",
                () ->
                    pickItem(
                        p,
                        item -> {
                          t.entries.add(new ItemEntry(Loot.encode(item)));
                          refresh.run();
                        },
                        refresh),
                "Your source item is never consumed"));
      }
      default -> {
        EncounterTable t = (EncounterTable) obj;
        for (Encounter e : t.entries)
          list.add(
              weighted(
                  Material.IRON_SWORD,
                  e.name,
                  e.weight,
                  Chance.percent(e, t.entries, v -> v.weight),
                  () -> encounter(p, t, e, refresh),
                  e.mobs.size() + " mob groups"));
        list.add(
            button(
                Material.LIME_DYE,
                "Add encounter",
                () ->
                    input(
                        p,
                        "Encounter name",
                        "EMPTY",
                        v -> {
                          Encounter e = new Encounter();
                          e.name = v;
                          t.entries.add(e);
                          refresh.run();
                        },
                        refresh)));
      }
    }
    show(p, kind + " › " + name(obj), list, 0, () -> listing(p, kind), true);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void duplicate(Player p, String kind, String id, String name) {
    Map map = map(session(p).draft, kind);
    Object old = map.get(id), copy = Store.JSON.fromJson(Store.JSON.toJson(old), old.getClass());
    rename(copy, name);
    if (copy instanceof Profile profile) {
      profile.enabled = false;
      profile.scheduled = false;
    }
    map.put(UUID.randomUUID().toString().substring(0, 8), copy);
  }

  private void delete(Player p, String kind, String id) {
    Config c = session(p).draft;
    boolean used =
        switch (kind) {
          case "blocks" -> c.profiles.values().stream().anyMatch(v -> v.blockTable.equals(id));
          case "guardians" ->
              c.profiles.values().stream().anyMatch(v -> v.guardianTable.equals(id));
          case "items" ->
              c.profiles.values().stream().anyMatch(v -> v.containers.containsValue(id))
                  || c.guardians.values().stream()
                      .flatMap(t -> t.entries.stream())
                      .flatMap(e -> e.mobs.stream())
                      .anyMatch(m -> m.dropTable.equals(id));
          default -> false;
        };
    Validation.require(!used, "Reassign references before deleting this table");
    map(c, kind).remove(id);
  }

  private void profileButtons(
      Player p, String id, Profile profile, List<Button> list, Runnable refresh) {
    for (String f :
        List.of("enabled", "scheduled", "minRolls", "maxRolls", "intervalMinutes", "minPlayers"))
      field(p, list, profile, f, label(f), refresh);
    list.add(
        button(
            Material.GRASS_BLOCK,
            "Worlds • " + String.join(", ", profile.worlds),
            () -> worlds(p, profile, refresh)));
    list.add(
        button(
            Material.GOLD_BLOCK,
            "Block table • " + profile.blockTable,
            () ->
                choose(
                    p,
                    "blocks",
                    v -> {
                      profile.blockTable = v;
                      refresh.run();
                    },
                    refresh)));
    list.add(
        button(
            Material.IRON_SWORD,
            "Guardian table • " + profile.guardianTable,
            () ->
                choose(
                    p,
                    "guardians",
                    v -> {
                      profile.guardianTable = v;
                      refresh.run();
                    },
                    refresh)));
    list.add(button(Material.BARREL, "Container assignments", () -> mappings(p, profile, refresh)));
    list.add(
        button(
            Material.EMERALD,
            "Validate profile",
            () -> {
              Validation.profile(profile, session(p).draft);
              message(p, "Profile is valid.");
            }));
    if (p.hasPermission("supplydrops.spawn"))
      list.add(
          button(
              Material.FIREWORK_ROCKET,
              "Spawn saved profile",
              () -> {
                Validation.require(
                    p.hasPermission("supplydrops.spawn"), "Missing spawn permission");
                plugin.events.spawn(id, null, msg -> message(p, msg));
              },
              "Uses saved settings; save this draft first"));
  }

  private String label(String name) {
    if (name.equals("speed")) return "Fall speed (blocks / second)";
    if (name.equals("radius")) return "Radius from 0, 0 (blocks)";
    if (name.equals("announce")) return "Announcement • MiniMessage";
    return Character.toUpperCase(name.charAt(0))
        + name.substring(1).replaceAll("([A-Z])", " $1").toLowerCase(Locale.ROOT);
  }

  private void field(
      Player p, List<Button> list, Object obj, String key, String label, Runnable refresh) {
    try {
      Field f = obj.getClass().getField(key);
      Object value = f.get(obj);
      list.add(
          button(
              value instanceof Boolean b
                  ? (b ? Material.LIME_DYE : Material.GRAY_DYE)
                  : Material.COMPARATOR,
              label + " • " + value,
              () -> {
                if (value instanceof Boolean) {
                  try {
                    f.set(obj, !(boolean) f.get(obj));
                    refresh.run();
                  } catch (Exception ex) {
                    message(p, ex.getMessage());
                  }
                } else
                  input(
                      p,
                      label,
                      String.valueOf(value),
                      v -> {
                        try {
                          Object parsed =
                              f.getType() == int.class
                                  ? Integer.parseInt(v)
                                  : f.getType() == double.class ? Double.parseDouble(v) : v;
                          if (parsed instanceof Double n)
                            Validation.require(Double.isFinite(n), "Enter a finite number");
                          f.set(obj, parsed);
                          refresh.run();
                        } catch (ReflectiveOperationException ex) {
                          throw new IllegalArgumentException(ex.getMessage());
                        }
                      },
                      refresh);
              }));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private void worlds(Player p, Profile profile, Runnable back) {
    List<Button> list = new ArrayList<>();
    for (World world : Bukkit.getWorlds())
      list.add(
          button(
              profile.worlds.contains(world.getName()) ? Material.LIME_DYE : Material.GRAY_DYE,
              world.getName(),
              () -> {
                if (!profile.worlds.remove(world.getName())) profile.worlds.add(world.getName());
                worlds(p, profile, back);
              }));
    for (String missing : new ArrayList<>(profile.worlds))
      if (Bukkit.getWorld(missing) == null)
        list.add(
            button(
                Material.BARRIER,
                missing + " (unavailable; click to remove)",
                () -> {
                  profile.worlds.remove(missing);
                  worlds(p, profile, back);
                }));
    show(p, "Profile › Worlds", list, 0, back, true);
  }

  private void mappings(Player p, Profile profile, Runnable back) {
    List<Button> list = new ArrayList<>();
    for (String type : Loot.containers())
      list.add(
          button(
              Material.valueOf(type),
              type,
              () ->
                  choose(
                      p,
                      "items",
                      id -> {
                        profile.containers.put(type, id);
                        mappings(p, profile, back);
                      },
                      () -> mappings(p, profile, back)),
              "Table: " + profile.containers.getOrDefault(type, "Unassigned")));
    show(p, "Profile › Containers", list, 0, back, true);
  }

  private void choose(Player p, String kind, Consumer<String> selected, Runnable back) {
    List<Button> list = new ArrayList<>();
    map(session(p).draft, kind)
        .forEach(
            (id, t) ->
                list.add(button(kindIcon(kind), name(t), () -> selected.accept(id), "ID: " + id)));
    show(p, "Choose " + kind, list, 0, back, true);
  }

  private void blockEntry(Player p, BlockTable t, BlockEntry e, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> blockEntry(p, t, e, back);
    list.add(
        button(
            Material.STONE,
            e.material,
            () ->
                catalog(
                    p,
                    "Material",
                    Arrays.stream(Material.values())
                        .filter(Material::isBlock)
                        .filter(Material::isSolid)
                        .map(Enum::name)
                        .toList(),
                    v -> {
                      e.material = Loot.normalize(v);
                      refresh.run();
                    },
                    refresh)));
    weightField(p, list, e, t.entries, v -> v.weight, refresh);
    list.add(
        button(
            Material.BARRIER,
            "Remove entry",
            () -> {
              t.entries.remove(e);
              back.run();
            }));
    show(p, "Block entry", list, 0, back, true);
  }

  private void itemEntry(Player p, ItemTable t, ItemEntry e, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> itemEntry(p, t, e, back);
    weightField(p, list, e, t.entries, v -> v.weight, refresh);
    for (String f : List.of("min", "max")) field(p, list, e, f, label(f), refresh);
    list.add(
        button(
            Material.CHEST,
            "Replace copied item",
            () ->
                pickItem(
                    p,
                    item -> {
                      e.item = Loot.encode(item);
                      refresh.run();
                    },
                    refresh)));
    list.add(
        button(
            Material.BARRIER,
            "Remove entry",
            () -> {
              t.entries.remove(e);
              back.run();
            }));
    show(p, "Item entry", list, 0, back, true);
  }

  private void encounter(Player p, EncounterTable t, Encounter e, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> encounter(p, t, e, back);
    field(p, list, e, "name", "Name", refresh);
    weightField(p, list, e, t.entries, v -> v.weight, refresh);
    for (MobSpec m : e.mobs)
      list.add(button(Material.ZOMBIE_HEAD, m.type + " × " + m.count, () -> mob(p, e, m, refresh)));
    list.add(
        button(
            Material.LIME_DYE,
            "Add mob group",
            () ->
                catalog(
                    p,
                    "Mob type",
                    Arrays.stream(EntityType.values())
                        .filter(EntityType::isSpawnable)
                        .filter(
                            type ->
                                type.getEntityClass() != null
                                    && Mob.class.isAssignableFrom(type.getEntityClass()))
                        .map(Enum::name)
                        .toList(),
                    v -> {
                      MobSpec m = new MobSpec();
                      m.type = v;
                      e.mobs.add(m);
                      refresh.run();
                    },
                    refresh)));
    list.add(
        button(
            Material.BARRIER,
            "Remove encounter",
            () -> {
              t.entries.remove(e);
              back.run();
            }));
    show(p, "Encounter › " + e.name, list, 0, back, true);
  }

  private void mob(Player p, Encounter e, MobSpec m, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> mob(p, e, m, back);
    for (String f : List.of("name", "count", "health", "radius", "vanillaDrops"))
      field(p, list, m, f, label(f) + (f.equals("health") ? " (0 = vanilla)" : ""), refresh);
    list.add(button(Material.DIAMOND_CHESTPLATE, "Equipment", () -> equipment(p, m, refresh)));
    list.add(button(Material.POTION, "Potion effects", () -> effects(p, m, refresh)));
    list.add(
        button(
            Material.DIAMOND,
            "Custom drops • " + m.dropTable,
            () ->
                choose(
                    p,
                    "items",
                    id -> {
                      m.dropTable = id;
                      refresh.run();
                    },
                    refresh)));
    list.add(
        button(
            Material.BUCKET,
            "Clear custom drops",
            () -> {
              m.dropTable = "";
              refresh.run();
            }));
    list.add(
        button(
            Material.BARRIER,
            "Remove mob group",
            () -> {
              e.mobs.remove(m);
              back.run();
            }));
    show(p, "Guardian › " + m.type, list, 0, back, true);
  }

  private void equipment(Player p, MobSpec m, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> equipment(p, m, back);
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      list.add(
          button(
              Material.ARMOR_STAND,
              slot.name() + " • copy item",
              () ->
                  pickItem(
                      p,
                      item -> {
                        m.equipment.put(slot.name(), Loot.encode(item));
                        refresh.run();
                      },
                      refresh),
              m.equipment.containsKey(slot.name()) ? "Assigned" : "Vanilla default"));
      if (m.equipment.containsKey(slot.name()))
        list.add(
            button(
                Material.BARRIER,
                "Clear " + slot,
                () -> {
                  m.equipment.remove(slot.name());
                  refresh.run();
                }));
    }
    show(p, "Guardian › Equipment", list, 0, back, true);
  }

  private void effects(Player p, MobSpec m, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> effects(p, m, back);
    for (Effect effect : m.effects)
      list.add(
          button(
              Material.POTION,
              effect.type + " " + (effect.amplifier + 1),
              () -> effect(p, m, effect, refresh)));
    list.add(
        button(
            Material.LIME_DYE,
            "Add effect",
            () ->
                catalog(
                    p,
                    "Effect",
                    Registry.EFFECT.stream().map(t -> t.getKey().toString()).sorted().toList(),
                    v -> {
                      Effect f = new Effect();
                      f.type = v;
                      m.effects.add(f);
                      refresh.run();
                    },
                    refresh)));
    show(p, "Guardian › Effects", list, 0, back, true);
  }

  private void effect(Player p, MobSpec m, Effect effect, Runnable back) {
    List<Button> list = new ArrayList<>();
    Runnable refresh = () -> effect(p, m, effect, back);
    field(p, list, effect, "amplifier", "Amplifier (0 = level I)", refresh);
    field(p, list, effect, "seconds", "Duration seconds", refresh);
    list.add(
        button(
            Material.BARRIER,
            "Remove effect",
            () -> {
              m.effects.remove(effect);
              back.run();
            }));
    show(p, effect.type, list, 0, back, true);
  }

  private static boolean validBlockItem(ItemStack item) {
    if (item == null || !item.getType().isBlock()) return false;
    try {
      BlockTable table = new BlockTable();
      table.entries.add(new BlockEntry(Loot.normalize(item.getType().name()), 1));
      Validation.blockTable(table);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private void pickItem(Player p, Consumer<ItemStack> selected, Runnable back) {
    pickItem(p, selected, back, item -> true);
  }

  private void pickItem(
      Player p, Consumer<ItemStack> selected, Runnable back, Predicate<ItemStack> allowed) {
    List<Button> list = new ArrayList<>();
    ItemStack[] contents = p.getInventory().getContents();
    for (int i = 0; i < contents.length; i++) {
      ItemStack original = contents[i];
      if (original == null || original.getType().isAir() || !allowed.test(original)) continue;
      ItemStack copy = original.clone();
      copy.setAmount(1);
      ItemStack visual = copy.clone();
      final int slot = i;
      visual.editMeta(
          m ->
              m.lore(List.of(text("Copy inventory slot " + slot), text("Source is not consumed"))));
      list.add(new Button(visual, () -> selected.accept(copy.clone())));
    }
    show(p, "Copy an inventory item", list, 0, back, true);
  }

  private void catalog(
      Player p, String title, List<String> values, Consumer<String> selected, Runnable back) {
    catalog(p, title, values, selected, back, "");
  }

  private void catalog(
      Player p,
      String title,
      List<String> values,
      Consumer<String> selected,
      Runnable back,
      String filter) {
    List<Button> list = new ArrayList<>();
    list.add(
        button(
            Material.SPYGLASS,
            "Search • " + filter,
            () ->
                input(
                    p,
                    "Search",
                    filter,
                    v -> catalog(p, title, values, selected, back, v),
                    () -> catalog(p, title, values, selected, back, filter))));
    for (String v : values)
      if (v.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
        Material material = Material.matchMaterial(v);
        list.add(
            button(
                material != null && material.isItem() ? material : Material.PAPER,
                v,
                () -> selected.accept(v)));
      }
    show(p, title, list, 0, back, true);
  }

  private void settings(Player p) {
    List<Button> list = new ArrayList<>();
    Config config = session(p).draft;
    field(
        p, list, config, "maxActivePerWorld", "Maximum active events per world", () -> settings(p));
    for (String f :
        List.of(
            "radius",
            "speed",
            "delaySeconds",
            "guardianGlowSeconds",
            "announce",
            "sound",
            "titles",
            "bossbar")) field(p, list, config.settings, f, label(f), () -> settings(p));
    show(p, "Settings", list, 0, () -> dashboard(p), true);
  }

  public void active(Player p) {
    if (!p.hasPermission("supplydrops.view")) {
      message(p, "Missing supplydrops.view permission");
      return;
    }
    List<Button> list = new ArrayList<>();
    for (Drop d : plugin.events.active())
      list.add(
          button(
              Material.CHEST,
              Events.shortId(d) + " • " + d.stage,
              () -> event(p, d),
              Events.coordinates(d),
              d.error));
    list.add(button(Material.CLOCK, "Refresh", () -> active(p)));
    if (p.hasPermission("supplydrops.spawn"))
      list.add(button(Material.FIREWORK_ROCKET, "Spawn profile", () -> spawnMenu(p)));
    show(
        p,
        "Active Events",
        list,
        0,
        p.hasPermission("supplydrops.edit") ? () -> dashboard(p) : null,
        false);
  }

  private void spawnMenu(Player p) {
    List<Button> list = new ArrayList<>();
    plugin.config.current.profiles.forEach(
        (id, profile) -> {
          list.add(
              button(
                  Material.FIREWORK_ROCKET,
                  profile.name + " • random location",
                  () -> {
                    Validation.require(
                        p.hasPermission("supplydrops.spawn"), "Missing spawn permission");
                    plugin.events.spawn(id, null, msg -> message(p, msg));
                    active(p);
                  }));
          list.add(
              button(
                  Material.COMPASS,
                  profile.name + " • at your location",
                  () -> {
                    Validation.require(
                        p.hasPermission("supplydrops.spawn"), "Missing spawn permission");
                    plugin.events.spawn(id, p.getLocation(), msg -> message(p, msg));
                    active(p);
                  }));
        });
    show(p, "Spawn saved profile", list, 0, () -> active(p), false);
  }

  private void event(Player p, Drop d) {
    List<Button> list = new ArrayList<>();
    list.add(
        button(
            Material.MAP,
            Events.coordinates(d),
            () -> {},
            d.stage.toString(),
            d.error,
            "Guardians remaining: " + d.guardians.stream().filter(g -> !g.defeated).count()));
    if (p.hasPermission("supplydrops.manage")) {
      list.add(
          button(
              Material.LIME_DYE,
              "Retry paused event",
              () -> {
                Validation.require(
                    p.hasPermission("supplydrops.manage"), "Missing manage permission");
                plugin.events.retry(d);
                active(p);
              }));
      list.add(
          button(
              Material.TRIPWIRE_HOOK,
              "Force unlock",
              () ->
                  confirm(
                      p,
                      "Unlock and remove remaining guardians?",
                      () -> {
                        Validation.require(
                            p.hasPermission("supplydrops.manage"), "Missing manage permission");
                        plugin.events.unlock(d, true);
                        active(p);
                      },
                      () -> event(p, d))));
      list.add(
          button(
              Material.BARRIER,
              "Cancel event",
              () ->
                  confirm(
                      p,
                      "Remove this event and its locked pile?",
                      () -> {
                        Validation.require(
                            p.hasPermission("supplydrops.manage"), "Missing manage permission");
                        plugin.events.cancel(d);
                        active(p);
                      },
                      () -> event(p, d))));
    }
    show(p, "Event › " + Events.shortId(d), list, 0, () -> active(p), false);
  }

  private void input(
      Player p, String title, String initial, Consumer<String> onSave, Runnable onCancel) {
    Session captured = sessions.get(p.getUniqueId());
    pendingDialogs.add(p.getUniqueId());
    p.closeInventory();
    p.showDialog(
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(
                        DialogBase.builder(text(title))
                            .canCloseWithEscape(false)
                            .inputs(
                                List.of(
                                    DialogInput.text("value", text(title))
                                        .initial(initial)
                                        .maxLength(1000)
                                        .width(300)
                                        .build()))
                            .build())
                    .type(
                        DialogType.confirmation(
                            ActionButton.create(
                                text("Apply to draft"),
                                Component.text("Save all changes from the menu"),
                                150,
                                DialogAction.customClick(
                                    (view, audience) ->
                                        plugin.main(
                                            () -> {
                                              if (!(audience instanceof Player actor)
                                                  || !actor.getUniqueId().equals(p.getUniqueId())
                                                  || !p.hasPermission("supplydrops.edit")
                                                  || sessions.get(p.getUniqueId()) != captured)
                                                return;
                                              try {
                                                pendingDialogs.remove(p.getUniqueId());
                                                onSave.accept(
                                                    Objects.requireNonNullElse(
                                                        view.getText("value"), ""));
                                              } catch (Exception e) {
                                                message(p, e.getMessage());
                                                input(p, title, initial, onSave, onCancel);
                                              }
                                            }),
                                    ClickCallback.Options.builder().uses(1).build())),
                            ActionButton.create(
                                text("Cancel"),
                                null,
                                100,
                                DialogAction.customClick(
                                    (view, audience) ->
                                        plugin.main(
                                            () -> {
                                              if (audience instanceof Player actor
                                                  && actor.getUniqueId().equals(p.getUniqueId())) {
                                                pendingDialogs.remove(p.getUniqueId());
                                                onCancel.run();
                                              }
                                            }),
                                    ClickCallback.Options.builder().uses(1).build()))))));
  }

  private void confirm(Player p, String title, Runnable yes, Runnable no) {
    Session captured = sessions.get(p.getUniqueId());
    pendingDialogs.add(p.getUniqueId());
    p.closeInventory();
    p.showDialog(
        Dialog.create(
            builder ->
                builder
                    .empty()
                    .base(DialogBase.builder(text(title)).canCloseWithEscape(false).build())
                    .type(
                        DialogType.confirmation(
                            ActionButton.create(
                                text("Confirm"),
                                null,
                                100,
                                DialogAction.customClick(
                                    (v, a) ->
                                        plugin.main(
                                            () -> {
                                              if (!(a instanceof Player actor)
                                                  || !actor.getUniqueId().equals(p.getUniqueId())
                                                  || sessions.get(p.getUniqueId()) != captured)
                                                return;
                                              if (!p.hasPermission("supplydrops.edit")
                                                  && !p.hasPermission("supplydrops.manage")) return;
                                              try {
                                                pendingDialogs.remove(p.getUniqueId());
                                                yes.run();
                                              } catch (Exception e) {
                                                message(p, e.getMessage());
                                                {
                                                  pendingDialogs.remove(p.getUniqueId());
                                                  no.run();
                                                }
                                              }
                                            }),
                                    ClickCallback.Options.builder().uses(1).build())),
                            ActionButton.create(
                                text("Cancel"),
                                null,
                                100,
                                DialogAction.customClick(
                                    (v, a) ->
                                        plugin.main(
                                            () -> {
                                              if (a instanceof Player actor
                                                  && actor.getUniqueId().equals(p.getUniqueId())) {
                                                pendingDialogs.remove(p.getUniqueId());
                                                no.run();
                                              }
                                            }),
                                    ClickCallback.Options.builder().uses(1).build()))))));
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof View v)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !v.player.equals(p.getUniqueId())
        || !p.hasPermission(v.permission)) return;
    Session s = sessions.get(p.getUniqueId());
    if (s != null && s.saving) return;
    Runnable action = v.actions.get(e.getRawSlot());
    if (action != null)
      plugin.main(
          () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() != v
                || !p.hasPermission(v.permission)) return;
            try {
              action.run();
            } catch (Exception ex) {
              message(p, ex.getMessage() == null ? "Unable to apply that change" : ex.getMessage());
            }
          });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
  }

  @EventHandler
  public void inventoryClose(InventoryCloseEvent event) {
    if (closing
        || !(event.getView().getTopInventory().getHolder() instanceof View)
        || !(event.getPlayer() instanceof Player player)) return;
    UUID id = player.getUniqueId();
    Session current = sessions.get(id);
    if (current == null || current.saving) return;
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (!player.isOnline()
                  || pendingDialogs.contains(id)
                  || sessions.get(id) != current
                  || current.saving) return;
              if (player.getOpenInventory().getTopInventory().getHolder() instanceof View) return;
              if (!Store.JSON.toJson(current.draft).equals(current.baseline))
                message(
                    player,
                    "Your changes have NOT been applied. They are saved in /sd until you"
                        + " disconnect.");
            },
            1);
  }

  public void close() {
    closing = true;
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof View) p.closeInventory();
    sessions.clear();
    pendingDialogs.clear();
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
    pendingDialogs.remove(e.getPlayer().getUniqueId());
  }
}
