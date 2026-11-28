package dev.mikoto2000.rei.autonomous;

import java.time.Duration;

/** Lightweight policy, independent of the generator and external transport. */
public record DefaultAutonomousActionPolicy(
    boolean enabled,
    boolean blueskyEnabled,
    int maxPendingDrafts,
    int maxDraftsPerDay,
    Duration cooldown,
    int maxRegenerations,
    Duration regenerationCooldown)
    implements AutonomousActionPolicy {
  public DefaultAutonomousActionPolicy(
      boolean enabled,
      boolean blueskyEnabled,
      int maxPendingDrafts,
      int maxDraftsPerDay,
      Duration cooldown) {
    this(
        enabled,
        blueskyEnabled,
        maxPendingDrafts,
        maxDraftsPerDay,
        cooldown,
        3,
        Duration.ofHours(1));
  }

  @Override
  public ActionDecision evaluate(AutonomousActionContext context) {
    if (!enabled) return new ActionDecision(false, "disabled");
    if (!blueskyEnabled) return new ActionDecision(false, "bluesky_disabled");
    if (context.stopping()) return new ActionDecision(false, "stopping");
    if (context.targetDid() == null
        || context.targetDid().isBlank()
        || context.targetHandle() == null
        || context.targetHandle().isBlank())
      return new ActionDecision(false, "target_not_configured");
    if (context.activeOpportunity()) return new ActionDecision(false, "active_opportunity");
    if (context.regeneration() != null) {
      var regeneration = context.regeneration();
      if (!regeneration.targetMatches()) return new ActionDecision(false, "target_changed");
      if (regeneration.attempts() >= maxRegenerations)
        return new ActionDecision(false, "regeneration_limit");
      if (regeneration.lastAttemptAt() != null
          && context.now().isBefore(regeneration.lastAttemptAt().plus(regenerationCooldown)))
        return new ActionDecision(false, "regeneration_cooldown");
      return new ActionDecision(true, "consider");
    }
    if (context.pendingDrafts() >= maxPendingDrafts)
      return new ActionDecision(false, "pending_limit");
    if (context.generatedToday() >= maxDraftsPerDay)
      return new ActionDecision(false, "daily_limit");
    if (context.lastAttemptAt() != null
        && context.now().isBefore(context.lastAttemptAt().plus(cooldown)))
      return new ActionDecision(false, "cooldown");
    return new ActionDecision(true, "consider");
  }
}
