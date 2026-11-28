package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.autonomous.AutonomousPublicationPermissions;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** Reuses existing Bluesky send capabilities for the explicit selected-draft action. */
@Component
public final class ExistingBlueskyPublicationPermissions
    implements AutonomousPublicationPermissions {
  private final BooleanSupplier allowed;

  public ExistingBlueskyPublicationPermissions(ToolPermissionPolicy policy) {
    Objects.requireNonNull(policy);
    this.allowed = () -> policy.evaluate("blueskyPost") != PermissionDecision.DENY;
  }

  @Override
  public boolean allowed() {
    // The separate fixed publication action supplies explicit confirmation for REQUIRE_APPROVAL.
    return allowed.getAsBoolean();
  }
}
