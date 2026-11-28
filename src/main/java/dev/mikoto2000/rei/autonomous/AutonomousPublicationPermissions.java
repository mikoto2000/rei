package dev.mikoto2000.rei.autonomous;

/** Existing send rights; an allowed result never initiates publication by itself. */
@FunctionalInterface
public interface AutonomousPublicationPermissions {
  boolean allowed();
}
