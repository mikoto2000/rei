package dev.mikoto2000.rei.autonomous;

import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Bounded consideration ending in a review draft. This service has no publishing capability. */
@Service
@ConditionalOnProperty(
    prefix = "rei.autonomous-action",
    name = {"enabled", "bluesky.enabled"},
    havingValue = "true")
public final class AutonomousGenerationService {
  public enum Status {
    SKIPPED,
    DRAFT_CREATED,
    FAILED
  }

  public record Result(Status status, String reason, String draftId) {}

  private final AutonomousDraftStore drafts;
  private final AutonomousPublicSources sources;
  private final AutonomousTargetResolver targets;
  private final AutonomousPostGenerator generator;

  public AutonomousGenerationService(
      AutonomousDraftStore drafts,
      AutonomousPublicSources sources,
      AutonomousTargetResolver targets,
      AutonomousPostGenerator generator) {
    this.drafts = drafts;
    this.sources = sources;
    this.targets = targets;
    this.generator = generator;
  }

  public Result manual() {
    return consider("manual:" + UUID.randomUUID(), AutonomousDraft.Trigger.MANUAL);
  }

  public Result consider(String opportunityId, AutonomousDraft.Trigger trigger) {
    if (drafts.isStopping()) return skipped("stopping");
    AutonomousDraft.Target target;
    try {
      target = targets.resolve();
    } catch (RuntimeException unavailable) {
      return new Result(Status.FAILED, "target_unavailable", null);
    }
    var consideration =
        drafts.reserve(
            opportunityId,
            trigger,
            target == null ? "" : target.did(),
            target == null ? "" : target.handle());
    if (!consideration.decision().shouldConsider())
      return skipped(consideration.decision().reason());
    var claim = consideration.claim();
    try {
      var snapshot = sources.publicSnapshot();
      if (drafts.isStopping()) {
        drafts.skip(claim, "stopping");
        return skipped("stopping");
      }
      if (snapshot.isEmpty()) {
        drafts.skip(claim, "no_public_sources");
        return skipped("no_public_sources");
      }
      var raw = generator.generate(snapshot);
      if (drafts.isStopping()) {
        drafts.skip(claim, "stopping");
        return skipped("stopping");
      }
      var decision =
          AutonomousPostResponse.decode(
              raw,
              snapshot.stream()
                  .map(AutonomousPublicSources.PublicSource::id)
                  .collect(Collectors.toUnmodifiableSet()));
      if (decision instanceof AutonomousPostResponse.Skip skip) {
        drafts.skip(claim, skip.reason());
        return skipped(skip.reason());
      }
      var content = ((AutonomousPostResponse.Proposal) decision).content();
      return drafts
          .complete(claim, content.text(), content.reason(), content.sourceIds())
          .map(draft -> new Result(Status.DRAFT_CREATED, "pending_review", draft.id()))
          .orElseGet(() -> skipped("consideration_aborted"));
    } catch (RuntimeException failed) {
      // Never copy provider errors, response fragments or stack traces into UI/audit messages.
      drafts.fail(claim);
      return new Result(Status.FAILED, "generation_failed", null);
    }
  }

  private static Result skipped(String reason) {
    return new Result(Status.SKIPPED, reason, null);
  }
}
