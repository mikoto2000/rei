package dev.mikoto2000.rei.autonomous;

import java.time.Instant;
import java.util.List;

/** Operator-only history; this read port has no inference or publication operation. */
public interface AutonomousHistory {
  enum Kind {
    NEW,
    REGENERATE
  }

  enum Status {
    RUNNING,
    POLICY_SKIPPED,
    SKIPPED,
    GENERATED,
    FAILED,
    ABORTED
  }

  record Opportunity(
      String id,
      AutonomousDraft.Trigger trigger,
      Kind kind,
      Status status,
      Instant createdAt,
      Instant updatedAt,
      String reason,
      String draftId) {}

  List<AutonomousDraft> published(int limit);

  List<Opportunity> considerations(int limit);
}
