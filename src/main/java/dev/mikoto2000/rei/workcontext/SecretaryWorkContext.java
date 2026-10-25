package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.secretary.SecretaryContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Latest saved work declarations are context, never independently verified completion facts. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryWorkContext implements SecretaryContext {
  private final WorkContextRepository contexts;

  public SecretaryWorkContext(WorkContextRepository contexts) {
    this.contexts = contexts;
  }

  @Override
  public List<Item> context(String project, LocalDate date, ZoneId zone) {
    return contexts
        .current(project)
        .map(
            snapshot ->
                snapshot.items().stream()
                    .filter(
                        i ->
                            i.status() == WorkContext.Status.OPEN
                                || i.status() == WorkContext.Status.COMPLETED)
                    .limit(256)
                    .map(
                        i ->
                            new Item(
                                "work-context",
                                i.id(),
                                i.text(),
                                i.status() == WorkContext.Status.COMPLETED
                                    ? "COMPLETED"
                                    : i.kind().name(),
                                "Saved "
                                    + i.kind()
                                    + " declaration at "
                                    + i.updatedAt()
                                    + "; "
                                    + i.reason(),
                                i.userCorrected() || i.certainty() == WorkContext.Origin.USER
                                    ? Reliability.USER_REGISTERED
                                    : i.certainty() == WorkContext.Origin.TOOL
                                        ? Reliability.FACT
                                        : i.certainty() == WorkContext.Origin.ASSISTANT
                                            ? Reliability.PROPOSAL
                                            : Reliability.INFERENCE,
                                i.evidence().stream()
                                    .limit(32)
                                    .map(e -> "work-evidence:" + e.id())
                                    .toList()))
                    .toList())
        .orElse(List.of());
  }
}
