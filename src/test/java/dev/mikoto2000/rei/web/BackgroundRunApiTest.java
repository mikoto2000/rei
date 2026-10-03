package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.summarize.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BackgroundRunApiTest {
  @TempDir Path directory;
  @Test void summariesAndImagesUseCommonRunPollingSseReplayAndQueuedCancel() throws Exception {
    var pending = new ArrayDeque<Runnable>();
    var router = new ConversationInputRouter(pending::add, (c,p,q)-> { throw new AssertionError("Chat runner must not execute operations"); });
    var registry = new RunRegistry(Clock.systemUTC()); var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC()); var cancellation = new CommandCancellationService();
    var projects = new ProjectRegistry(directory.resolve("projects.json")); var project = projects.resolve(directory);
    var summaries = mock(WebPageSummarizerService.class); var images = mock(ImageGenerationService.class);
    when(summaries.summarize(URI.create("https://example.com"))).thenReturn(new SummaryResult(URI.create("https://example.com"),"summary",null));
    when(images.generate(any())).thenAnswer(a -> ImageGenerationResult.success(((ImageGenerationRequest)a.getArgument(0)).outputPath(),"image"));
    try(var runs = new RunService(registry,bus,events,cancellation,router::cancelQueued); var bridge = new SseBridge(bus,runs,"")) {
      var submit = new BackgroundRunSubmitService(projects,registry,runs,router,cancellation,events,bus,summaries,images,new ImageProperties());
      var mvc = MockMvcBuilders.standaloneSetup(new BackgroundRunController(submit),new RunController(runs),new SseController(bridge)).setControllerAdvice(new ApiExceptionHandler()).build();
      var response = mvc.perform(post("/api/v1/summaries").contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"url\":\"https://example.com\"}"))
          .andExpect(status().isAccepted()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.length()").value(1)).andReturn().getResponse();
      String location = response.getHeader("Location"); assertThat(location).startsWith("/api/v1/runs/");
      String id = location.substring(location.lastIndexOf('/')+1);
      mvc.perform(get(location)).andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.sessionId").value(org.hamcrest.Matchers.nullValue()));
      var image = submit.image(project.id(),"test",null);
      assertThat(runs.get(image.runId()).status()).isEqualTo(RunStatus.QUEUED);
      assertThat(runs.cancel(image.runId()).accepted()).isTrue();
      pending.remove().run();
      mvc.perform(get(location)).andExpect(jsonPath("$.status").value("COMPLETED"));
      verifyNoInteractions(images);
      var first=mvc.perform(get(location+"/events")).andExpect(request().asyncStarted()).andReturn();
      first.getAsyncResult(5000); mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream"));
      assertThat(first.getResponse().getContentAsString()).contains("agent.run.started","message.delta","summary","agent.run.completed");
      var replay=mvc.perform(get(location+"/events").header("Last-Event-ID","1")).andReturn();
      replay.getAsyncResult(5000); mvc.perform(asyncDispatch(replay)).andExpect(status().isOk());
      assertThat(replay.getResponse().getContentAsString()).contains("summary","agent.run.completed").doesNotContain("id:1\n");
      var imageResponse=mvc.perform(post("/api/v1/images").contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"prompt\":\"draw\"}"))
          .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn().getResponse();
      pending.remove().run();
      var imageRun = imageResponse.getHeader("Location"); mvc.perform(get(imageRun)).andExpect(jsonPath("$.status").value("COMPLETED"));
      var request=org.mockito.ArgumentCaptor.forClass(ImageGenerationRequest.class); verify(images).generate(request.capture());
      assertThat(request.getValue().outputPath().startsWith(directory.resolve(".rei/web-images"))).isTrue();
      assertThatThrownBy(()->submit.summary("unknown","https://example.com")).isInstanceOf(ResourceNotFoundException.class);
      assertThatThrownBy(()->submit.summary(project.id(),"file:///secret")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(()->submit.image(project.id()," ",null)).isInstanceOf(IllegalArgumentException.class);
      assertThat(runs.get(id).context().projectId()).isEqualTo(project.id());
    }
  }
  @Test void runningCancelStopsWorkAndProjectsShareChatFifoButDifferentProjectsProceed() throws Exception {
    var started=new CountDownLatch(1); var interrupted=new CountDownLatch(1); var chat=new CountDownLatch(1);
    var projects=new ProjectRegistry(directory.resolve("projects.json")); var one=projects.resolve(directory); var two=projects.resolve(java.nio.file.Files.createDirectory(directory.resolve("other")));
    var bus=new InMemoryAgentEventBus(); var events=new AgentEventFactory(Clock.systemUTC()); var registry=new RunRegistry(Clock.systemUTC()); var cancellation=new CommandCancellationService();
    var summaries=mock(WebPageSummarizerService.class); var images=mock(ImageGenerationService.class);
    when(summaries.summarize(any())).thenAnswer(a->{ started.countDown(); try { new CountDownLatch(1).await(); } catch(InterruptedException e) { interrupted.countDown(); throw new CancellationException(); } return null; });
    when(images.generate(any())).thenReturn(ImageGenerationResult.success(directory.resolve("image.png")));
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var router=new ConversationInputRouter(executor,(c,p,q)-> {chat.countDown(); bus.publish(events.runCompleted(c.runId(),1).withOwnership(c));});
      try(var runs=new RunService(registry,bus,events,cancellation,router::cancelQueued)) {
        var submit=new BackgroundRunSubmitService(projects,registry,runs,router,cancellation,events,bus,summaries,images,new ImageProperties());
        var slow=submit.summary(one.id(),"https://example.com"); assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
        var queued=submit.image(one.id(),"queued",null);
        var context=new AgentRunContext("chat","session",directory,one.id()); registry.register(context); router.submit(context,"chat",work->runs.execute(context,work));
        var other=submit.image(two.id(),"other",null);
        for(int i=0;i<100 && !runs.get(other.runId()).status().isTerminal();i++) Thread.sleep(10);
        assertThat(runs.get(other.runId()).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(chat.getCount()).isEqualTo(1); assertThat(runs.get(queued.runId()).status()).isEqualTo(RunStatus.QUEUED);
        runs.cancel(slow.runId()); assertThat(interrupted.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(chat.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(runs.get(slow.runId()).status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runs.get(queued.runId()).status()).isEqualTo(RunStatus.COMPLETED);
      }
    }
  }
}
