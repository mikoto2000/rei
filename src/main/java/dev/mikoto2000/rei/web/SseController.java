package dev.mikoto2000.rei.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SseController {
  private final SseBridge bridge;
  public SseController(SseBridge bridge) { this.bridge = bridge; }
  @GetMapping(value = "/api/v1/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String runId,
      @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    var emitter = new SseEmitter(0L);
    var connection = bridge.connect(runId, lastEventId, new SseBridge.Sink() {
      public void event(WebApiEventDto event) throws Exception {
        emitter.send(SseEmitter.event().name(event.type()).id(Long.toString(event.sequence())).data(event, MediaType.APPLICATION_JSON));
      }
      public void heartbeat() throws Exception {
        emitter.send(SseEmitter.event().name("heartbeat").data(java.util.Map.of(), MediaType.APPLICATION_JSON));
      }
      public void complete(Throwable error) {
        if (error == null) emitter.complete(); else emitter.completeWithError(error);
      }
    });
    emitter.onCompletion(connection::close);
    emitter.onTimeout(() -> { connection.close(); emitter.complete(); });
    emitter.onError(error -> connection.close());
    return emitter;
  }
}
