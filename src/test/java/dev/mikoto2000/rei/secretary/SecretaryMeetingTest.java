package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretaryMeetingTest {
  final LocalDate date = LocalDate.of(2026, 10, 31);
  final ZoneId zone = ZoneId.of("Asia/Tokyo");
  final Instant now = Instant.parse("2026-10-31T09:00:00Z");

  SecretaryContext.Item context(
      String id, String work, String kind, String state, boolean verified, Instant updated) {
    return new SecretaryContext.Item(
        "work-context",
        id,
        "作業 " + id,
        kind,
        "saved declaration",
        SecretaryContext.Reliability.FACT,
        List.of("work-evidence:" + id),
        new SecretaryContext.Observation(work, updated, state, verified, "次の確認", null, kind));
  }

  SecretaryMeeting.View view(List<SecretaryContext.Item> items) {
    return SecretaryMeeting.project(
        "p", date, zone, true, Map.of(), items, false, List.of(), List.of());
  }

  @Test
  void savedContextProducesCandidatesWithoutWritingTasks() {
    var result = view(List.of(context("one", "run:1", "PENDING", "UNKNOWN", false, now)));
    assertEquals(1, result.sections().get("carryover").size());
    assertEquals("UNKNOWN", result.sections().get("carryover").getFirst().state());
  }

  @Test
  void purposeAndToolSuccessDoNotBecomeCompletionOrCandidates() {
    var result =
        view(
            List.of(
                context("one", "run:1", "PURPOSE", "UNKNOWN", false, now),
                context("two", "run:2", "VERIFICATION", "COMPLETED", false, now)));
    assertTrue(result.sections().get("completed").isEmpty());
    assertTrue(result.sections().get("carryover").isEmpty());
  }

  @Test
  void explicitIdentityMergesEvidenceButSimilarTitlesDoNotMerge() {
    var result =
        view(
            List.of(
                context("one", "run:1", "CURRENT_WORK", "UNKNOWN", false, now),
                context("two", "run:1", "NEXT_ACTION", "UNKNOWN", false, now),
                context("three", "run:2", "PENDING", "UNKNOWN", false, now)));
    assertEquals(2, result.sections().get("unfinished").size());
    assertEquals(2, result.sections().get("unfinished").getFirst().evidence().size());
  }

  @Test
  void canonicalStateOverridesNewerHistoricalBlockerAndPending() {
    var task =
        new SecretaryTask(
            "t",
            "p",
            "review",
            SecretaryTask.Intent.TODO,
            SecretaryTask.Status.DONE,
            null,
            2,
            List.of(),
            List.of(),
            "EXPLICIT",
            1,
            now,
            now);
    var result =
        SecretaryMeeting.project(
            "p",
            date,
            zone,
            true,
            Map.of("completed", List.of(task)),
            List.of(
                context(
                    "old", "secretary-task:t", "BLOCKER", "BLOCKED", false, now.plusSeconds(1))),
            false,
            List.of(),
            List.of());
    assertEquals(1, result.sections().get("completed").size());
    assertTrue(result.sections().get("waiting").isEmpty());
    assertTrue(result.sections().get("unfinished").isEmpty());
  }

  @Test
  void oldCompletionIsOmittedButContinuingWorkSurvivesDateFilter() {
    var old = now.minus(Duration.ofDays(10));
    var result =
        view(
            List.of(
                context("a", "run:a", "PENDING", "UNKNOWN", false, old),
                context("b", "run:b", "COMPLETED_WORK", "UNKNOWN", false, old)));
    assertEquals(1, result.sections().get("carryover").size());
    assertTrue(result.sections().get("progress").isEmpty());
  }

  @Test
  void tokyoDateUsesHalfOpenBoundsAndExcludesFutureEvents() {
    var result =
        view(
            List.of(
                context(
                    "a",
                    "run:a",
                    "CURRENT_WORK",
                    "UNKNOWN",
                    false,
                    Instant.parse("2026-10-30T15:00:00Z")),
                context(
                    "b",
                    "run:b",
                    "CURRENT_WORK",
                    "UNKNOWN",
                    false,
                    Instant.parse("2026-10-31T15:00:00Z"))));
    assertEquals(1, result.sections().get("progress").size());
    assertEquals("run:a", result.sections().get("progress").getFirst().id());
  }

  @Test
  void unknownLegacyContextIsNotTreatedAsVerifiedCurrentState() {
    var result =
        view(
            List.of(
                new SecretaryContext.Item(
                    "work-context", "x", "runCommand succeeded", "COMPLETED", "no timestamp")));
    assertTrue(result.sections().get("completed").isEmpty());
  }

  @Test
  void priorityUsesDeadlineThenPriorityAndLimitsSuggestionsToThree() {
    var tasks = new ArrayList<SecretaryTask>();
    for (int i = 0; i < 6; i++)
      tasks.add(
          new SecretaryTask(
              "t" + i,
              "p",
              "作業" + i,
              SecretaryTask.Intent.TODO,
              SecretaryTask.Status.OPEN,
              date.plusDays(i),
              3,
              List.of(),
              List.of(),
              "EXPLICIT",
              0,
              now,
              now));
    var result =
        SecretaryMeeting.project(
            "p",
            date,
            zone,
            false,
            Map.of("unfinished", tasks),
            List.of(),
            false,
            List.of(),
            List.of());
    assertEquals(3, result.sections().get("priorities").size());
    assertEquals("secretary-task:t0", result.sections().get("priorities").getFirst().id());
  }

  @Test
  void emptyMeetingEndsWithoutInventingWorkOrAvailability() {
    var result = view(List.of());
    assertNull(result.question());
    assertTrue(result.text().contains("確認できた進展はありません"));
    assertTrue(result.text().contains("未取得"));
    assertFalse(result.text().contains("自由時間"));
  }

  @Test
  void hundredsOfContextItemsKeepEvidenceButBoundHumanOutput() {
    var items = new ArrayList<SecretaryContext.Item>();
    for (int i = 0; i < 420; i++)
      items.add(context("item" + i, "run:" + i, "PENDING", "UNKNOWN", false, now));
    var result = view(items);
    assertEquals(3, result.sections().get("carryover").size());
    assertTrue(result.text().length() < 5000);
  }

  @Test
  void currentGoalStateOverridesHistoricalRunAndCancellationAndResumeAreDistinct() {
    var old = context("old", "run:r", "BLOCKER", "UNKNOWN", false, now.plusSeconds(5));
    var current =
        new SecretaryContext.Item(
            "goal",
            "g",
            "全体のレビュー",
            "IN_PROGRESS",
            "canonical",
            SecretaryContext.Reliability.FACT,
            List.of("run:r"),
            new SecretaryContext.Observation(
                "goal:g", now, "IN_PROGRESS", true, null, null, "GOAL"));
    var result = view(List.of(old, current));
    assertEquals(1, result.sections().get("unfinished").size());
    assertEquals("IN_PROGRESS", result.sections().get("unfinished").getFirst().state());
    assertTrue(result.sections().get("waiting").isEmpty());
    var cancelled =
        new SecretaryContext.Item(
            "goal",
            "g",
            "全体のレビュー",
            "CANCELLED",
            "canonical",
            SecretaryContext.Reliability.FACT,
            List.of("run:r"),
            new SecretaryContext.Observation("goal:g", now, "CANCELLED", true, null, null, "GOAL"));
    assertTrue(view(List.of(old, cancelled)).sections().get("carryover").isEmpty());
  }

  @Test
  void calendarIsAnAgendaItemRatherThanProgressOrAutomaticallyPlacedWork() {
    var event =
        new SecretaryContext.Item(
            "external-untrusted",
            "event",
            "予定",
            "CONFIRMED",
            "cached",
            SecretaryContext.Reliability.UNKNOWN,
            List.of(),
            new SecretaryContext.Observation(
                "external:event", now, "UNKNOWN", false, null, date, "CALENDAR"));
    var result = view(List.of(event));
    assertEquals(1, result.sections().get("schedule").size());
    assertTrue(result.sections().get("progress").isEmpty());
    assertTrue(result.sections().get("carryover").isEmpty());
  }

  @Test
  void multiProjectAggregationRetainsAtMostThreePrioritiesAndPrefersExplicitHandoff() {
    var tasks = new java.util.ArrayList<SecretaryTask>();
    for (int i = 0; i < 4; i++)
      tasks.add(
          new SecretaryTask(
              "t" + i,
              "p",
              "作業" + i,
              SecretaryTask.Intent.TODO,
              SecretaryTask.Status.OPEN,
              null,
              3,
              List.of(),
              List.of(),
              "EXPLICIT",
              0,
              now,
              now));
    var first =
        SecretaryMeeting.project(
            "p",
            date,
            zone,
            false,
            Map.of("unfinished", tasks),
            List.of(),
            false,
            List.of("secretary-task:t3"),
            List.of());
    var global = SecretaryMeeting.aggregate(date, zone.getId(), false, List.of(first), false);
    assertEquals("secretary-task:t3", global.sections().get("priorities").getFirst().id());
    assertEquals(3, global.sections().get("priorities").size());
  }

  @Test
  void textEscapesControlsWithoutCorruptingIdsOrTitles() {
    assertEquals("run:control", SecretaryMeeting.shortText("run:control"));
    assertEquals("a b", SecretaryMeeting.shortText("a\nb"));
  }

  @Test
  void heldNotificationsAppearAsWaitingWithoutBecomingWorkPriorities() {
    var notice =
        new SecretaryContext.Item(
            "attention",
            "n",
            "確認が必要",
            "HELD",
            "held notice",
            SecretaryContext.Reliability.UNKNOWN,
            List.of(),
            new SecretaryContext.Observation(
                "attention:n", now, "WAITING", true, null, null, "ATTENTION"));
    var result = view(List.of(notice));
    assertEquals(1, result.sections().get("waiting").size());
    assertTrue(result.sections().get("carryover").isEmpty());
  }

  @Test
  void unapprovedLedgerCandidatesKeepTheirUnverifiedOrigin() {
    var candidate =
        new SecretaryTask(
            "c",
            "p",
            "提案",
            SecretaryTask.Intent.TODO,
            SecretaryTask.Status.CANDIDATE,
            null,
            3,
            List.of(),
            List.of(),
            "CONVERSATION",
            0,
            now,
            now);
    var result =
        SecretaryMeeting.project(
            "p",
            date,
            zone,
            false,
            Map.of("candidate", List.of(candidate)),
            List.of(),
            false,
            List.of(),
            List.of());
    var work = result.sections().get("priorities").getFirst();
    assertFalse(work.verified());
    assertEquals(SecretaryContext.Reliability.INFERENCE, work.reliability());
  }

  @Test
  void cancelledCalendarEntriesAreNotShownAsTodaysAgenda() {
    var event =
        new SecretaryContext.Item(
            "external-untrusted",
            "event",
            "取消された予定",
            "CANCELLED",
            "cached",
            SecretaryContext.Reliability.UNKNOWN,
            List.of(),
            new SecretaryContext.Observation(
                "external:event", now, "UNKNOWN", false, null, date, "CALENDAR"));
    assertTrue(view(List.of(event)).sections().get("schedule").isEmpty());
  }
}
