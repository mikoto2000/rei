package dev.mikoto2000.rei.secretary;

import java.time.LocalDate;

/** Read-only reporting port; suggestion evaluation cannot reconfigure or run follow-up delivery. */
public interface SecretaryReports {
  SecretaryFollowups.Settings settings(String project);

  SecretaryFollowups.Report report(String project, LocalDate date, boolean review);

  default SecretaryFollowups.Report report(
      String project, LocalDate date, boolean review, java.time.ZoneId zone) {
    return report(project, date, review);
  }
}
