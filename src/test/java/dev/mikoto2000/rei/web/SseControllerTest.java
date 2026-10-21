package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ReplayBuffer;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** イベント配信受付窓口の結果と状態の契約を検証するテスト。 */
class SseControllerTest {
  /** Web APIによる操作で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void lastEventIdResumesAndReplayGapIsRejectedBeforeStreaming() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = InMemoryAgentEventBus.withReplayBuffer(new ReplayBuffer(2, Clock.systemUTC()));
    var events = new AgentEventFactory(Clock.systemUTC());
    bus.publish(events.runStarted("run", "one", null));
    bus.publish(events.runStarted("run", "two", null));
    bus.publish(events.runCompleted("run", 1));
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var mvc =
          MockMvcBuilders.standaloneSetup(new SseController(bridge))
              .setControllerAdvice(new ApiExceptionHandler())
              .build();
      mvc.perform(get("/api/v1/runs/run/events").header("Last-Event-ID", "0"))
          .andExpect(status().isConflict())
          .andExpect(request().asyncNotStarted());
      mvc.perform(get("/api/v1/runs/run/events").header("Last-Event-ID", "invalid"))
          .andExpect(status().isBadRequest())
          .andExpect(request().asyncNotStarted());
      var result =
          mvc.perform(get("/api/v1/runs/run/events").header("Last-Event-ID", "2"))
              .andExpect(request().asyncStarted())
              .andReturn();
      result.getAsyncResult(5000);
      mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
      assertThat(result.getResponse().getContentAsString()).contains("id:3").doesNotContain("id:2");
    }
  }

  /** Web APIによる操作で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void streamsSseFramesAndUnknownRunIs404BeforeStartingStream() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var mvc =
          MockMvcBuilders.standaloneSetup(new SseController(bridge))
              .setControllerAdvice(new ApiExceptionHandler())
              .build();
      mvc.perform(get("/api/v1/runs/unknown/events")).andExpect(status().isNotFound());
      var result =
          mvc.perform(get("/api/v1/runs/run/events"))
              .andExpect(request().asyncStarted())
              .andReturn();
      bus.publish(new AgentEventFactory(Clock.systemUTC()).runCompleted("run", 1));
      result.getAsyncResult(5000);
      mvc.perform(asyncDispatch(result))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
      assertThat(result.getResponse().getContentAsString())
          .contains("event:agent.run.completed", "id:1", "data:");
    }
  }
}
