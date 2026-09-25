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
class SummaryRuntimeTraceTest {
  @TempDir Path dir;
  record Harness(picocli.CommandLine command,java.io.StringWriter output,ActivityStore store) {}
  Harness harness(DailySummaryService service,String date) {
    var store=mock(ActivityStore.class);
    when(store.findRecordsBetween(any(),any())).thenReturn(ConsolidationPipelineTest.fixture().stream().flatMap(s->s.evidence().stream()).toList());
    var clock=Clock.fixed(DailySummaryTest.START.plusSeconds(date.equals("yesterday")?90000:86399),ZoneOffset.UTC);
    var timeline=new ActivityTimeline(store,clock,new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC),service);
    var root=new picocli.CommandLine(picocli.CommandLine.Model.CommandSpec.create().name("rei")).addSubcommand("activity",new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties()));
    var output=new java.io.StringWriter();root.setOut(new java.io.PrintWriter(output));return new Harness(root,output,store);
  }
  void execute(Harness h,String date) {
    var input=new dev.mikoto2000.rei.core.command.UserInputService(new dev.mikoto2000.rei.core.command.UserInputParser())
        .interpret("/activity summary"+(date.isBlank()?"":" "+date));
    assertThat(input.kind()).isEqualTo(dev.mikoto2000.rei.core.command.UserInputService.Kind.COMMAND);
    assertThat(h.command().execute(input.arguments())).isZero();
  }
  @ParameterizedTest @ValueSource(strings={"","today","yesterday","2026-09-24"})
  void slashDateFormsUseDailyServiceAndMissingConfigExplainsUnchangedOutput(String date) throws Exception {
    var file=dir.resolve("summary.yaml");
    var writes=new java.util.concurrent.atomic.AtomicInteger();
    var service=spy(new DailySummaryService(new ProjectAliasStore(file),a->{writes.incrementAndGet();return DailySummary.fallback(a);}));
    var h=harness(service,date);execute(h,date);
    assertThat(h.output().toString()).contains("sensevoice の文書作業","文字起こし・音声翻訳","livevingo の翻訳");
    Files.copy(Path.of("docs/testdata/summary-theme-groups-2026-09-24.yaml"),file);
    h.output().getBuffer().setLength(0);execute(h,date);
    assertThat(h.output().toString()).contains("音声入力・文字起こし系の文書作業","音声翻訳系の開発")
        .doesNotContain("sensevoice の文書作業","文字起こし・音声翻訳","livevingo の翻訳");
    assertThat(writes.get()).isEqualTo(2);
    verify(service,times(2)).summarize(eq(DailySummaryTest.DATE),any(),eq(ZoneOffset.UTC),eq(.5),any());
    verify(h.store(),never()).append(any());
  }
  @ParameterizedTest @ValueSource(strings={"success","invalid","failure"})
  void commandThroughRealWriterTracesInputModeAndFormatter(String mode) throws Exception {
    var file=dir.resolve("summary.yaml");Files.copy(Path.of("docs/testdata/summary-theme-groups-2026-09-24.yaml"),file);
    var aliases=new ProjectAliasStore(file);var config=aliases.snapshot();
    var aggregate=new DailySummaryAggregator(config.projects(),.5,config.groups()).aggregate(DailySummaryTest.DATE,DailySummaryTest.RANGE,ZoneOffset.UTC,ConsolidationPipelineTest.fixture());
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenAnswer(call->{
      var input=ConsolidationPipelineTest.JSON.readTree(((Prompt)call.getArgument(0)).getUserMessage().getText());
      ConsolidationPipelineTest.assertNoRawAlias(input);
      assertThat(input.path("mainWorkThemeCandidates").findValuesAsText("label")).doesNotContain("livevingo の翻訳");
      assertThat(input.has("topProjects")).isFalse();
      if(mode.equals("failure"))return Flux.error(new IllegalStateException("synthetic failure"));
      var output=ConsolidationPipelineTest.JSON.valueToTree(DailySummary.fallback(aggregate));
      var times=(com.fasterxml.jackson.databind.node.ObjectNode)output.path("timeOfDay");
      DailySummaryAggregate.BUCKET_ORDER.forEach(key->{if(!times.has(key))times.putNull(key);});
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(mode.equals("invalid")?"{}":output.toString())))));
    });
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("dev.mikoto2000.rei.activity");
    var previous=logger.getLevel();var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
    try {
      logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      var h=harness(new DailySummaryService(aliases,new LlmDailySummaryWriter(()->model,()->OpenAiChatOptions.builder().build(),Duration.ofSeconds(2))),"yesterday");
      execute(h,"yesterday");verify(model).stream(any(Prompt.class));
      assertThat(h.output().toString()).doesNotContain("sensevoice の文書作業","livevingo の翻訳","文字起こし・音声翻訳");
      var trace=events.list.stream().map(e->e.getFormattedMessage()).toList();
      for(var stage:List.of("command","source-segments","raw-themes","canonical-themes","grouped-themes","suppressed-themes","final-themes","llm-input","formatter-input"))
        assertThat(trace).anyMatch(s->s.startsWith("[summary-trace] "+stage));
      assertThat(trace).contains("[summary-trace] writer-mode="+(mode.equals("success")?"LLM_SUCCESS":"FALLBACK"));
      if(mode.equals("success"))assertThat(trace).anyMatch(s->s.startsWith("[summary-trace] llm-output validated="));
      else assertThat(trace).anyMatch(s->s.startsWith("[summary-trace] writer-failure"));
      assertThat(events.list.stream().filter(e->e.getFormattedMessage().startsWith("[summary-trace]"))).allMatch(e->e.getLevel()==ch.qos.logback.classic.Level.DEBUG);
    } finally {logger.detachAppender(events);logger.setLevel(previous);events.stop();}
  }
}
