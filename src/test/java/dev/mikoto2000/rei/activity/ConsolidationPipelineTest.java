package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
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
class ConsolidationPipelineTest {
  @TempDir Path dir;
  static final ObjectMapper JSON=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static List<SummarySegment> fixture() {
    return List.of(
        segment(0,1800,"documentation","sensevoice","",EntertainmentDisposition.NON_ENTERTAINMENT),
        sample(30,1200,"deepseek-vl-flash-vision-exp",""),
        segment(360,1800,"documentation","sensevoice-input","",EntertainmentDisposition.NON_ENTERTAINMENT),
        sample(390,1800,"rei",""),
        sample(1080,2400,"","transcription speech translation"),
        sample(1120,1800,"livevingo","translation"));
  }
  DailySummaryAggregate aggregate(ProjectAliasStore store) {
    var config=store.snapshot();
    return new DailySummaryAggregator(config.projects(),.5,config.groups()).aggregate(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,fixture());
  }
  String render(DailySummaryAggregate a){return new DailySummaryFormatter().format(a,DailySummary.fallback(a));}
  @Test void missingConfigurationReproducesRawAliasesAndHotReloadFixesBothScopes() throws Exception {
    var file=dir.resolve("summary.yaml");var store=new ProjectAliasStore(file);
    var before=aggregate(store);
    assertThat(before.dominantThemes()).contains("sensevoice の文書作業","sensevoice-input の文書作業","文字起こし・音声翻訳","livevingo の翻訳");
    assertThat(before.timeOfDay().get("lateNight").workThemes()).contains("sensevoice の文書作業");
    Files.writeString(file,SummaryThemeConsolidationTest.CONFIG);
    var after=aggregate(store);
    assertThat(after.dominantThemes()).contains("sensevoice-input の文書作業","livevingo の翻訳").doesNotContain("sensevoice の文書作業","文字起こし・音声翻訳","音声翻訳");
    assertThat(after.timeOfDay().get("lateNight").workThemes()).contains("sensevoice-input の文書作業");
    assertThat(after.timeOfDay().get("evening").workThemes()).contains("livevingo の翻訳","文字起こし").doesNotContain("文字起こし・音声翻訳");
    assertThat(render(after)).doesNotContain("sensevoice の","文字起こし・音声翻訳");
    Files.writeString(Path.of("target/consolidation-pipeline-comparison.txt"),
        "BEFORE chars="+render(before).length()+"\n"+render(before)+"\nAFTER chars="+render(after).length()+"\n"+render(after));
  }
  @Test void aliasAndGroupLookupNormalizeCaseSeparatorsAndWhitespace() throws Exception {
    var file=dir.resolve("summary.yaml");
    Files.writeString(file,SummaryThemeConsolidationTest.CONFIG.replace("projects: [sensevoice, whisper-live-transcriber]","projects: [' SENSEVOICE_INPUT ', whisper-live-transcriber]"));
    var config=new ProjectAliasStore(file).snapshot();
    for(var raw:List.of("sensevoice"," SenseVoice ","SENSEVOICE_INPUT","sensevoice input","sensevoiceinput","sensorvoice-input"))
      assertThat(config.projects().project(raw)).isEqualTo("sensevoice-input");
    assertThat(config.groups().groups().getFirst().projects()).contains("sensevoice-input");
  }
  @ParameterizedTest @ValueSource(strings={"success","invalid","failure"})
  void actualModelBoundaryAndFallbackUseFinalCandidates(String mode) throws Exception {
    var file=dir.resolve("summary.yaml");Files.writeString(file,SummaryThemeConsolidationTest.CONFIG);
    var store=new ProjectAliasStore(file);var a=aggregate(store);var model=mock(ChatModel.class);

    when(model.stream(any(Prompt.class))).thenAnswer(call->{
      var input=JSON.readTree(((Prompt)call.getArgument(0)).getUserMessage().getText());
      assertNoRawAlias(input);
      assertThat(input.has("topProjects")).isFalse();
      input.path("majorWorkBlocks").forEach(b->assertThat(b.has("theme")).isFalse());
      assertThat(input.path("mainWorkThemeCandidates").findValuesAsText("label")).doesNotContain("文字起こし・音声翻訳");
      assertThat(input.path("timeOfDay").path("evening").path("workThemes").toString()).doesNotContain("音声翻訳");
      if(mode.equals("failure"))return Flux.error(new IllegalStateException("test failure"));
      if(mode.equals("invalid"))return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
      var response=JSON.valueToTree(DailySummary.fallback(a));
      var times=(com.fasterxml.jackson.databind.node.ObjectNode)response.path("timeOfDay");
      for(var key:DailySummaryAggregate.BUCKET_ORDER)if(!times.has(key))times.putNull(key);
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(JSON.writeValueAsString(response))))));
    });
    var writer=new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(2));
    var result=new DailySummaryService(store,writer).summarize(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,.5,fixture());
    verify(model).stream(any(Prompt.class));
    assertThat(result).isEqualTo(render(a)).doesNotContain("sensevoice の","文字起こし・音声翻訳");
  }
  @Test void explicitSingleProjectExampleProducesGroupsAtModelBoundary() throws Exception {
    var file=dir.resolve("summary.yaml");
    Files.copy(Path.of("docs/testdata/summary-theme-groups-2026-09-24.yaml"),file);
    var store=new ProjectAliasStore(file);var a=aggregate(store);
    assertThat(a.dominantThemes()).containsExactlyInAnyOrder("音声入力・文字起こし系の文書作業","音声翻訳系の開発","rei の開発","DeepSeek / Vision関連の開発");
    // Low day-wide coverage keeps project-level evidence, but the configured display name is used in every scope.
    assertThat(a.timeOfDay().get("lateNight").workThemes()).contains("DeepSeek / Vision関連の開発");
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenAnswer(call->{
      var input=JSON.readTree(((Prompt)call.getArgument(0)).getUserMessage().getText());
      assertNoRawAlias(input);
      var candidates=input.path("mainWorkThemeCandidates");
      assertThat(candidates.findValuesAsText("label")).doesNotContain("livevingo の翻訳","sensevoice-input の文書作業");
      assertThat(candidates.findValuesAsText("level")).contains("GROUP");
      assertThat(candidates.toString()).contains("speech-input","speech-translation");
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
    });
    new DailySummaryService(store,new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(2)))
        .summarize(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,.5,fixture());
    verify(model).stream(any(Prompt.class));
    Files.writeString(Path.of("target/pipeline-single-project-example.txt"),render(a));
  }
  @Test void arithmeticDigitsAreNotPrivatePhoneNumbersButTextStillIs() throws Exception {
    var a=aggregate(new ProjectAliasStore(dir.resolve("missing.yaml")));
    // Exact pre-fix input: the long decimal score was mistakenly detected as PHONE_JP.
    assertThat(new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().detectPatterns(JSON.writeValueAsString(a))).contains("PHONE_JP");
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("model reached")));
    var writer=new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(2));
    assertThatThrownBy(()->writer.write(a)).hasMessageContaining("model reached");
    verify(model).stream(any(Prompt.class));
    reset(model);
    var privateNames=new ProjectNameNormalizer(Map.of("private-09012345678",List.of("sensevoice")));
    var privateAggregate=new DailySummaryAggregator(privateNames,.5).aggregate(DailySummaryTest.DATE,RANGE,ZoneOffset.UTC,fixture());
    assertThatThrownBy(()->writer.write(privateAggregate)).hasMessage("Unsafe summary input");
    verifyNoInteractions(model);
  }
  @Test void yesterdayCommandUsesSameConfiguredPipelineWithoutChangingSavedRecords() throws Exception {
    var file=dir.resolve("summary.yaml");Files.writeString(file,SummaryThemeConsolidationTest.CONFIG);
    var records=fixture().stream().flatMap(s->s.evidence().stream()).toList();
    assertThat(records.stream().flatMap(r->r.inference().activities().stream()).map(ActivityRecord.Activity::projectCandidate)).contains("sensevoice");
    var store=mock(ActivityStore.class);when(store.findRecordsBetween(any(),any())).thenReturn(records);
    var timeline=new ActivityTimeline(store,Clock.fixed(START.plusSeconds(90000),ZoneOffset.UTC),
        new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC),
        new DailySummaryService(new ProjectAliasStore(file),null));
    var command=new picocli.CommandLine(new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties()));
    var out=new java.io.StringWriter();command.setOut(new java.io.PrintWriter(out));
    assertThat(command.execute("summary","yesterday")).isZero();
    assertThat(out.toString()).contains("2026-09-24","sensevoice-input の文書作業","livevingo の翻訳").doesNotContain("sensevoice の","文字起こし・音声翻訳");
    verify(store,never()).append(any());
  }
  static void assertNoRawAlias(JsonNode node) {
    if(node.isTextual())assertThat(node.asText()).isNotEqualTo("sensevoice").doesNotContain("sensevoice の");
    else node.forEach(ConsolidationPipelineTest::assertNoRawAlias);
  }
}
