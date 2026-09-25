package dev.mikoto2000.rei.activity;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static dev.mikoto2000.rei.activity.DailySummaryTest.*;
import static dev.mikoto2000.rei.activity.EntertainmentDisposition.*;
class ThemeEnrichmentTest {
  static List<SummarySegment> differentProjects() {
    try(var in=ThemeEnrichmentTest.class.getResourceAsStream("/activity/daily-summary-social-projects.csv")) {
      return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).filter(s->!s.isBlank()).map(line->{
        var p=line.split(",",-1);return segment(Integer.parseInt(p[0]),Integer.parseInt(p[1]),p[2],p[3],p[4],EntertainmentDisposition.valueOf(p[5]));
      }).toList();
    } catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
  }
  static SummarySegment withText(SummarySegment s,String title,String summary) {
    var r=s.evidence().getFirst();var p=r.inference().activities().getFirst();
    var activity=new ActivityRecord.Activity(p.monitor(),p.type(),p.application(),p.service(),title,p.projectCandidate());
    var record=new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),
        new ActivityRecord.Inference(summary,List.of(activity)),r.confidence(),r.screenshotReferences(),r.changeAmount(),r.duplicate(),r.continuityId(),r.detection());
    return new SummarySegment(s.startedAt(),s.endedAt(),s.observedSeconds(),new ActivityRolePolicy().classify(record),List.of(record),List.of());
  }
  @Test void savedTitleAndSummarySupplyTopicsWithoutInventingFromIdentifiers() {
    var a=aggregate(List.of(withText(segment(0,600,"development","rei","",NON_ENTERTAINMENT),"Activity classification","AgentEventFactory"),
        withText(segment(20,600,"research","sensevoice","",NON_ENTERTAINMENT),"","音声入力")));
    assertThat(a.dominantThemes()).contains("rei のActivity分類","sensevoice-input の音声入力");
    assertThat(a.projectThemeStats()).allMatch(s->s.specificity()==3);
    var plain=aggregate(List.of(withText(segment(0,600,"development","rei","",NON_ENTERTAINMENT),"AgentEventFactory project.cd build_and_chat","API")));
    assertThat(plain.dominantThemes()).containsExactly("rei の開発");
  }
  @Test void noProjectUsesExplicitTopicThenGenericFallback() {
    var topic=aggregate(List.of(withText(segment(0,600,"research","","",NON_ENTERTAINMENT),"Vision model","")));
    assertThat(topic.dominantThemes()).containsExactly("Visionモデル");
    var generic=aggregate(List.of(segment(0,600,"research","","",NON_ENTERTAINMENT)));
    assertThat(generic.dominantThemes()).containsExactly("調査");
  }
  @Test void shortIncidentalProjectDoesNotSuppressMeaningfulGenericWork() {
    var a=aggregate(List.of(segment(0,18000,"development","","",NON_ENTERTAINMENT),
        segment(300,30,"development","incidental","",NON_ENTERTAINMENT)));
    assertThat(a.dominantThemes()).containsExactly("開発");
  }
  @Test void statsCountClippedObservationsAndContinuityWithoutCountingGaps() {
    var a=aggregate(List.of(segment(0,300,"development","rei","",NON_ENTERTAINMENT),
        segment(5,300,"research","rei","",NON_ENTERTAINMENT),segment(60,300,"documentation","rei","",NON_ENTERTAINMENT)));
    var stat=a.projectThemeStats().getFirst();
    assertThat(stat.observedSeconds()).isEqualTo(900);assertThat(stat.observationCount()).isEqualTo(3);
    assertThat(stat.longestContinuousSeconds()).isEqualTo(600);
    assertThat(stat.score()).isGreaterThan(900);
    assertThat(a.categorySeconds()).containsEntry("development",300L).containsEntry("research",300L).containsEntry("documentation",300L);
  }
  @Test void implementationNamesAndProjectFamiliesAreNotInferred() {
    for(var name:List.of("build_and_chat","software_development","AgentEventFactory","project.cd","suggest-rules","repl"))
      assertThat(NAMES.project(name)).isEmpty();
    var a=aggregate(List.of(segment(0,600,"development","livevingo","",NON_ENTERTAINMENT),
        segment(20,600,"development","livetrans","",NON_ENTERTAINMENT),segment(40,600,"development","palabra","",NON_ENTERTAINMENT)));
    assertThat(a.dominantThemes()).hasSize(3).noneMatch(s->s.contains("翻訳") || s.contains("音声"));
  }
  @Test void meaningfulWorkSurvivesMostlyUnknownBucketAndAiValidationRejectsRepetition() {
    var a=aggregate(List.of(segment(0,18000,"unknown","","",UNCERTAIN),
        segment(300,600,"development","rei","",NON_ENTERTAINMENT)));
    assertThat(DailySummary.fallback(a).timeOfDay().get("lateNight")).contains("rei");
    var ai=aggregate(differentProjects());var fallback=DailySummary.fallback(ai);
    assertThat(fallback.validated(ai)).isEqualTo(fallback);
    assertThatThrownBy(()->new DailySummary(fallback.overview()+"AI支援",fallback.timeOfDay(),
        fallback.workThemes(),fallback.nonWorkActivities(),fallback.trend()).validated(ai)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void fallbackFailureKeepsEnrichedSavedTopic() {
    var source=List.of(withText(segment(0,600,"development","rei","",NON_ENTERTAINMENT),"Activity分類",""));
    var service=new DailySummaryService(()->NAMES,a->{throw new IllegalStateException();});
    assertThat(service.summarize(DailySummaryTest.DATE,RANGE,java.time.ZoneOffset.UTC,.5,source)).contains("rei のActivity分類");
  }
  static String metrics(DailySummaryAggregate a,DailySummary s) {
    var generic=Set.of("開発","調査","文書作業","コミュニケーション");
    return "themes="+s.workThemes().size()+", generic="+s.workThemes().stream().filter(generic::contains).count()
        +", repeatedBuckets="+(s.timeOfDay().size()-new HashSet<>(s.timeOfDay().values()).size())
        +", chars="+new DailySummaryFormatter().format(a,s).length();
  }
  @Test void comparison() throws Exception {
    var out=new StringBuilder();
    for(var source:List.of(largeDay(),differentProjects())) {
      var a=aggregate(source);var s=DailySummary.fallback(a);
      out.append(metrics(a,s)).append("\n\n").append(new DailySummaryFormatter().format(a,s)).append("\n\n");
    }
    Files.writeString(Path.of("target/theme-enrichment-comparison.txt"),out);
  }
  @Test void genericIsSuppressedWhenSpecificWorkExists() {
    var a=aggregate(List.of(segment(0,18000,"development","","",NON_ENTERTAINMENT),
        segment(300,14400,"development","rei","",NON_ENTERTAINMENT)));
    assertThat(a.dominantThemes()).containsExactly("rei の開発");
  }
  @Test void sameCanonicalProjectMergesCategories() {
    var a=aggregate(List.of(segment(0,600,"development","sensevoice","",NON_ENTERTAINMENT),
        segment(20,600,"research","sensevoiceinput","",NON_ENTERTAINMENT),
        segment(40,600,"documentation","sensevoice-input","",NON_ENTERTAINMENT)));
    assertThat(a.dominantThemes()).hasSize(1);
    assertThat(a.dominantThemes().getFirst()).contains("sensevoice-input","開発","調査","文書作業");
  }
  @Test void socialDominanceRetainsEachBucketsProjectAndDoesNotRepeatAi() {
    var a=aggregate(differentProjects());var s=DailySummary.fallback(a);
    assertThat(s.timeOfDay().values()).hasSize(4).doesNotHaveDuplicates();
    for(int b=0;b<4;b++) {
      assertThat(a.timeOfDay().get(DailySummaryAggregate.BUCKET_ORDER.get(b)).workThemes()).hasSize(1);
      assertThat(s.timeOfDay().get(DailySummaryAggregate.BUCKET_ORDER.get(b))).contains("SNS閲覧").doesNotContain("AI支援");
    }
    assertThat(s.overview()+s.trend()).containsOnlyOnce("AI支援");
  }
}
