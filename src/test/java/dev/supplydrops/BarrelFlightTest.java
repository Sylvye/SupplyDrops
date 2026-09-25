package dev.supplydrops;

import static dev.supplydrops.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;

class BarrelFlightTest {
  @Test
  void segmentsAreLinearAndClampToDestination() {
    var segment = BarrelFlight.segment(64, 3);
    assertEquals(5, segment.ticks());
    assertEquals(63.25, segment.to());
    assertEquals(63.55, segment.heightAt(3), 1e-9);
    assertEquals(64, segment.heightAt(-1));
    assertEquals(63.25, segment.heightAt(20));
    var last = BarrelFlight.segment(.2, 3);
    assertEquals(2, last.ticks());
    assertEquals(0, last.to());
    assertEquals(.1, last.heightAt(1), 1e-9);
    assertEquals(22, BarrelFlight.eta(64, 3));
  }

  @Test
  void visualBottomIsExactlyTheOffsetAtEveryTerrainHeight() {
    var transform = BarrelFlight.transform(64, 4);
    assertEquals(64, transform.getTranslation().y);
    assertEquals(-2, transform.getTranslation().x);
    assertEquals(4, transform.getScale().x);
    for (int ground : new int[] {-60, 64, 318}) {
      assertEquals(ground + 64, ground + transform.getTranslation().y);
      assertEquals(ground, ground + BarrelFlight.transform(0, 4).getTranslation().y);
    }
  }

  @Test
  void landingWaitsForTheLastInterpolationAndRecoveryStartsFromSavedProgress() {
    var target = new AtomicReference<Transformation>();
    BlockDisplay display =
        (BlockDisplay)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {BlockDisplay.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("setTransformation"))
                    target.set((Transformation) args[0]);
                  return null;
                });
    Drop drop = new Drop();
    drop.settings = new Settings();
    drop.remainingHeight = .2;
    BarrelFlight flight = new BarrelFlight(display);
    assertFalse(flight.advance(drop));
    assertFalse(flight.advance(drop));
    assertEquals(.2, drop.remainingHeight);
    assertFalse(flight.advance(drop));
    assertEquals(0, target.get().getTranslation().y);
    assertEquals(.2, drop.remainingHeight);
    assertFalse(flight.advance(drop));
    assertEquals(.1, drop.remainingHeight, 1e-9);
    assertTrue(flight.advance(drop));
    assertEquals(0, drop.remainingHeight);
  }

  @Test
  void proximityUsesExactThreeDimensionalBoundaryAndEligibleModes() {
    assertTrue(Events.eligibleForGlow(GameMode.SURVIVAL, 32 * 32, 32));
    assertTrue(Events.eligibleForGlow(GameMode.ADVENTURE, 32 * 32, 32));
    assertFalse(Events.eligibleForGlow(GameMode.SURVIVAL, 32 * 32 + .01, 32));
    assertFalse(Events.eligibleForGlow(GameMode.SURVIVAL, 30 * 30 + 20 * 20, 32));
    assertFalse(Events.eligibleForGlow(GameMode.CREATIVE, 0, 32));
    assertFalse(Events.eligibleForGlow(GameMode.SPECTATOR, 0, 32));
  }
}
