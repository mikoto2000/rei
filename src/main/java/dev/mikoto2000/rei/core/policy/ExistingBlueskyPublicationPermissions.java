package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.autonomous.AutonomousPublicationPermissions;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Reuses existing Bluesky send capabilities for the explicit selected-draft action. */
@Component
public final class ExistingBlueskyPublicationPermissions
    implements AutonomousPublicationPermissions {
  private final ToolPermissionPolicy policy;

  public ExistingBlueskyPublicationPermissions(ToolPermissionPolicy policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  @Override
  public boolean allowed() {
    // The separate fixed publication action supplies explicit confirmation for REQUIRE_APPROVAL.
    return policy.evaluate("blueskyPost") != PermissionDecision.DENY;
  }
}
