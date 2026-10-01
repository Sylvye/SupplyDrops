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
      click("Weight / chance");
    });
    steps.add(() -> respond(apply, "4"));
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().weight == 4, "Guardian weight applies");
      click("RAVAGER ×");
    });
    steps.add(() -> click("Health (0 = vanilla) •"));
    steps.add(() -> respond(apply, "50.5"));
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().health == 50.5,
          "Guardian decimal health applies");
      click("Vanilla drops •");
    });
    steps.add(() -> {
      check(draft.guardians.get("Example").entries.getFirst().mobs.getFirst().vanillaDrops,
          "Guardian boolean edit applies");
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
      Config reopened = (Config) field(session(), "draft");
      check(reopened.items.get("Example").entries.getLast().item.equals(draft.items.get("Example").entries.getLast().item),
          "Copied item survives save and reopen");
      plugin.config.commit(original, plugin.config.revision, error -> {
        check(error == null, "Restore integration baseline");
        sessions().remove(id);
        pending().remove(id);
        done.run();
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
