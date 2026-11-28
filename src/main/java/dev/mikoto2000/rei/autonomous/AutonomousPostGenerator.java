package dev.mikoto2000.rei.autonomous;

import java.util.List;

/** Inference over a caller-owned public snapshot; it has no publication operation. */
@FunctionalInterface
public interface AutonomousPostGenerator {
  String generate(List<AutonomousPublicSources.PublicSource> sources);
}
