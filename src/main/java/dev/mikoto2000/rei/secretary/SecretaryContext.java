package dev.mikoto2000.rei.secretary;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Optional read projection of existing schedules, work history or external information. */
public interface SecretaryContext {
  record Item(String source, String id, String title, String status, String reason) {}

  List<Item> context(String project, LocalDate date, ZoneId zone);
}
