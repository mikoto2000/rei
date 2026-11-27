package dev.mikoto2000.rei.autonomous;

/** A decision to consider a draft is not permission to publish it. */
public interface AutonomousActionPolicy {
  ActionDecision evaluate(AutonomousActionContext context);

  record ActionDecision(boolean shouldConsider, String reason) {}
}
