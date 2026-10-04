package dev.supplydrops;

import dev.supplydrops.Model.MobSpec;
import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/** Stored overrides are base values; vanilla modifiers and effective-value limits still apply. */
final class GuardianAttributes {
  static final String MAX_HEALTH = "minecraft:max_health";

  private GuardianAttributes() {}

  static void migrate(MobSpec spec) {
    if (spec.attributes == null) spec.attributes = new LinkedHashMap<>();
    if (spec.health > 0) spec.attributes.putIfAbsent(MAX_HEALTH, spec.health);
    spec.health = 0;
  }

  static List<String> supported(MobSpec spec) {
    var defaults = EntityType.valueOf(spec.type).getDefaultAttributes();
    return Registry.ATTRIBUTE.stream()
        .filter(attribute -> defaults.getAttribute(attribute) != null)
        .map(attribute -> attribute.getKey().toString()).sorted().toList();
  }

  static Attribute resolve(MobSpec spec, String key) {
    NamespacedKey parsed = NamespacedKey.fromString(key);
    Attribute attribute = parsed == null ? null : Registry.ATTRIBUTE.get(parsed);
    Validation.require(attribute != null, "Unknown attribute: " + key);
    Validation.require(EntityType.valueOf(spec.type).getDefaultAttributes().getAttribute(attribute) != null,
        "Attribute " + key + " is unsupported by " + spec.type);
    return attribute;
  }

  static void validate(MobSpec spec, String key, Double value) {
    Attribute attribute = resolve(spec, key);
    Validation.require(value != null && Double.isFinite(value), "Enter a finite number");
    if (attribute == Attribute.MAX_HEALTH)
      Validation.require(value > 0 && value <= 1024, "Max health must be greater than 0 and at most 1024");
  }

  static void validate(MobSpec spec) {
    Validation.require(spec.attributes != null, "Guardian attributes are missing");
    spec.attributes.forEach((key, value) -> validate(spec, key, value));
  }

  static double defaultValue(MobSpec spec, String key) {
    return Objects.requireNonNull(EntityType.valueOf(spec.type).getDefaultAttributes()
        .getAttribute(resolve(spec, key))).getBaseValue();
  }

  static void apply(MobSpec spec, LivingEntity mob) {
    spec.attributes.forEach((key, value) -> {
      validate(spec, key, value);
      var instance = mob.getAttribute(resolve(spec, key));
      Validation.require(instance != null, "Spawned mob does not support " + key);
      instance.setBaseValue(value);
    });
  }
}
