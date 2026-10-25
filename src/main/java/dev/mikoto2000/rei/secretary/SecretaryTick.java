package dev.mikoto2000.rei.secretary;

/** Lightweight bounded work admitted by the existing scheduler clock. */
@FunctionalInterface
public interface SecretaryTick {
  void tick();
}
