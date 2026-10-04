package dev.supplydrops;

import static dev.supplydrops.Model.*;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** Real Paper items/dialogs with a synthetic player; callbacks run through the server scheduler. */
final class EditorIntegration {
  private final IntegrationPlugin plugin;
  private final Deque<Runnable> steps = new ArrayDeque<>();
  private final UUID id = UUID.randomUUID();
  private final List<String> messages = new ArrayList<>();
  private final ItemStack source = new ItemStack(Material.DIAMOND, 5);
  private final AtomicReference<Inventory> top = new AtomicReference<>();
  private final AtomicReference<InventoryView> view = new AtomicReference<>();
  private Player player;
  private Dialog dialog;
  private DialogActionCallback apply, cancel, staleApply, staleCancel;
  private boolean permission = true, online = true, failDialog;
  private Config draft, original;
  private int beforeCreate, opened, beforeDenied;
  private long revision;

  EditorIntegration(IntegrationPlugin plugin) { this.plugin = plugin; }

  void run(Runnable done) {
    source.editMeta(meta -> {
      meta.displayName(Component.text("Copied custom diamond"));
      meta.lore(List.of(Component.text("Original lore")));
    });
    PlayerInventory inventory = proxy(PlayerInventory.class, (p, method, args) -> switch (method.getName()) {
      case "getContents" -> new ItemStack[] {source};
      case "getItemInMainHand" -> source;
      case "getSize" -> 41;
      default -> null;
    });
    player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
      case "hasPermission" -> permission;
      case "isOnline" -> online;
      case "getUniqueId" -> id;
      case "getWorld" -> plugin.world;
      case "getInventory" -> inventory;
      case "getOpenInventory" -> view.get();
      case "openInventory" -> { opened++; top.set((Inventory) args[0]); yield null; }
      case "showDialog" -> {
        if (failDialog) throw new IllegalStateException("Injected dialog failure");
        dialog = (Dialog) args[0];
        captureCallbacks();
        yield null;
      }
      case "sendMessage" -> { messages.add(Arrays.toString(args)); yield null; }
      case "getName" -> "EditorIntegrationAdmin";
      case "hashCode" -> id.hashCode();
      case "equals" -> p == args[0];
      default -> null;
    });
    view.set(proxy(InventoryView.class, (p, method, args) -> switch (method.getName()) {
      case "getTopInventory" -> top.get();
      case "getBottomInventory" -> inventory;
      case "getPlayer" -> player;
      case "getInventory" -> (int) args[0] < 54 ? top.get() : inventory;
      case "convertSlot" -> args[0];
      case "getType" -> InventoryType.CHEST;
      default -> null;
    }));
    original = Store.copy(plugin.config.current, Config.class);
    revision = plugin.config.revision;
    plugin.menus.open(player);
    draft = (Config) field(session(), "draft");
    steps.add(() -> {
      check(top.get().getSize() == 54, "Dashboard builds");
      verifyNamesAndCommands();
      check(new ItemStack(Material.STONE).getItemMeta().lore() == null,
          "Paper returns null for absent lore");
      for (ClickType click : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY)) {
        InventoryClickEvent event = new InventoryClickEvent(view.get(), InventoryType.SlotType.CONTAINER,
            49, click, InventoryAction.PICKUP_ALL);
        plugin.menus.click(event);
        check(event.isCancelled(), "UI items cannot be stolen: " + click);
      }
      detail("blocks");
      check(lore("GOLD_BLOCK").size() == 2, "Block list weight and chance work without existing lore");
      click("GOLD_BLOCK");
    });
    steps.add(() -> {
      check(lore("Weight / chance").size() == 2, "Block entry opens without lore");
      check(lore("Weight / chance").get(0).color().equals(net.kyori.adventure.text.format.NamedTextColor.GOLD),
          "Weight color retained");
      click("Weight / chance");
    });
    steps.add(() -> respond(apply, "2.5"));
    steps.add(() -> {
      check(draft.blocks.get("Example").entries.getFirst().weight == 2.5, "Block weight applies");
      detail("items");
      click("Copy inventory item");
    });
    steps.add(() -> click("Copied custom diamond"));
    steps.add(() -> {
      ItemEntry copied = draft.items.get("Example").entries.getLast();
      check(source.getAmount() == 5 && Loot.decode(copied.item).isSimilar(source),
          "Copied item preserves metadata without consumption");
      click("Copied custom diamond");
    });
    steps.add(() -> {
      check(lore("Weight / chance").size() == 2, "Item entry opens without lore");
      click("Min •");
    });
    steps.add(() -> respond(apply, "oops"));
    steps.add(() -> {
      check(initial().equals("oops"), "Invalid numeric input is retained for retry");
      check(messages.stream().anyMatch(s -> s.contains("Enter a valid number")), "Numeric error is useful");
      check(draft.items.get("Example").entries.getLast().min == 1, "Invalid number leaves value intact");
      respond(apply, "1");
    });
    steps.add(() -> {
      detail("guardians");
      check(lore("Raiders").size() == 3, "Existing guardian description is retained with weight/chance");
      click("Raiders");
    });
    steps.add(() -> {
      check(lore("Weight / chance").size() == 2, "Guardian encounter opens without lore");
      check(menuItem("RAVAGER ×").getType() == Material.RAVAGER_SPAWN_EGG,
          "Existing groups use spawn eggs");
      check(Menus.mobIcon("NO_SUCH_MOB") == Material.ZOMBIE_HEAD, "Mob icon fallback");
      click("Add mob group");
    });
    steps.add(() -> {
      check(menuItem("CREEPER").getType() == Material.CREEPER_SPAWN_EGG, "Mob catalog uses spawn eggs");
      click("Search •");
    });
    steps.add(() -> respond(apply, "WITHER"));
    steps.add(() -> {
      check(menuItem("WITHER").getType() == Material.WITHER_SPAWN_EGG, "Wither available in mob search");
      click("WITHER");
    });
    steps.add(() -> click("WITHER ×"));
    steps.add(() -> click("Remove mob group"));
    steps.add(() -> click("Add mob group"));
    steps.add(() -> click("Search •"));
    steps.add(() -> respond(apply, "HUSK"));
    steps.add(() -> {
      check(menuItem("HUSK").getType() == Material.HUSK_SPAWN_EGG, "Search retains mob icons");
      EncounterTable table = draft.guardians.get("Example");
      invoke("encounter", new Class<?>[] {Player.class, EncounterTable.class, Encounter.class, Runnable.class},
          player, table, table.entries.getFirst(), (Runnable) () -> {});
      click("Weight / chance");
    });
    steps.add(() -> respond(apply, "4"));
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().weight == 4, "Guardian weight applies");
      click("RAVAGER ×");
    });
    steps.add(() -> click("Attributes"));
    steps.add(() -> click("Add attribute"));
    steps.add(() -> {
      check(GuardianAttributes.supported(draft.guardians.get("Example").entries.getFirst().mobs.getFirst())
          .stream().noneMatch(key -> key.equals("minecraft:block_break_speed")),
          "Player-only attributes excluded from ravager picker");
      click("minecraft:max_health");
    });
    steps.add(() -> {
      check(lore("Base value •").getFirst().toString().contains("100.0"), "Shows ravager-specific health default");
      click("Base value •");
    });
    steps.add(() -> respond(apply, "NaN"));
    steps.add(() -> {
      check(initial().equals("NaN"), "Invalid attribute retained for retry");
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes.isEmpty(),
          "Invalid input creates no override");
      respond(apply, "0");
    });
    steps.add(() -> {
      check(initial().equals("0"), "Zero health rejected without changing draft");
      respond(cancel, "");
    });
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes.isEmpty(),
          "Canceled attribute leaves vanilla default intact");
      click("Base value •");
    });
    steps.add(() -> respond(apply, "50.5"));
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes
          .get(GuardianAttributes.MAX_HEALTH) == 50.5,
          "Guardian decimal health applies");
      click("Reset to default");
    });
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes.isEmpty(),
          "Reset removes attribute override");
      click("Add attribute");
    });
    steps.add(() -> click("minecraft:max_health"));
    steps.add(() -> click("Base value •"));
    steps.add(() -> respond(apply, "50.5"));
    steps.add(() -> click("Back"));
    steps.add(() -> click("Add attribute"));
    steps.add(() -> click("Search •"));
    steps.add(() -> respond(apply, "movement_speed"));
    steps.add(() -> click("minecraft:movement_speed"));
    steps.add(() -> click("Base value •"));
    steps.add(() -> respond(apply, "0.35"));
    steps.add(() -> click("Back"));
    steps.add(() -> click("Back"));
    steps.add(() -> {
      click("Vanilla drops •");
    });
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().vanillaDrops,
          "Guardian boolean edit applies");
      check(menuItem("Damage immunity • OFF").getType() == Material.GRAY_DYE,
          "Group immunity defaults OFF");
      click("Damage immunity • OFF");
    });
    steps.add(() -> {
      check(menuItem("Damage immunity • ON").getType() == Material.LIME_DYE, "Immunity toggle applies");
      click("Equipment");
    });
    steps.add(() -> {
      check(menuItem("HEAD •").getType() == Material.IRON_HELMET, "Unassigned head uses helmet icon");
      check(menuItem("HAND •").getType() == Material.IRON_SWORD, "Unassigned hand uses sword icon");
      check(menuItem("OFF_HAND •").getType() == Material.SHIELD, "Off hand uses shield icon");
      check(menuItem("BODY •").getType() == Material.WOLF_ARMOR, "Body uses armor icon");
      check(menuItem("SADDLE •").getType() == Material.SADDLE, "Saddle uses saddle icon");
      click("HEAD •");
    });
    steps.add(() -> click("Copied custom diamond"));
    steps.add(() -> {
      MobSpec mob = draft.guardians.get("Example").entries.getFirst().mobs.getFirst();
      ItemStack stored = Loot.decode(mob.equipment.get("HEAD"));
      ItemStack visual = menuItem("Copied custom diamond");
      check(visual.getType() == Material.DIAMOND && visual.getAmount() == 1, "Assigned item shown");
      check(stored.isSimilar(source) && source.getAmount() == 5, "Equipment source metadata retained");
      check(stored.getItemMeta().lore().equals(source.getItemMeta().lore()), "Menu lore does not alter storage");
      check(visual.getItemMeta().lore().size() == 3, "Original lore plus slot information shown");
      click("Clear HEAD");
    });
    steps.add(() -> {
      MobSpec mob = draft.guardians.get("Example").entries.getFirst().mobs.getFirst();
      check(!mob.equipment.containsKey("HEAD"), "Equipment clear works");
      check(menuItem("HEAD •").getType() == Material.IRON_HELMET, "Clear restores slot icon");
      Encounter encounter = draft.guardians.get("Example").entries.getFirst();
      invoke("mob", new Class<?>[] {Player.class, Encounter.class, MobSpec.class, Runnable.class},
          player, encounter, mob, (Runnable) () -> {});
      click("Name •");
    });
    steps.add(() -> respond(apply, "Test guardian"));
    steps.add(() -> { click("Name •"); });
    steps.add(() -> respond(cancel, ""));
    steps.add(() -> {
      check(label(0).contains("Test guardian"), "Text applies and cancellation restores menu");
      invoke("listing", new Class<?>[] {Player.class, String.class}, player, "blocks");
      beforeCreate = draft.blocks.size();
      click("Create");
    });
    steps.add(() -> respond(apply, " "));
    steps.add(() -> {
      check(draft.blocks.size() == beforeCreate, "Invalid creation leaves no phantom entry");
      check(initial().equals(" "), "Invalid name retained for retry");
      respond(cancel, "");
    });
    steps.add(() -> {
      detail("blocks");
      failDialog = true;
      click("Rename •");
    });
    steps.add(() -> {
      failDialog = false;
      check(!pending().containsKey(id), "Dialog construction failure clears pending state");
      check(label(0).startsWith("Rename"), "Dialog construction failure restores source menu");
      check(messages.stream().anyMatch(s -> s.contains("server log")), "Unexpected error has diagnostic message");
      click("Rename •");
    });
    steps.add(() -> {
      staleApply = apply;
      staleCancel = cancel;
      sessions().remove(id);
      plugin.menus.open(player);
      Object replacement = session();
      respond(staleApply, "Stale name");
      respond(staleCancel, "");
      check(session() == replacement, "Replacement session retained");
    });
    steps.add(() -> {
      check(label(0).equals("Profiles"), "Stale Apply and Cancel cannot reopen old menu");
      check(plugin.config.current.blocks.get("Example").name.equals(original.blocks.get("Example").name),
          "Stale callback cannot change saved configuration");
      // Restore the edited draft for save/reopen verification.
      setField(session(), "draft", draft);
      detail("blocks");
      click("Rename •");
    });
    steps.add(() -> { permission = false; respond(apply, "Unauthorized name"); respond(cancel, ""); });
    steps.add(() -> {
      permission = true;
      check(draft.blocks.get("Example").name.equals(original.blocks.get("Example").name),
          "Permission loss blocks dialog actions");
      detail("blocks");
      click("Rename •");
    });
    steps.add(() -> { beforeDenied = opened; permission = false; respond(cancel, ""); });
    steps.add(() -> {
      permission = true;
      check(!pending().containsKey(id), "Unauthorized Cancel is consumed without reopening the editor");
      check(opened == beforeDenied, "Unauthorized Cancel does not navigate");
      // A callback from a different player must leave the legitimate response usable.
      detail("blocks");
      click("Rename •");
    });
    steps.add(() -> {
      Player stranger = proxy(Player.class, (p, method, args) ->
          method.getName().equals("getUniqueId") ? UUID.randomUUID() : null);
      cancel.accept(proxy(DialogResponseView.class, (p, method, args) -> null), stranger);
    });
    steps.add(() -> {
      check(pending().containsKey(id), "Wrong-player response is ignored");
      online = false;
      respond(apply, "Offline name");
    });
    steps.add(() -> {
      online = true;
      check(draft.blocks.get("Example").name.equals(original.blocks.get("Example").name),
          "Offline response is ignored");
      respond(cancel, "");
      respond(apply, "Duplicate name");
    });
    steps.add(() -> {
      check(draft.blocks.get("Example").name.equals(original.blocks.get("Example").name),
          "Second response to consumed dialog is ignored");
      click("Delete");
    });
    steps.add(() -> respond(cancel, ""));
    steps.add(() -> {
      check(draft.blocks.containsKey("Example"), "Confirmation cancel preserves table");
      plugin.config.commit(null, revision, error -> {
        check(error != null && error.contains("server log"), "Unexpected save failure cannot report success");
        check(plugin.config.revision == revision, "Failed save keeps current configuration intact");
      });
      addWorldRegressionProfiles();
      click("Save changes");
    });
    steps.add(() -> plugin.await(() -> plugin.config.revision > revision, () -> {
      Config saved;
      try {
        saved = plugin.store.read("config", Config.class, null);
      } catch (java.sql.SQLException error) { throw new RuntimeException(error); }
      check(saved.blocks.get("Example").entries.getFirst().weight == 2.5, "Block edit persisted");
      check(saved.guardians.get("Example").entries.getFirst().mobs.getFirst().name.equals("Test guardian"),
          "Guardian customization persisted");
      check(saved.guardians.get("Example").entries.getFirst().mobs.getFirst().damageImmune,
          "Immunity persists through save");
      check(saved.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes
          .equals(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes),
          "Attribute overrides persist through save");
      Config reopened = (Config) field(session(), "draft");
      check(reopened.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes
          .equals(saved.guardians.get("Example").entries.getFirst().mobs.getFirst().attributes),
          "Attribute overrides survive editor reopen");
      check(reopened.guardians.get("Example").entries.getFirst().mobs.getFirst().damageImmune,
          "Immunity survives editor reopen");
      check(reopened.items.get("Example").entries.getLast().item.equals(draft.items.get("Example").entries.getLast().item),
          "Copied item survives save and reopen");
      check(saved.profiles.get("stale-world-test").worlds.equals(List.of("paper_26_2_123456789")),
          "Enabled stale-world profile saves without losing references");
      check(saved.profiles.get("empty-world-test").worlds.isEmpty(), "Enabled profile without worlds saves");
      check(reopened.profiles.get("stale-world-test").worlds.equals(saved.profiles.get("stale-world-test").worlds),
          "Unavailable world survives save and reopen for manual repair");
      verifyUnavailableSpawn("stale-world-test");
      verifyUnavailableSpawn("empty-world-test");
      Profile repaired = reopened.profiles.get("stale-world-test");
      invoke("worlds", new Class<?>[] {Player.class, Profile.class, Runnable.class},
          player, repaired, (Runnable) () -> {});
      click("paper_26_2_123456789 (unavailable;");
      plugin.later(3, () -> {
        check(repaired.worlds.isEmpty(), "Unavailable world removable in editor");
        click(plugin.world.getName());
        plugin.later(3, () -> {
          check(repaired.worlds.equals(List.of(plugin.world.getName())), "Renamed world can be selected");
          long repairRevision = plugin.config.revision;
          click("Save changes");
          plugin.await(() -> plugin.config.revision > repairRevision, () -> {
            check(plugin.config.current.profiles.get("stale-world-test").worlds.equals(List.of(plugin.world.getName())),
                "Repaired world selection saves despite other unavailable profiles");
            plugin.config.commit(original, plugin.config.revision, error -> {
              check(error == null, "Restore integration baseline");
              sessions().remove(id);
              pending().remove(id);
              done.run();
            });
          }, 100);
        });
      });
    }, 100));
    next();
  }

  private void next() {
    if (steps.isEmpty()) return;
    plugin.guard(steps.removeFirst());
    if (!steps.isEmpty()) plugin.later(3, this::next);
  }

  private void detail(String kind) {
    invoke("detail", new Class<?>[] {Player.class, String.class, String.class}, player, kind, "Example");
  }

  private void addWorldRegressionProfiles() {
    for (String kind : List.of("stale", "mixed", "empty")) {
      Profile profile = Store.copy(draft.profiles.get("Example"), Profile.class);
      profile.name = kind + "_world_test";
      profile.enabled = true;
      profile.scheduled = false;
      profile.worlds = new ArrayList<>();
      if (!kind.equals("empty")) profile.worlds.add("paper_26_2_123456789");
      if (kind.equals("mixed")) {
        profile.worlds.add(null);
        profile.worlds.add("");
        profile.worlds.add(plugin.world.getName());
        check(Validation.spawnProfile(profile, draft).equals(List.of(plugin.world)),
            "Spawn candidates exclude unavailable and invalid world references");
      }
      draft.profiles.put(kind + "-world-test", profile);
    }
  }

  private void verifyUnavailableSpawn(String id) {
    int before = plugin.events.data.drops.size();
    AtomicReference<String> reply = new AtomicReference<>();
    plugin.events.spawn(id, null, reply::set);
    check(reply.get() != null && reply.get().contains("No available worlds"),
        "Unavailable profile gives actionable spawn error: " + id);
    check(plugin.events.data.drops.size() == before, "Unavailable profile creates no event");
  }

  private void verifyNamesAndCommands() {
    Profile profile = draft.profiles.get("Example");
    invoke("listing", new Class<?>[] {Player.class, String.class}, player, "profiles");
    check(Objects.requireNonNullElse(menuItem(profile.name).getItemMeta().lore(), List.of()).isEmpty(),
        "Profile list hides internal IDs");
    detail("profiles");
    check(menuItem("Block table • " + draft.blocks.get(profile.blockTable).name) != null,
        "Profile displays block table name");
    check(menuItem("Guardian table • " + draft.guardians.get(profile.guardianTable).name) != null,
        "Profile displays guardian table name");
    invoke("mappings", new Class<?>[] {Player.class, Profile.class, Runnable.class},
        player, profile, (Runnable) () -> {});
    check(PlainTextComponentSerializer.plainText().serialize(lore("CHEST").getFirst())
        .equals("Table: " + draft.items.get(profile.containers.get("CHEST")).name),
        "Container table name shown");
    MobSpec mob = draft.guardians.get("Example").entries.getFirst().mobs.getFirst();
    String previous = mob.dropTable;
    mob.dropTable = "Example";
    invoke("mob", new Class<?>[] {Player.class, Encounter.class, MobSpec.class, Runnable.class},
        player, draft.guardians.get("Example").entries.getFirst(), mob, (Runnable) () -> {});
    check(menuItem("Custom drops • " + draft.items.get("Example").name) != null, "Drop table name shown");
    mob.dropTable = previous;
    List<String> completions = plugin.onTabComplete(player, plugin.getCommand("supplydrops"), "sd",
        new String[] {"spawn", ""});
    check(completions.contains(plugin.config.current.profiles.get("Example").name)
        && !completions.contains("Example"), "Spawn completion uses names");
    Profile saved = plugin.config.current.profiles.get("Example");
    boolean enabled = saved.enabled;
    saved.enabled = true;
    for (String argument : List.of(saved.name.toUpperCase(Locale.ROOT), "Example")) {
      messages.clear();
      plugin.onCommand(player, plugin.getCommand("supplydrops"), "sd",
          new String[] {"spawn", argument, plugin.world.getName(), "999999", "90", "999999"});
      check(messages.stream().anyMatch(s -> s.contains("No safe landing site")),
          "Name/legacy command resolves profile and coordinates: " + argument);
    }
    saved.enabled = enabled;
    invoke("listing", new Class<?>[] {Player.class, String.class}, player, "blocks");
    int count = draft.blocks.size();
    try {
      invoke("duplicate", new Class<?>[] {Player.class, String.class, String.class, String.class},
          player, "blocks", "Example", draft.blocks.get("Example").name.toUpperCase(Locale.ROOT));
      throw new AssertionError("Duplicate name should be rejected");
    } catch (RuntimeException expected) {
      check(expected.getCause() instanceof InvocationTargetException
          && expected.getCause().getCause() instanceof IllegalArgumentException, "Duplicate name rejected");
    }
    check(count == draft.blocks.size(), "Duplicate rejection creates no phantom resource");
    plugin.menus.open(player);
  }

  private ItemStack menuItem(String prefix) {
    for (int slot = 0; slot < 54; slot++)
      if (label(slot).startsWith(prefix)) return top.get().getItem(slot);
    throw new AssertionError("Missing menu item: " + prefix);
  }

  private void click(String prefix) {
    for (int slot = 0; slot < 54; slot++) {
      if (label(slot).startsWith(prefix)) {
        InventoryClickEvent event = new InventoryClickEvent(view.get(), InventoryType.SlotType.CONTAINER,
            slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        plugin.menus.click(event);
        check(event.isCancelled(), "Click canceled: " + prefix);
        return;
      }
    }
    throw new AssertionError("Missing menu action: " + prefix);
  }

  private String label(int slot) {
    ItemStack item = top.get().getItem(slot);
    return item == null || item.getItemMeta().displayName() == null ? ""
        : PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
  }

  private List<Component> lore(String prefix) {
    for (int slot = 0; slot < 54; slot++)
      if (label(slot).startsWith(prefix)) return top.get().getItem(slot).getItemMeta().lore();
    throw new AssertionError("Missing lore: " + prefix);
  }

  private void respond(DialogActionCallback callback, String value) {
    callback.accept(proxy(DialogResponseView.class, (p, method, args) ->
        method.getName().equals("getText") ? value : null), player);
  }

  // Reflection into Paper is confined to this test harness, never the production JAR.
  private void captureCallbacks() throws Exception {
    Object manager = Class.forName("io.papermc.paper.adventure.providers.ClickCallbackProviderImpl")
        .getField("DIALOG_CLICK_MANAGER").get(null);
    Field queue = manager.getClass().getSuperclass().getDeclaredField("queue");
    queue.setAccessible(true);
    Object[] queued = ((Queue<?>) queue.get(manager)).toArray();
    apply = (DialogActionCallback) field(queued[queued.length - 2], "callback");
    cancel = (DialogActionCallback) field(queued[queued.length - 1], "callback");
  }

  private String initial() {
    try {
      Object handle = dialog.getClass().getMethod("getHandle").invoke(dialog);
      Object common = handle.getClass().getMethod("common").invoke(handle);
      Object input = ((List<?>) common.getClass().getMethod("inputs").invoke(common)).getFirst();
      Object control = input.getClass().getMethod("control").invoke(input);
      return (String) control.getClass().getMethod("initial").invoke(control);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, Object> sessions() { return (Map<UUID, Object>) field(plugin.menus, "sessions"); }
  @SuppressWarnings("unchecked")
  private Map<UUID, UUID> pending() { return (Map<UUID, UUID>) field(plugin.menus, "pendingDialogs"); }
  private Object session() { return sessions().get(id); }
  private Object field(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
  private void setField(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
  private void invoke(String name, Class<?>[] types, Object... args) {
    try {
      Method method = Menus.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      method.invoke(plugin.menus, args);
    } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
  }
  private <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(plugin.getClass().getClassLoader(), new Class<?>[] {type}, handler));
  }
  private void check(boolean condition, String message) { plugin.check(condition, message); }
}
