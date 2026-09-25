package dev.supplydrops;

import static dev.supplydrops.Model.*;

import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The entity stays at ground level; only its client-interpolated model moves. */
final class BarrelFlight {
  static final int SEGMENT_TICKS = 5;
  final BlockDisplay display;
  private int warmup = 2;
  private Segment segment;
  private int elapsed;

  record Segment(double from, double to, int ticks) {
    double heightAt(int tick) {
      double fraction = Math.clamp((double) tick / ticks, 0, 1);
      return from + (to - from) * fraction;
    }
  }

  BarrelFlight(BlockDisplay display) {
    this.display = display;
  }

  static Segment segment(double remaining, double speed) {
    int ticks = Math.max(1, Math.min(SEGMENT_TICKS, (int) Math.ceil(remaining * 20 / speed)));
    return new Segment(remaining, Math.max(0, remaining - speed * ticks / 20), ticks);
  }

  static Transformation transform(double height, int size) {
    return new Transformation(
        new Vector3f(-size / 2f, (float) height, -size / 2f),
        new Quaternionf(),
        new Vector3f(size),
        new Quaternionf());
  }

  static int eta(double remaining, double speed) {
    return (int) Math.ceil(remaining / speed);
  }

  /** Returns true only after the final displayed interpolation has had time to finish. */
  boolean advance(Drop drop) {
    if (warmup > 0) {
      warmup--;
      return false;
    }
    if (segment != null) {
      drop.remainingHeight = segment.heightAt(++elapsed);
      if (elapsed < segment.ticks()) return false;
      drop.remainingHeight = segment.to();
    }
    if (drop.remainingHeight <= 0) return true;
    segment = segment(drop.remainingHeight, drop.settings.speed);
    elapsed = 0;
    display.setInterpolationDuration(segment.ticks());
    display.setInterpolationDelay(0);
    display.setTransformation(transform(segment.to(), drop.settings.barrelSize));
    return false;
  }
}
