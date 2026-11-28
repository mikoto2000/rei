package dev.mikoto2000.rei.autonomous;

import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Explicit selected-draft publication, separate from autonomous consideration and generation. */
@Service
public final class AutonomousPublicationService {
  private static final Set<String> SAFE_FAILURE_CODES =
      Set.of(
          "authentication_failed",
          "target_changed",
          "publication_blocked",
          "configuration_changed",
          "invalid_record_receipt",
          "response_lost",
          "request_failed",
          "record_request_failed",
          "publication_unknown",
          "publication_failed",
          "cancelled",
          "invalid_transport_receipt",
          "invalid_authentication_response");
  private final AutonomousPublicationStore store;
  private final AutonomousPublicationPermissions permissions;
  private final AutonomousPublicationTransport transport;

  public AutonomousPublicationService(
      AutonomousPublicationStore store,
      AutonomousPublicationPermissions permissions,
      AutonomousPublicationTransport transport) {
    this.store = Objects.requireNonNull(store);
    this.permissions = Objects.requireNonNull(permissions);
    this.transport = Objects.requireNonNull(transport);
  }

  public AutonomousDraft publish(String id, int expectedRevision, String actor) {
    if (expectedRevision < 1) throw new IllegalArgumentException("Review revision is required");
    if (store.isStopping()) throw new AutonomousConflictException();
    if (!permissions.allowed()) throw new SecurityException("Bluesky publication is not allowed");
    var current = store.get(id);
    if (current.content().revision() != expectedRevision
        || (current.state() != AutonomousDraft.State.PENDING_REVIEW
            && current.state() != AutonomousDraft.State.FAILED))
      throw new AutonomousConflictException();
    transport.validate(current.content().text());
    var claim = store.beginPublication(id, expectedRevision, actor);
    AutonomousPublicationTransport.Receipt receipt;
    try {
      receipt =
          !canSend()
              ? new AutonomousPublicationTransport.Receipt(
                  AutonomousPublicationStore.Outcome.FAILED, null, null, "publication_blocked")
              : transport.send(claim.record(), this::canSend);
    } catch (RuntimeException uncertain) {
      // The transport may already have submitted the record. Never copy provider diagnostics.
      receipt =
          new AutonomousPublicationTransport.Receipt(
              AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "publication_unknown");
    }
    if (receipt == null || receipt.outcome() == null)
      receipt =
          new AutonomousPublicationTransport.Receipt(
              AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "invalid_transport_receipt");
    var outcome = receipt.outcome();
    var code =
        outcome == AutonomousPublicationStore.Outcome.PUBLISHED
            ? ""
            : receipt.failureCode() != null && SAFE_FAILURE_CODES.contains(receipt.failureCode())
                ? receipt.failureCode()
                : outcome == AutonomousPublicationStore.Outcome.FAILED
                    ? "publication_failed"
                    : "publication_unknown";
    // Preserve cancellation while allowing the short journal transaction to record uncertainty.
    boolean interrupted = Thread.interrupted();
    try {
      return store.finishPublication(claim, outcome, receipt.uri(), receipt.cid(), code);
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private boolean canSend() {
    return !store.isStopping() && permissions.allowed();
  }

  public AutonomousDraft reconcile(String id, int expectedRevision, String actor) {
    if (expectedRevision < 1
        || actor == null
        || actor.isBlank()
        || actor.length() > 256
        || actor.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Review revision and operation source are required");
    if (store.isStopping()) throw new AutonomousConflictException();
    if (!permissions.allowed()) throw new SecurityException("Bluesky publication is not allowed");
    var current = store.get(id);
    if (current.content().revision() != expectedRevision
        || current.state() != AutonomousDraft.State.UNKNOWN)
      throw new AutonomousConflictException();
    var record = store.publicationRecord(id).orElseThrow(AutonomousConflictException::new);
    AutonomousPublicationTransport.FoundRecord found;
    try {
      found = transport.lookup(record);
    } catch (RuntimeException unavailable) {
      return current;
    }
    if (found == null || !record.matches(found.uri(), found.cid(), found.recordJson()))
      return current;
    return store.confirmPublication(id, expectedRevision, record, found.uri(), found.cid(), actor);
  }
}
