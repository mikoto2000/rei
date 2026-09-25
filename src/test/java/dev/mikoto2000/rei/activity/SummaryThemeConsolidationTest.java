package dev.mikoto2000.rei.activity;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static dev.mikoto2000.rei.activity.DailySummaryTest.*;
import static dev.mikoto2000.rei.activity.ThemeAttributionTest.sample;
class SummaryThemeConsolidationTest {
  @TempDir Path dir;
  static final String CONFIG="""
      projectAliases:
        sensevoice-input: [sensevoice, sensevoiceinput, sensorvoice-input]
      themeGroups:
        speech-input:
          displayName: 音声入力・文字起こし系
          projects: [sensevoice, whisper-live-transcriber]
          themes: [音声入力, 文字起こし]
        speech-translation:
          displayName: 音声翻訳系
          projects: [livevingo, my-translator, livetrans, palabra]
          themes: [翻訳, 音声翻訳]
      """;
  static List<SummarySegment> fixture() {
    return List.of(sample(0,1200,"sensevoice",""),sample(20,600,"sensevoice-input",""),
        sample(30,1200,"whisper-live-transcriber",""),sample(60,1200,"livevingo","translation"),
        sample(80,1200,"my-translator",""),sample(100,3600,"","speech translation"),
        sample(180,1200,"rei",""),sample(210,600,"",""));
  }
  DailySummaryAggregate configured(List<SummarySegment> source,String yaml) throws Exception {
    var file=dir.resolve("summary.yaml");Files.writeString(file,yaml);
    var captured=new java.util.concurrent.atomic.AtomicReference<DailySummaryAggregate>();
    new DailySummaryService(new ProjectAliasStore(file),a->{captured.set(a);return DailySummary.fallback(a);})
        .summarize(DailySummaryTest.DATE,RANGE,java.time.ZoneOffset.UTC,.5,source);
    return captured.get();
  }
  @Test void groupsReplaceMembersAndCoveredThemeOnlyCandidates() throws Exception {
    var a=configured(fixture(),CONFIG);
    assertThat(a.dominantThemes()).containsExactlyInAnyOrder("音声入力・文字起こし系の開発","音声翻訳系の開発","rei の開発");
    assertThat(a.timeOfDay().get("lateNight").workThemes()).containsExactlyInAnyOrder("音声入力・文字起こし系の開発","音声翻訳系の開発");
  }
  @Test void aliasesCountAsOneMemberAndDoNotCreateArtificialGroupSupport() throws Exception {
    var a=configured(List.of(sample(0,1200,"sensevoice",""),sample(20,1200,"sensevoice-input","")),CONFIG);
    assertThat(a.dominantThemes()).containsExactly("sensevoice-input の開発");
    assertThat(a.mainWorkThemeCandidates().getFirst().memberProjectCount()).isEqualTo(1);
  }
  @Test void singleDominantProjectRetainsDetailInsteadOfWeakGroup() throws Exception {
    var a=configured(List.of(sample(0,3300,"livevingo","translation"),sample(55,300,"my-translator","")),CONFIG);
    assertThat(a.dominantThemes()).contains("livevingo の翻訳").noneMatch(s->s.startsWith("音声翻訳系"));
  }
  @Test void canonicalNamesSurviveConfiguredGroupPipeline() throws Exception {
    var a=configured(fixture(),CONFIG);
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(a);
    assertThat(json).doesNotContain("\"sensevoice\"","sensevoice の","sensorvoice-input");
  }
  @Test void weightedCategoriesAreLimitedAndMemberDurationIsNotDoubleCounted() throws Exception {
    var source=List.of(segment(0,7200,"development","sensevoice","",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(120,3600,"documentation","whisper-live-transcriber","",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(180,600,"research","sensevoice-input","",EntertainmentDisposition.NON_ENTERTAINMENT));
    var a=configured(source,CONFIG);var c=a.mainWorkThemeCandidates().getFirst();
    assertThat(c.level()).isEqualTo(SummaryThemeCandidate.Level.GROUP);
    assertThat(c.displayTheme()).isEqualTo("音声入力・文字起こし系");
    assertThat(c.durationSeconds()).isEqualTo(11400);assertThat(c.observationCount()).isEqualTo(3);
    assertThat(c.activities()).containsExactly("development","documentation");
    assertThat(c.memberProjectCount()).isEqualTo(2);
    assertThat(c.label()).isEqualTo("音声入力・文字起こし系の開発・文書作業");
    assertThat(a.categorySeconds()).containsEntry("research",600L);
  }
  @Test void groupingRunsBeforeTopFiveTruncation() throws Exception {
    var source=new ArrayList<SummarySegment>();
    for(int i=0;i<6;i++)source.add(sample(i*30,1800,"other-"+i,""));
    source.add(sample(200,1200,"sensevoice",""));source.add(sample(220,1200,"whisper-live-transcriber",""));
    assertThat(configured(source,CONFIG).dominantThemes()).contains("音声入力・文字起こし系の開発").hasSize(5);
  }
  @Test void scopeCoverageCanKeepProjectsForDayButGroupThemWithinBucket() throws Exception {
    var a=configured(List.of(sample(0,600,"sensevoice",""),sample(10,600,"whisper-live-transcriber",""),
        sample(360,36000,"other-project","")),CONFIG);
    assertThat(a.dominantThemes()).noneMatch(s->s.startsWith("音声入力・文字起こし系"));
    assertThat(a.timeOfDay().get("lateNight").workThemes()).containsExactly("音声入力・文字起こし系の開発");
  }
  @Test void onlyExplicitlyCoveredPartsOfThemeOnlyCandidateAreSuppressed() throws Exception {
    var a=configured(List.of(sample(0,1200,"livevingo","translation"),sample(20,1200,"my-translator",""),
        sample(40,1200,"","speech translation feed summary")),CONFIG);
    assertThat(a.dominantThemes()).contains("音声翻訳系の開発","フィード要約").doesNotContain("音声翻訳","livevingo の翻訳");
  }
  @Test void noGroupDoesNotGuessFromSimilarNamesAndKeepsStrongAssociation() throws Exception {
    var a=configured(List.of(sample(0,1200,"livevingo","translation"),sample(20,1200,"livetrans","")), "projectAliases: {}");
    assertThat(a.dominantThemes()).containsExactlyInAnyOrder("livevingo の翻訳","livetrans の開発");
    assertThat(a.mainWorkThemeCandidates()).allMatch(c->c.level()==SummaryThemeCandidate.Level.PROJECT);
  }
  @Test void explicitSingleProjectDisplayGroupIsOptional() throws Exception {
    String yaml="""
        themeGroups:
          vision:
            displayName: DeepSeek / Vision関連
            projects: [deepseek-vl-flash-vision-exp]
            allowSingleProject: true
        """;
    var source=List.of(sample(0,1200,"deepseek-vl-flash-vision-exp",""));
    assertThat(configured(source,yaml).dominantThemes()).containsExactly("DeepSeek / Vision関連の開発");
    assertThat(configured(source,yaml.replace("true","false")).dominantThemes()).containsExactly("deepseek-vl-flash-vision-exp の開発");
  }
  @Test void groupValidationRetainsAliasesAndGroupsAtomically() throws Exception {
    var file=dir.resolve("validation.yaml");Files.writeString(file,CONFIG);
    var store=new ProjectAliasStore(file);var good=store.snapshot();
    var invalid=List.of(
        CONFIG.replace("displayName: 音声翻訳系","displayName: ''"),
        CONFIG.replace("projects: [livevingo, my-translator, livetrans, palabra]","projects: [sensevoice-input]"),
        CONFIG.replace("projects: [sensevoice, whisper-live-transcriber]","projects: [project]"),
        CONFIG.replace("themes: [翻訳, 音声翻訳]","themes: [文字起こし]"),
        CONFIG.replace("themes: [翻訳, 音声翻訳]","themes: [made-up-topic]"),
        CONFIG.replace("speech-translation:","speech-input:"),
        CONFIG.replace("displayName: 音声翻訳系","displayName: 音声入力・文字起こし系"),
        CONFIG.replace("themes: [翻訳, 音声翻訳]","allowSingleProject: maybe"),
        CONFIG.replace("themes: [翻訳, 音声翻訳]","unknownSetting: true"));
    for(var text:invalid) {
      Files.writeString(file,text);assertThat(store.snapshot()).isSameAs(good);
      assertThat(store.get().project("sensevoice")).isEqualTo("sensevoice-input");
    }
    Files.writeString(file,CONFIG.replace("音声翻訳系","翻訳ツール群"));
    assertThat(store.snapshot().groups().groups()).anyMatch(g->g.displayName().equals("翻訳ツール群"));
    Files.delete(file);assertThat(store.snapshot().groups().groups()).isEmpty();
    assertThat(store.get().project("sensevoice")).isEqualTo("sensevoice");
  }
  @Test void aliasChangesRevalidateGroupMembershipRatherThanUsingStaleNames() throws Exception {
    var file=dir.resolve("reload.yaml");Files.writeString(file,CONFIG);var store=new ProjectAliasStore(file);
    assertThat(store.snapshot().groups().groups().getFirst().projects()).contains("sensevoice-input");
    Files.writeString(file,CONFIG.replace("sensevoice-input:","voice-input:"));
    assertThat(store.snapshot().groups().groups().getFirst().projects()).contains("voice-input").doesNotContain("sensevoice-input");
  }
  @Test void failedWriterUsesConsolidatedFallbackAndRejectsChildOutput() throws Exception {
    var a=configured(fixture(),CONFIG);var fallback=DailySummary.fallback(a);
    assertThatThrownBy(()->new DailySummary(fallback.overview(),fallback.timeOfDay(),List.of("livevingo の翻訳"),
        fallback.nonWorkActivities(),fallback.trend()).validated(a)).isInstanceOf(IllegalArgumentException.class);
    var file=dir.resolve("fallback.yaml");Files.writeString(file,CONFIG);
    var service=new DailySummaryService(new ProjectAliasStore(file),ignored->{throw new IllegalStateException();});
    assertThat(service.summarize(DailySummaryTest.DATE,RANGE,java.time.ZoneOffset.UTC,.5,fixture()))
        .contains("音声入力・文字起こし系の開発","音声翻訳系の開発").doesNotContain("livevingo の翻訳","sensevoice の");
  }
  @Test void weakAssociationsAndInvalidProjectsDoNotReturnThroughConsolidation() throws Exception {
    var a=configured(List.of(sample(0,60,"rei","transcription"),sample(2,600,"rei",""),sample(20,600,"project","")),CONFIG);
    assertThat(a.dominantThemes()).contains("rei の開発").doesNotContain("rei の文字起こし","project の開発");
  }
  @Test void twoHundredSegmentsRemainBoundedAndCategoriesUnchanged() throws Exception {
    var source=new ArrayList<SummarySegment>();
    for(int i=0;i<200;i++)source.add(sample(i*7,120,i%2==0?"sensevoice":"whisper-live-transcriber",""));
    var a=configured(source,CONFIG);var before=aggregate(source);
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(a);
    assertThat(json.length()).isLessThan(16000);
    assertThat(a.categorySeconds()).isEqualTo(before.categorySeconds());assertThat(a.entertainmentSeconds()).isEqualTo(before.entertainmentSeconds());
    assertThat(a.dominantThemes()).containsExactly("音声入力・文字起こし系の開発");
    assertThat(a.timeOfDay().values()).allMatch(b->b.workThemes().size()<=2);
    assertThat(new DailySummaryFormatter().format(a,DailySummary.fallback(a)).length()).isLessThan(2400);
  }
  @Test void shortSpecificThemeDoesNotClaimToDominateLongGenericWork() throws Exception {
    var a=configured(List.of(sample(0,18000,"",""),sample(300,14400,"rei","")), "projectAliases: {}");
    assertThat(DailySummary.fallback(a).timeOfDay().get("lateNight")).contains("rei").doesNotContain("中心");
  }
  @Test void beforeAfterComparison() throws Exception {
    var out=new StringBuilder();
    for(var source:List.of(fixture(),largeDay())) {
      var before=aggregate(source);var after=configured(source,CONFIG);
      for(var a:List.of(before,after)) {
        var s=DailySummary.fallback(a);out.append(ThemeEnrichmentTest.metrics(a,s)).append("\n").append(a.consolidationMetrics()).append("\n\n")
            .append(new DailySummaryFormatter().format(a,s)).append("\n\n");
      }
    }
    Files.writeString(Path.of("target/theme-consolidation-comparison.txt"),out);
  }
}
