package dev.mikoto2000.rei.activity;

import static dev.mikoto2000.rei.activity.DailySummaryTest.aggregate;
import static dev.mikoto2000.rei.activity.DailySummaryTest.segment;
import static dev.mikoto2000.rei.activity.EntertainmentDisposition.ENTERTAINMENT;
import static dev.mikoto2000.rei.activity.EntertainmentDisposition.NON_ENTERTAINMENT;
import static dev.mikoto2000.rei.activity.EntertainmentDisposition.UNCERTAIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 日次要約Wordingの結果と状態の契約を検証するテスト。 */
class DailySummaryWordingTest {
  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void groupPhrasesPreserveThemePunctuationAndSupportThreeActivities() {
    String theme = "音声入力・文字起こし系";
    assertThat(ThemeActivityFormatter.format(theme, List.of("development"), false))
        .isEqualTo(theme + "の開発");
    assertThat(ThemeActivityFormatter.format(theme, List.of("documentation", "development"), false))
        .isEqualTo(theme + "に関する文書作業や開発");
    assertThat(
            ThemeActivityFormatter.format(
                theme, List.of("documentation", "development", "research"), false))
        .isEqualTo(theme + "に関する文書作業や開発、調査");
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void workFreeBucketsRetainTheirOnlyKnownActivityEvenWhenUnknownIsLargest() {
    var source = new ArrayList<SummarySegment>();
    for (int minute : List.of(0, 360)) {
      source.add(segment(minute, 1800, "unknown", "", "", UNCERTAIN));
      source.add(segment(minute + 30, 600, "social", "", "", ENTERTAINMENT));
    }
    var a = aggregate(source);
    assertThat(DailySummary.fallback(a).timeOfDay().values())
        .containsExactly("SNS閲覧が中心でした。", "SNS閲覧が中心でした。");
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void oneActivityStaysConcise() {
    assertThat(
            aggregate(List.of(segment(0, 1800, "development", "rei", "", NON_ENTERTAINMENT)))
                .dominantThemes())
        .containsExactly("rei の開発");
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void twoActivitiesUseNaturalConjunction() {
    assertThat(
            aggregate(
                    List.of(
                        segment(0, 1800, "documentation", "rei", "", NON_ENTERTAINMENT),
                        segment(30, 600, "development", "rei", "", NON_ENTERTAINMENT)))
                .dominantThemes())
        .containsExactly("rei に関する文書作業や開発");
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void threeActivitiesUseNaturalConjunction() {
    assertThat(
            aggregate(
                    List.of(
                        segment(0, 1800, "documentation", "rei", "", NON_ENTERTAINMENT),
                        segment(30, 1200, "development", "rei", "", NON_ENTERTAINMENT),
                        segment(50, 600, "research", "rei", "", NON_ENTERTAINMENT)))
                .dominantThemes())
        .containsExactly("rei に関する文書作業や開発、調査");
  }

  /** {@code repeated} の条件を再現するための補助情報を返す。 */
  static DailySummaryAggregate repeated(String category, boolean dominantEvening) {
    var source = new ArrayList<SummarySegment>();
    for (int minute : List.of(0, 360, 720, 1080)) {
      boolean major = dominantEvening && minute == 1080;
      source.add(segment(minute, major ? 600 : 1800, "development", "rei", "", NON_ENTERTAINMENT));
      source.add(segment(minute + 30, major ? 1800 : 600, category, "", "", ENTERTAINMENT));
    }
    return aggregate(source);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @CsvSource({"social,SNS閲覧", "shopping,ショッピング", "media,動画・音楽の閲覧", "gaming,ゲーム"})
  void repeatedIncidentalLeisureIsKeptOnlyInDailyList(String category, String label) {
    var a = repeated(category, false);
    var summary = DailySummary.fallback(a);
    assertThat(summary.nonWorkActivities()).contains(label);
    assertThat(summary.timeOfDay()).hasSize(4);
    assertThat(summary.timeOfDay().values())
        .allSatisfy(s -> assertThat(s).contains("rei の開発").doesNotContain(label));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void dominantEveningSocialRemainsVisible() {
    var summary = DailySummary.fallback(repeated("social", true));
    assertThat(summary.timeOfDay().get("evening")).contains("SNS閲覧が多く", "rei の開発");
    assertThat(summary.timeOfDay().get("morning")).doesNotContain("SNS");
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void uniqueIncidentalActivityRemainsVisible() {
    var a =
        aggregate(
            List.of(
                segment(0, 1800, "development", "rei", "", NON_ENTERTAINMENT),
                segment(30, 600, "social", "", "", ENTERTAINMENT)));
    assertThat(DailySummary.fallback(a).timeOfDay().get("lateNight")).contains("SNS閲覧");
  }

  /** 作業状況の把握で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void rendererAlsoSuppressesRepetitionFromSuccessfulWriter() {
    var a = repeated("social", false);
    var fallback = DailySummary.fallback(a);
    var sections = new LinkedHashMap<String, String>();
    a.timeOfDay().keySet().forEach(k -> sections.put(k, "rei の開発が見られました。SNSも一部で見られました。"));
    var summary =
        new DailySummary(
                fallback.overview(),
                sections,
                fallback.workThemes(),
                fallback.nonWorkActivities(),
                fallback.trend())
            .validated(a);
    String text = new DailySummaryFormatter().format(a, summary);
    assertThat(text.substring(text.indexOf("時間帯別:"), text.indexOf("主な作業テーマ:")))
        .doesNotContain("SNS");
    assertThat(text).contains("- SNS閲覧");
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @CsvSource({"0,0時間0分0秒,24時間0分0秒", "86400,24時間0分0秒,0時間0分0秒", "83762,23時間16分2秒,0時間43分58秒"})
  void coverageLabelsDescribeDataAvailability(int seconds, String observed, String unobserved) {
    var a =
        aggregate(
            seconds == 0
                ? List.of()
                : List.of(segment(0, seconds, "development", "rei", "", NON_ENTERTAINMENT)));
    assertThat(new DailySummaryFormatter().format(a, DailySummary.fallback(a)))
        .contains("画面観測データあり: " + observed + " / 観測データなし: " + unobserved)
        .contains("PC利用時間や作業時間を表すものではありません")
        .doesNotContain("観測時間:");
  }
}
