package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.mikoto2000.rei.activity.DailySummaryTest.*;
import static dev.mikoto2000.rei.activity.ThemeAttributionTest.sample;
class SingleProjectDisplayTest {
  @TempDir Path dir;
  static List<SummarySegment> fixture() {
    return List.of(segment(0,360,"documentation","sensevoice","",EntertainmentDisposition.NON_ENTERTAINMENT),
        sample(6,300,"deepseek-vl-flash-vision-exp",""),
        sample(360,360,"sensevoice-input",""),sample(366,360,"rei",""),
        segment(720,360,"documentation","sensevoiceinput","",EntertainmentDisposition.NON_ENTERTAINMENT),
        sample(1080,360,"livevingo","translation"),sample(1086,6000,"","transcription speech translation"));
  }
  static String config() throws Exception {return Files.readString(Path.of("docs/testdata/summary-theme-groups-2026-09-24.yaml"));}
  ProjectAliasStore store(String yaml) throws Exception {var file=dir.resolve("summary.yaml");Files.writeString(file,yaml);return new ProjectAliasStore(file);}
  static DailySummaryAggregate aggregate(ProjectAliasStore store) {
    var c=store.snapshot();return new DailySummaryAggregator(c.projects(),.5,c.groups()).aggregate(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,fixture());
  }
  static String render(DailySummaryAggregate a){return new DailySummaryFormatter().format(a,DailySummary.fallback(a));}
  @Test void lowCoverageAndShortScopesStillUseConfiguredDisplayEverywhere() throws Exception {
    var a=aggregate(store(config()));var text=render(a);
    assertThat(a.mainWorkThemeCandidates()).allMatch(c->c.level()==SummaryThemeCandidate.Level.PROJECT);
    assertThat(a.mainWorkThemeCandidates()).filteredOn(c->!c.memberProjects().contains("rei"))
        .allMatch(c->c.displaySource()==SummaryThemeCandidate.DisplaySource.THEME_GROUP);
    assertThat(a.dominantThemes()).anyMatch(s->s.startsWith("音声入力・文字起こし系")).anyMatch(s->s.startsWith("音声翻訳系"))
        .contains("DeepSeek / Vision関連の開発","rei の開発");
    assertThat(DailySummary.fallback(a).overview()).contains("音声入力・文字起こし系");
    for(var key:List.of("lateNight","morning","afternoon"))
      assertThat(a.timeOfDay().get(key).workThemes()).anyMatch(s->s.startsWith("音声入力・文字起こし系"));
    assertThat(a.timeOfDay().get("lateNight").workThemes()).contains("DeepSeek / Vision関連の開発");
    assertThat(a.timeOfDay().get("evening").workThemes()).contains("音声翻訳系の開発");
    assertClean(text);
  }
  @Test void displayReplacementPreservesEvidenceScoreAndCanonicalMetadata() throws Exception {
    var before=aggregate(store(config().replace("true","false")));var after=aggregate(store(config()));
    assertThat(after.categorySeconds()).isEqualTo(before.categorySeconds());
    assertThat(after.entertainmentSeconds()).isEqualTo(before.entertainmentSeconds());
    assertThat(after.observedSeconds()).isEqualTo(before.observedSeconds()).isEqualTo(8100);
    assertThat(after.mainWorkThemeCandidates()).hasSize(before.mainWorkThemeCandidates().size());
    for(var c:after.mainWorkThemeCandidates()) {
      var prior=before.mainWorkThemeCandidates().stream().filter(p->p.memberProjects().equals(c.memberProjects())).findFirst().orElseThrow();
      assertThat(c.score()).isEqualTo(prior.score());
      assertThat(c.durationSeconds()).isEqualTo(prior.durationSeconds());
      assertThat(c.observationCount()).isEqualTo(prior.observationCount());
      assertThat(c.associationConfidence()).isEqualTo(prior.associationConfidence());
    }
    assertThat(after.mainWorkThemeCandidates()).anySatisfy(c->{
      assertThat(c.memberProjects()).containsExactly("sensevoice-input");
      assertThat(c.displayTheme()).isEqualTo("音声入力・文字起こし系");
    });
    assertThat(fixture().getFirst().evidence().getFirst().inference().activities().getFirst().projectCandidate()).isEqualTo("sensevoice");
  }
  @Test void disabledMissingAndHotReloadedDisplayRespectUserConfiguration() throws Exception {
    var file=dir.resolve("reload.yaml");Files.writeString(file,config().replace("true","false"));var store=new ProjectAliasStore(file);
    assertThat(render(aggregate(store))).contains("sensevoice-input の","livevingo の","deepseek-vl-flash-vision-exp の");
    Files.writeString(file,config().replace("    allowSingleProject: true\n",""));
    assertThat(render(aggregate(store))).contains("sensevoice-input の","livevingo の");
    Files.writeString(file,config().replace("音声入力・文字起こし系","カスタム音声ツール"));
    assertThat(render(aggregate(store))).contains("カスタム音声ツール").doesNotContain("sensevoice-input の");
    Files.writeString(file,"projectAliases: {}");
    assertThat(render(aggregate(store))).contains("sensevoice の","livevingo の");
  }
  @ParameterizedTest @ValueSource(strings={"success","invalid","failure"})
  void actualWriterAndFallbackNeverExposeCanonicalProvenance(String mode) throws Exception {
    var store=store(config());var a=aggregate(store);var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenAnswer(call->{
      String input=((Prompt)call.getArgument(0)).getUserMessage().getText();
      assertClean(input);
      assertThat(input).contains("音声入力・文字起こし系","音声翻訳系","DeepSeek / Vision関連");
      if(mode.equals("failure"))return Flux.error(new IllegalStateException("fixture"));
      var json=ConsolidationPipelineTest.JSON.valueToTree(DailySummary.fallback(a));
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(mode.equals("invalid")?"{}":json.toString())))));
    });
    var result=new DailySummaryService(store,new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(2)))
        .summarize(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,.5,fixture());
    verify(model).stream(any(Prompt.class));assertClean(result);
    assertThat(result).contains("音声入力・文字起こし系","音声翻訳系","DeepSeek / Vision関連");
  }
  @Test void multipleWeakMembersWithSameDisplayDoNotCreateExtraEvidence() throws Exception {
    var config=store(config().replace("projects: [sensevoice-input]","projects: [sensevoice-input, whisper]")).snapshot();
    var source=List.of(sample(0,400,"sensevoice",""),sample(10,300,"whisper",""),sample(20,6000,"","transcription"));
    var a=new DailySummaryAggregator(config.projects(),.5,config.groups()).aggregate(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,source);
    assertThat(a.mainWorkThemeCandidates()).hasSize(1);
    var candidate=a.mainWorkThemeCandidates().getFirst();
    assertThat(candidate.level()).isEqualTo(SummaryThemeCandidate.Level.PROJECT);
    assertThat(candidate.label()).isEqualTo("音声入力・文字起こし系の開発");
    assertThat(candidate.durationSeconds()).isEqualTo(400);
    assertThat(candidate.observationCount()).isEqualTo(1);
    assertThat(candidate.memberProjects()).containsExactly("sensevoice-input");
    assertThat(a.observedSeconds()).isEqualTo(6700);
  }
  @Test void phase383FixtureKeepsShortDisplayAndConfiguredNames() throws Exception {
    var c=store(config()).snapshot();
    var a=new DailySummaryAggregator(c.projects(),.5,c.groups()).aggregate(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,ConsolidationPipelineTest.fixture());
    assertClean(render(a));
    assertThat(a.dominantThemes()).contains("音声入力・文字起こし系の文書作業","音声翻訳系の開発","rei の開発","DeepSeek / Vision関連の開発");
    assertThat(render(a).length()).isLessThan(500);
  }
  static void assertClean(String value) {
    assertThat(value).doesNotContain("sensevoice","livevingo","deepseek-vl-flash-vision-exp","音声翻訳系の翻訳");
  }
}
