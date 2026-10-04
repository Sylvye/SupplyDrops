package dev.supplydrops;

import static dev.supplydrops.Model.*;

import java.util.*;

/** Names are the public interface; stable storage keys remain internal. */
final class ResourceNames {
  private ResourceNames() {}

  static String name(Object value) {
    return switch (value) {
      case Profile p -> p.name;
      case BlockTable t -> t.name;
      case ItemTable t -> t.name;
      case EncounterTable t -> t.name;
      case Encounter e -> e.name;
      default -> throw new IllegalArgumentException("Unknown resource");
    };
  }

  static void setName(Object value, String name) {
    switch (value) {
      case Profile p -> p.name = name;
      case BlockTable t -> t.name = name;
      case ItemTable t -> t.name = name;
      case EncounterTable t -> t.name = name;
      case Encounter e -> e.name = name;
      default -> throw new IllegalArgumentException("Unknown resource");
    }
  }

  static boolean valid(String name) {
    return name != null && name.matches("[A-Za-z0-9_-]{1,48}");
  }

  static void validate(String name) {
    Validation.require(valid(name), "Name must contain 1–48 letters, numbers, underscores, or hyphens");
  }

  static void available(Map<String, ?> resources, String excludedId, String name) {
    validate(name);
    Validation.require(resources.entrySet().stream().noneMatch(e ->
        !e.getKey().equals(excludedId) && name.equalsIgnoreCase(name(e.getValue()))),
        "A resource with that name already exists");
  }

  static void validateAll(Map<String, ?> resources) {
    Set<String> used = new HashSet<>();
    for (Object resource : resources.values()) {
      String name = name(resource);
      validate(name);
      Validation.require(used.add(name.toLowerCase(Locale.ROOT)), "Duplicate resource name: " + name);
    }
  }

  static String display(Map<String, ?> resources, String id) {
    if (id == null || id.isBlank()) return "Unassigned";
    Object value = resources.get(id);
    return value == null ? "Missing table" : name(value);
  }

  static String resolveProfile(Map<String, Profile> profiles, String value) {
    List<String> matches = profiles.entrySet().stream()
        .filter(e -> value.equalsIgnoreCase(e.getValue().name)).map(Map.Entry::getKey).toList();
    Validation.require(matches.size() <= 1, "Duplicate profile names; rename them in the editor");
    if (!matches.isEmpty()) return matches.getFirst();
    if (profiles.containsKey(value)) return value;
    return profiles.keySet().stream().filter(k -> k.equalsIgnoreCase(value)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown profile name; use tab completion"));
  }

  static String normalize(String name) {
    String normalized = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]+", "_");
    if (normalized.isEmpty()) normalized = "Resource";
    return normalized.substring(0, Math.min(48, normalized.length()));
  }

  static void migrate(Map<String, ?> resources) {
    List<?> sorted = resources.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue).toList();
    Set<String> used = new HashSet<>();
    List<Object> pending = new ArrayList<>();
    for (Object value : sorted) {
      String name = name(value);
      if (!valid(name) || !used.add(name.toLowerCase(Locale.ROOT))) pending.add(value);
    }
    for (Object value : pending) {
      String base = normalize(name(value)), next = base;
      int suffix = 2;
      while (!used.add(next.toLowerCase(Locale.ROOT))) {
        String ending = "_" + suffix++;
        next = base.substring(0, Math.min(base.length(), 48 - ending.length())) + ending;
      }
      setName(value, next);
    }
  }
}
