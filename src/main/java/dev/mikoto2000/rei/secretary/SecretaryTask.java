package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A durable intention, independent of an execution Run or a Google task. */
public record SecretaryTask(
    String id,
    String projectId,
    String title,
    Intent intent,
    Status status,
    LocalDate due,
    int priority,
    List<String> tags,
    List<String> references,
    String origin,
    long revision,
    Instant created,
    Instant updated) {
  public enum Intent {
    IDEA,
    TODO,
    COMMITMENT
  }

  public enum Status {
    CANDIDATE,
    OPEN,
    WAITING,
    DONE,
    CANCELLED
  }

  public SecretaryTask {
    tags = List.copyOf(tags);
    references = List.copyOf(references);
  }
}
