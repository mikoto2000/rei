package dev.mikoto2000.rei.attention;

import dev.mikoto2000.rei.secretary.SecretaryContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read the existing held inbox; no acknowledgement or delivery side effects. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryHeldAttention implements SecretaryContext {
  private final ObjectProvider<AttentionRepository> inbox;

  public SecretaryHeldAttention(ObjectProvider<AttentionRepository> inbox) {
    this.inbox = inbox;
  }

  @Override
  public List<Item> context(String project, LocalDate date, ZoneId zone) {
    return inbox.getObject().list(project).stream()
        .filter(i -> i.status().equals("HELD"))
        .map(
            i ->
                new Item(
                    "attention",
                    i.id(),
                    i.message(),
                    "HELD",
                    "Persisted held notice; content is not independently verified.",
                    Reliability.UNKNOWN,
                    List.of("attention:" + i.id())))
        .toList();
  }
}
