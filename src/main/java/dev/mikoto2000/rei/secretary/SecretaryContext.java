package dev.mikoto2000.rei.secretary;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Optional read projection of existing schedules, work history or external information. */
public interface SecretaryContext {
  enum Reliability {
    FACT,
    USER_REGISTERED,
    INFERENCE,
    PROPOSAL,
    UNKNOWN
  }

  record Item(
      String source,
      String id,
      String title,
      String status,
      String reason,
      Reliability reliability,
      List<String> references) {
    public Item {
      reliability = reliability == null ? Reliability.UNKNOWN : reliability;
      references = references == null ? List.of() : List.copyOf(references);
    }

    public Item(String source, String id, String title, String status, String reason) {
      this(source, id, title, status, reason, Reliability.UNKNOWN, List.of());
    }
  }

  List<Item> context(String project, LocalDate date, ZoneId zone);
}
