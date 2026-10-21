package dev.mikoto2000.rei.activity;

import static dev.mikoto2000.rei.activity.ActivitySemanticTest.START;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.activity;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.dev;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.record;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.segments;
import static dev.mikoto2000.rei.activity.ActivityTrendTest.fine;
import static dev.mikoto2000.rei.activity.ActivityTrendTest.trends;
import static dev.mikoto2000.rei.activity.ActivityTrendTest.unknown;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 作業状況段階34の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActivityPhase34Test {
  /** {@code sample} の条件を再現するための補助情報を返す。 */
  private static ActivityRecord sample(int minute, String category, String project) {
    String app = Set.of("social", "media", "shopping").contains(category) ? "Firefox" : "Terminal";
    String service = app.equals("Firefox") ? "X" : "";
    return record(
        minute,
        app,
        project.isBlank() ? service : project,
        new ActivityRecord.Activity("m1", category, app, service, project, project),
        activity("other", "Firefox", "GitHub", ""));
  }

  /** {@code block} の条件を再現するための補助情報を返す。 */
  private static List<ActivityRecord> block(
      int start, int minutes, String category, String project) {
    return java.util.stream.IntStream.range(start, start + minutes)
        .mapToObj(i -> sample(i, category, project))
        .toList();
  }

  /** {@code summarize} の条件を再現するための補助情報を返す。 */
  private static List<TrendSummarySegment> summarize(List<ActivityRecord> records) {
    return trends(records.toArray(ActivityRecord[]::new));
  }

  /** {@code concat} の条件を再現するための補助情報を返す。 */
  private static List<ActivityRecord> concat(List<ActivityRecord> a, List<ActivityRecord> b) {
    var result = new ArrayList<>(a);
    result.addAll(b);
    return result;
  }

  /** {@code text} の条件を再現するための補助情報を返す。 */
  private static String text(List<TrendSummarySegment> segments) {
    return new TrendSummaryFormatter(ZoneOffset.UTC).format(segments);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void terminalAliasesIncludeCaseWhitespaceAndPunctuation() {
    for (String value :
        List.of(
            "WindowsTerminal",
            "Windows Terminal",
            "Windows-Terminal",
            "cmd",
            "PowerShell",
            "Local terminal",
            "Terminal",
            " terminal ")) assertEquals("ターミナル", ActivityDisplayLabels.label(value), value);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void editorAndLogAliasesAreReadable() {
    for (String value :
        List.of("Text Editor", "text editor", "TEXT_EDITOR", "Local editor", "editor"))
      assertEquals("エディタ", ActivityDisplayLabels.label(value));
    assertEquals("ローカルログ", ActivityDisplayLabels.label("Local log file"));
    assertEquals("ローカルログ", ActivityDisplayLabels.label("log file"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void twitterAliasesAreOneService() {
    for (String value :
        List.of("Twitter", "X (Twitter)", "X (旧Twitter)", "X（旧Twitter）", "x browsing"))
      assertEquals("X", ActivityDisplayLabels.label(value));
  }

  /** 作業状況の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateLabelsDoNotConsumeDisplaySlotsAndRawValuesSurvive() {
    var r =
        record(
            0,
            "Terminal",
            "rei",
            activity("development", "Terminal", "", "rei"),
            activity("other", "WindowsTerminal", "", ""),
            activity("other", "Text Editor", "", ""),
            activity("other", "text editor", "", ""),
            activity("other", "Local log file", "", ""));
    var s = trends(r).getFirst();
    assertEquals(Set.of("ターミナル", "エディタ", "ローカルログ"), new HashSet<>(s.labels()));
    assertEquals(r, s.evidence().getFirst());
    assertTrue(text(List.of(s)).contains("ローカルログ"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void serviceActivityApplicationAndTopicAreNotProjects() {
    for (String candidate :
        List.of(
            "x browsing",
            "X",
            "GitHub",
            "YouTube",
            "ChatGPT",
            "Firefox",
            "WindowsTerminal",
            "development",
            "機能設計の提案")) {
      var s = summarize(block(0, 3, "development", candidate));
      assertTrue(s.getFirst().projects().isEmpty(), candidate);
      assertFalse(text(s).contains(candidate + "関連"), candidate);
      assertEquals(
          candidate,
          s.getFirst()
              .evidence()
              .getFirst()
              .inference()
              .activities()
              .getFirst()
              .projectCandidate());
    }
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void validProjectIdentifiersArePreserved() {
    for (String project : List.of("rei", "yagisan-reports", "another_project")) {
      var s = summarize(block(0, 5, "development", project));
      assertEquals(List.of(project), s.getFirst().projects());
      assertTrue(text(s).contains(project + "関連"));
    }
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void stableFiftyFiveMinuteThemeIsNotCutAtFortyFiveMinutes() {
    var records = block(0, 55, "development", "rei");
    var result = summarize(records);
    assertEquals(1, result.size());
    assertEquals(3300, result.getFirst().observedSeconds());
    assertEquals(records, result.getFirst().evidence());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sustainedThemeSwitchSplitsFiftyMinuteWindow() {
    var records = concat(block(0, 30, "social", ""), block(30, 20, "development", "rei"));
    var result = summarize(records);
    assertEquals(2, result.size());
    assertEquals(START.plusSeconds(30 * 60), result.getFirst().endedAt());
    assertEquals(START.plusSeconds(30 * 60), result.getLast().startedAt());
    assertEquals(records, result.stream().flatMap(s -> s.evidence().stream()).toList());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void projectSwitchSplitsEvenShortWindow() {
    var result =
        summarize(
            concat(
                block(0, 10, "development", "rei"),
                block(10, 10, "documentation", "yagisan-reports")));
    assertEquals(2, result.size());
    assertEquals(List.of("rei"), result.getFirst().projects());
    assertEquals(List.of("yagisan-reports"), result.getLast().projects());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sustainedWorkToLeisureSplitsButBriefDetourDoesNot() {
    assertEquals(
        2, summarize(concat(block(0, 10, "development", ""), block(10, 10, "media", ""))).size());
    var detour =
        concat(
            concat(block(0, 20, "development", "rei"), block(20, 2, "social", "")),
            block(22, 30, "development", "rei"));
    assertEquals(1, summarize(detour).size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameLeisureFamilyCanStayTogether() {
    assertEquals(
        1, summarize(concat(block(0, 15, "social", ""), block(15, 15, "media", ""))).size());
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void softLimitTriggersCategoryBoundarySearchWithoutFixedTimeCut() {
    var records = concat(block(0, 30, "development", "rei"), block(30, 25, "documentation", "rei"));
    var result = summarize(records);
    assertEquals(2, result.size());
    assertEquals(START.plusSeconds(1800), result.getLast().startedAt());
    assertEquals(
        1,
        new TrendSummaryPolicy(
                ZoneOffset.UTC,
                .5,
                Duration.ofMinutes(60),
                Duration.ofMinutes(20),
                Duration.ofMinutes(60))
            .aggregate(fine(records.toArray(ActivityRecord[]::new)))
            .size());
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void longGapCanSplitSoftLimitWithoutCountingMissingTimeAsObserved() {
    var records = concat(block(0, 20, "development", "rei"), block(30, 20, "development", "rei"));
    var result = summarize(records);
    assertEquals(2, result.size());
    assertEquals(2400, result.stream().mapToLong(TrendSummarySegment::observedSeconds).sum());
    assertEquals(records, result.stream().flatMap(s -> s.evidence().stream()).toList());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownBetweenStableThemesDoesNotHideSwitchOrLoseEvidence() {
    var records =
        concat(
            concat(block(0, 10, "development", "rei"), List.of(unknown(10))),
            block(11, 10, "social", ""));
    var result = summarize(records);
    assertEquals(2, result.size());
    assertEquals(records, result.stream().flatMap(s -> s.evidence().stream()).toList());
    assertEquals(60, result.stream().mapToLong(TrendSummarySegment::unknownSeconds).sum());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void evidenceBasedExamplesAreExportable() throws Exception {
    var continuous = summarize(block(0, 38, "development", "rei"));
    var mixed =
        trends(
            sample(0, "development", "x browsing"),
            sample(2, "social", ""),
            sample(4, "development", "x browsing"));
    var split = summarize(concat(block(0, 30, "social", ""), block(30, 20, "development", "rei")));
    String output =
        "Synthetic examples (not a replay of the user's database)\n"
            + text(continuous)
            + "\n"
            + text(mixed)
            + "\n"
            + text(split);
    assertFalse(output.contains("x browsing"));
    assertTrue(output.contains("混在"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/activity34-example.txt"), output);
  }

  /** 作業状況の把握で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void splitInspectsEvidenceInsideExistingSummaryProjection() {
    var records = concat(block(0, 25, "social", ""), block(25, 25, "development", "rei"));
    var roles = new ActivityRolePolicy(.5).classify(records.getFirst());
    var source =
        new SummarySegment(
            START, START.plusSeconds(3000), 3000, roles, records, List.of("fine-source"));
    var result = new TrendSummaryPolicy(ZoneOffset.UTC, .5).aggregate(List.of(source));
    assertEquals(2, result.size());
    assertEquals(START.plusSeconds(1500), result.getLast().startedAt());
    assertEquals(records, result.stream().flatMap(s -> s.evidence().stream()).toList());
    assertTrue(result.stream().allMatch(s -> s.sourceSegments().equals(List.of(source))));
    assertEquals(3000, result.stream().mapToLong(TrendSummarySegment::observedSeconds).sum());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void continuousStrongEvidenceUsesNaturalActivityWording() {
    String result = text(summarize(block(0, 38, "development", "rei")));
    assertTrue(result.contains("rei関連の開発・確認が中心"));
    assertFalse(result.contains("観測できた範囲では"));
    assertFalse(result.contains("に関する画面"));
    assertFalse(result.contains("バグ修正"));
    assertFalse(result.contains("集中して"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sparseObservationsKeepIntermittentWordingWithoutRepeatedQualification() {
    String result = text(trends(sample(0, "development", "rei"), sample(15, "development", "rei")));
    assertFalse(result.contains("観測できた範囲では"));
    assertTrue(result.contains("断続的"));
    assertTrue(result.startsWith("画面の観測に基づく振り返りです。"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void weakPrimaryConfidenceDoesNotGetStrongContinuousWording() {
    var r = sample(0, "development", "rei");
    r =
        new ActivityRecord(
            r.id(),
            r.capturedAt(),
            60,
            r.observations(),
            r.foreground(),
            r.inference(),
            .6,
            r.screenshotReferences(),
            .1,
            false,
            r.continuityId());
    String result = text(trends(r));
    assertFalse(result.contains("観測できた範囲では"));
    assertFalse(result.contains("が中心"));
    assertTrue(result.contains("関連の画面が断続的に表示されていました。"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void mixedAndUnknownWordingStayCautious() {
    String mixed =
        text(
            trends(
                sample(0, "development", "rei"),
                sample(2, "social", ""),
                sample(4, "development", "rei")));
    assertTrue(mixed.contains("混在"));
    assertFalse(mixed.contains("観測できた範囲では"));
    String unknown = text(trends(unknown(0)));
    assertTrue(unknown.contains("主活動は判定できません"));
    assertFalse(unknown.contains("が中心"));
    assertFalse(unknown.contains("観測できた範囲でも"));
  }
}
