package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SupplyDropsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
  Store store;
  Configuration config;
  Events events;
  Menus menus;

  @Override
  public void onEnable() {
    try {
      store = new Store(getDataFolder().toPath().resolve("supplydrops.db"));
      Config initial = store.read("config", Config.class, null);
      if (initial == null) {
        initial = Configuration.examples();
        store.save("config", initial).join();
      } else if (Configuration.migrate(initial)) store.save("config", initial).join();
      config = new Configuration(this, initial);
      events = new Events(this, store.read("runtime", RuntimeData.class, new RuntimeData()));
      menus = new Menus(this);
      Objects.requireNonNull(getCommand("supplydrops")).setExecutor(this);
      Objects.requireNonNull(getCommand("supplydrops")).setTabCompleter(this);
      getServer().getPluginManager().registerEvents(menus, this);
      getServer().getPluginManager().registerEvents(new Protection(events), this);
      events.recover();
      getServer().getScheduler().runTaskTimer(this, events::tick, 1, 1);
      getLogger()
          .info("Ready. Configure everything in-game with /sd. Example profile is disabled.");
    } catch (Exception e) {
      getLogger().log(java.util.logging.Level.SEVERE, "Could not initialize SupplyDrops", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  public void main(Runnable action) {
    if (isEnabled()) getServer().getScheduler().runTask(this, action);
  }

  @Override
  public void onDisable() {
    try {
      if (menus != null) menus.close();
      if (events != null) events.stop();
    } catch (Exception e) {
      getLogger().log(java.util.logging.Level.SEVERE, "Could not flush SupplyDrops", e);
    } finally {
      if (store != null)
        try {
          store.close();
        } catch (Exception e) {
          getLogger().log(java.util.logging.Level.SEVERE, "Could not close SupplyDrops storage", e);
        }
    }
  }

  private void permission(CommandSender sender, String permission) {
    Validation.require(
        sender.hasPermission("supplydrops." + permission),
        "Missing supplydrops." + permission + " permission");
  }

  private String resolveProfile(String value) {
    return ResourceNames.resolveProfile(config.current.profiles, value);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
      switch (sub) {
        case "menu", "edit" -> {
          if (sub.equals("edit")) permission(sender, "edit");
          Validation.require(sender instanceof Player, "Open the GUI in-game");
          menus.open((Player) sender);
        }
        case "events" -> {
          permission(sender, "view");
          if (sender instanceof Player p) menus.active(p);
          else
            events
                .active()
                .forEach(
                    d ->
                        sender.sendMessage(
                            Events.label(d) + " " + d.stage + " " + Events.coordinates(d)));
        }
        case "spawn" -> {
          permission(sender, "spawn");
          Validation.require(
              args.length == 2 || args.length == 6, "Usage: /sd spawn <profile-name> [world x y z]");
          Location location = null;
          if (args.length == 6) {
            World w = Bukkit.getWorld(args[2]);
            Validation.require(w != null, "Unknown world");
            location =
                new Location(
                    w,
                    Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]));
          }
          events.spawn(resolveProfile(args[1]), location, sender::sendMessage);
        }
        case "inspect", "cancel", "unlock", "retry" -> {
          permission(sender, sub.equals("inspect") ? "view" : "manage");
          Validation.require(args.length == 2, "Usage: /sd " + sub + " <event-id>");
          Drop d = events.find(args[1]);
          switch (sub) {
            case "cancel" -> events.cancel(d);
            case "unlock" -> events.unlock(d, true);
            case "retry" -> events.retry(d);
            default ->
                sender.sendMessage(
                    Events.label(d)
                        + " • "
                        + d.stage
                        + " • "
                        + Events.coordinates(d)
                        + " • guardians: "
                        + d.guardians.stream().filter(g -> !g.defeated).count()
                        + " • "
                        + d.error);
          }
        }
        default ->
            sender.sendMessage(
                "/sd | /sd events | /sd spawn <profile> [world x y z] | /sd"
                    + " inspect|cancel|unlock|retry <event>");
      }
    } catch (Exception e) {
      sender.sendMessage("SupplyDrops • " + e.getMessage());
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command cmd, String alias, String[] args) {
    List<String> options = new ArrayList<>();
    if (args.length == 1) {
      options.add("events");
      options.add("inspect");
      if (sender.hasPermission("supplydrops.edit")) options.add("edit");
      if (sender.hasPermission("supplydrops.spawn")) options.add("spawn");
      if (sender.hasPermission("supplydrops.manage"))
        options.addAll(List.of("cancel", "unlock", "retry"));
    } else if (args.length == 2) {
      if (args[0].equalsIgnoreCase("spawn"))
        options.addAll(config.current.profiles.values().stream().map(p -> p.name).toList());
      else options.addAll(events.active().stream().map(Events::shortId).toList());
    } else if (args.length == 3 && args[0].equalsIgnoreCase("spawn"))
      options.addAll(Bukkit.getWorlds().stream().map(World::getName).toList());
    return options.stream()
        .filter(
            s ->
                s.toLowerCase(Locale.ROOT)
                    .startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
        .toList();
  }
}
