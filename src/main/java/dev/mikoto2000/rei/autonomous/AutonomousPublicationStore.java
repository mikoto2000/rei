package dev.mikoto2000.rei.autonomous;

import java.util.Optional;

/** Publication journal capability, deliberately separate from the generator's draft store. */
public interface AutonomousPublicationStore {
  record PublishClaim(String draftId, String token, AutonomousPostRecord record) {}

  enum Outcome {
    PUBLISHED,
    FAILED,
    UNKNOWN
  }

  boolean isStopping();

  AutonomousDraft get(String id);

  PublishClaim beginPublication(String id, int expectedRevision, String actor);

  AutonomousDraft finishPublication(
      PublishClaim claim, Outcome outcome, String uri, String cid, String failureCode);

  Optional<AutonomousPostRecord> publicationRecord(String id);

  void recoverInterruptedPublications();
}
