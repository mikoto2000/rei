package dev.mikoto2000.rei.autonomous;

/** Resolve a public configured identity without supplying publication credentials. */
@FunctionalInterface
public interface AutonomousTargetResolver {
  AutonomousDraft.Target resolve();
}
