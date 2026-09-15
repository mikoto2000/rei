package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class SseControllerTest {
  @Test void streamsSseFramesAndUnknownRunIs404BeforeStartingStream() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var mvc = MockMvcBuilders.standaloneSetup(new SseController(bridge)).setControllerAdvice(new ApiExceptionHandler()).build();
      mvc.perform(get("/api/v1/runs/unknown/events")).andExpect(status().isNotFound());
      var result = mvc.perform(get("/api/v1/runs/run/events")).andExpect(request().asyncStarted()).andReturn();
      bus.publish(new AgentEventFactory(Clock.systemUTC()).runCompleted("run", 1));
      result.getAsyncResult(5000);
      mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream"));
      assertThat(result.getResponse().getContentAsString()).contains("event:agent.run.completed", "id:1", "data:");
    }
  }
}
