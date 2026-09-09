package dev.mikoto2000.rei.core.chat;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.execution.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.summarize.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackgroundCommandsTest {
  @TempDir Path temp;
  ProjectContext a,b;
  ProjectService projects;
  ProjectRunStateStore states;
  List<Runnable> tasks;
  List<AgentEvent> events;
  ConversationInputRouter router;
  WebPageSummarizerService summaries;
  ImageGenerationService images;
  BackgroundCommands commands;
  @BeforeEach void setup() throws Exception {
    var registry=new ProjectRegistry(temp.resolve("projects.json"));
    a=registry.resolve(Files.createDirectory(temp.resolve("A"))); b=registry.resolve(Files.createDirectory(temp.resolve("B")));
    projects=new ProjectService(a.root(),registry); states=new ProjectRunStateStore(temp);
    tasks=new ArrayList<>(); events=new ArrayList<>(); router=new ConversationInputRouter(tasks::add,(c,p,q)->{});
    summaries=mock(WebPageSummarizerService.class); images=mock(ImageGenerationService.class);
    commands=new BackgroundCommands(router,projects,states,summaries,images,new AgentEventFactory(Clock.systemUTC()),events::add,Clock.systemUTC());
  }
  String show() { var out=new ArrayList<String>(); commands.showLastSummary(out::add); return String.join("\n",out); }
  @Test void narratesOnlyWhenSavedSummaryIsDisplayedForCurrentProject() {
    var narrator = mock(dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator.class);
    commands.setNarrator(narrator);
    when(summaries.summarize(any())).thenReturn(new SummaryResult(URI.create("https://a.example"), "summary A", null));
    show();
    assertThat(tasks).isEmpty();
    commands.summarize(URI.create("https://a.example"));
    show(); // No completed summary yet.
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    assertThat(tasks).isEmpty(); // Completion must not enqueue audio.
    verifyNoInteractions(narrator);
    projects.cd(b.root().toString());
    show();
    assertThat(tasks).isEmpty();
    projects.cd(a.root().toString());
    assertThat(show()).contains("summary A");
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(tasks).hasSize(1);
    projects.cd(b.root().toString());
    tasks.removeFirst().run();
    verify(narrator).narrateCompletedRun("summary A");
    verifyNoMoreInteractions(narrator);
  }
  @Test void snapshotsOwnershipPersistsLatestPerProjectAndBareDisplayDoesNotStartWork() {
    when(summaries.summarize(any())).thenAnswer(call->{
      var execution=ExecutionScope.current();
      assertThat(dev.mikoto2000.rei.llm.ConversationIds.currentChat()).isEqualTo(execution.conversationId());
      assertThat(ProjectService.contextForOperation().id()).isEqualTo(execution.projectId());
      return new SummaryResult(call.getArgument(0),execution.projectId().equals(a.id())?"summary A":"summary B",null);
    });
    assertThat(show()).contains("No completed summary", "A");
    assertThat(tasks).isEmpty();
    commands.summarize(URI.create("https://a.example"));
    assertThat(show()).contains("currently running", "https://a.example", "No completed summary");
    verifyNoInteractions(summaries);
    projects.cd(b.root().toString()); tasks.getFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(show()).contains("No completed summary", "B").doesNotContain("summary A");
    commands.summarize(URI.create("https://b.example")); tasks.get(1).run();
    assertThat(show()).contains("summary B").doesNotContain("summary A");
    projects.cd(a.root().toString());
    commands.summarize(URI.create("https://new.example"));
    assertThat(show()).contains("currently running", "summary A", "https://new.example");
    assertThat(tasks).hasSize(3);
    assertThat(new ProjectRunStateStore(temp).latestCompleted(a.id(),ExecutionType.SUMMARIZE).orElseThrow().result()).isEqualTo("summary A");
    assertThat(events.stream().filter(e->e.type()==AgentEventType.EXECUTION_COMPLETED)).allMatch(e->e.projectId()!=null);
  }
  @Test void imagePreservesOwnershipWithoutChangingSummaryOrConversation() {
    when(images.generate(any())).thenAnswer(call->{
      assertThat(ExecutionScope.current().projectId()).isEqualTo(a.id());
      assertThat(AgentRunScope.current()).isNull();
      return ImageGenerationResult.success(a.root().resolve("image.png"),"picture");
    });
    commands.image(new ImageGenerationRequest("picture",Path.of("image.png"),null,new ImageSize(1024,1024)));
    projects.cd(b.root().toString());
    assertThat(router.activeExecutions()).hasSize(1);
    tasks.getFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(states.latestCompleted(a.id(),ExecutionType.IMAGE).orElseThrow().result()).contains("image.png");
    assertThat(states.latestCompleted(b.id(),ExecutionType.IMAGE)).isEmpty();
    assertThat(states.latestCompleted(a.id(),ExecutionType.SUMMARIZE)).isEmpty();
  }
  @Test void failuresAndTimeoutsNotifyAndNeverReplaceSuccessfulResult() {
    when(summaries.summarize(any())).thenReturn(new SummaryResult(URI.create("https://ok.example"),"good",null));
    commands.summarize(URI.create("https://ok.example")); tasks.removeFirst().run();
    for(RuntimeException error:List.of(new IllegalStateException("broken"),new java.util.concurrent.CancellationException(),
        new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException()))) {
      doThrow(error).when(summaries).summarize(any());
      commands.summarize(URI.create("https://fail.example")); tasks.removeFirst().run();
      assertThat(router.activeExecutions()).isEmpty();
      assertThat(show()).contains("good");
    }
    when(images.generate(any())).thenReturn(ImageGenerationResult.failure("timeout"));
    commands.image(new ImageGenerationRequest("picture",null,null,new ImageSize(1024,1024))); tasks.removeFirst().run();
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(events).anyMatch(e->e.type()==AgentEventType.EXECUTION_FAILED).anyMatch(e->e.type()==AgentEventType.EXECUTION_CANCELLED);
    assertThat(new ConversationInputRouter(tasks::add,(c,p,q)->{}).activeExecutions()).isEmpty();
  }
  @Test void shellCommandsReturnImmediatelyAndSnapshotParsedArguments() {
    when(summaries.summarize(any())).thenAnswer(call->new SummaryResult(call.getArgument(0),"summary",null));
    when(images.generate(any())).thenReturn(ImageGenerationResult.success(temp.resolve("image.png"),"picture"));
    var summarize=new dev.mikoto2000.rei.summarize.command.SummarizeCommand(summaries);
    summarize.setBackgroundCommands(commands);
    var summaryCli=new picocli.CommandLine(summarize);
    assertThat(summaryCli.execute("https://one.example")).isZero();
    assertThat(summaryCli.execute("https://two.example")).isZero();
    var generate=new dev.mikoto2000.rei.image.command.GenerateCommand(images,new ImageProperties(),new dev.mikoto2000.rei.core.service.CommandCancellationService());
    generate.setBackgroundCommands(commands);
    var imageCli=new picocli.CommandLine(generate);
    assertThat(imageCli.execute("--raw","first picture")).isZero();
    assertThat(imageCli.execute("second picture")).isZero();
    verifyNoInteractions(summaries,images);
    assertThat(tasks).hasSize(4);
    tasks.forEach(Runnable::run);
    verify(summaries).summarize(URI.create("https://one.example"));
    verify(summaries).summarize(URI.create("https://two.example"));
    verify(images).generate(argThat(r->r.prompt().equals("first picture")&&!r.enhancePrompt()));
    verify(images).generate(argThat(r->r.prompt().equals("second picture")&&r.enhancePrompt()));
    int started=tasks.size();
    var text=new java.io.StringWriter(); summaryCli.setOut(new java.io.PrintWriter(text));
    assertThat(summaryCli.execute()).isZero();
    assertThat(text.toString()).contains("Last completed summary", "summary");
    assertThat(tasks).hasSize(started);
  }
}
