package dev.mikoto2000.rei.autonomous;

import java.util.List;
import java.util.Optional;

/** Durable draft operations independent of the storage implementation. */
public interface AutonomousDraftStore {
  record Claim(String opportunityId, String token) {}

  record Consideration(AutonomousActionPolicy.ActionDecision decision, Claim claim) {}

  void stop();

  boolean isStopping();

  void recoverInterruptedConsiderations();

  Consideration reserve(
      String opportunityId, AutonomousDraft.Trigger trigger, String targetDid, String targetHandle);

  Optional<AutonomousDraft> complete(
      Claim claim, String text, String reason, List<String> sourceIds);

  Consideration reserveRegeneration(
      String opportunityId,
      String draftId,
      int expectedRevision,
      String targetDid,
      String targetHandle);

  Optional<AutonomousDraft> completeRegeneration(
      Claim claim, String text, String reason, List<String> sourceIds);

  void skip(Claim claim, String reason);

  void fail(Claim claim);

  AutonomousDraft get(String id);

  List<AutonomousDraft> list(int limit);

  AutonomousDraft edit(String id, int expectedRevision, String text);

  AutonomousDraft reject(String id, int expectedRevision, String reason);

  List<AutonomousDraft.Content> revisions(String id);
}
