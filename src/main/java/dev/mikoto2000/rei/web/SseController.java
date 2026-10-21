package dev.mikoto2000.rei.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** イベント配信をWeb APIの操作として受け付ける。 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.web.enabled",
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SseController {
  /** 連携。 */
  private final SseBridge bridge;

  /** 指定された値や利用先を使って、SseControllerを初期化する。 */
  public SseController(SseBridge bridge) {
    this.bridge = bridge;
  }

  /** Web APIによる操作で用いるイベント一覧を返す。 */
  @GetMapping(value = "/api/v1/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String runId,
      @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
    var emitter = new SseEmitter(0L);
    var connection =
        bridge.connect(
            runId,
            lastEventId,
            new SseBridge.Sink() {
              /** Web APIによる操作に関するイベントを処理する。 */
              public void event(WebApiEventDto event) throws Exception {
                emitter.send(
                    SseEmitter.event()
                        .name(event.type())
                        .id(Long.toString(event.sequence()))
                        .data(event, MediaType.APPLICATION_JSON));
              }

              /** Web APIによる操作に関する生存確認を処理する。 */
              public void heartbeat() throws Exception {
                emitter.send(
                    SseEmitter.event()
                        .name("heartbeat")
                        .data(java.util.Map.of(), MediaType.APPLICATION_JSON));
              }

              /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
              public void complete(Throwable error) {
                if (error == null) emitter.complete();
                else emitter.completeWithError(error);
              }
            });
    emitter.onCompletion(connection::close);
    emitter.onTimeout(
        () -> {
          connection.close();
          emitter.complete();
        });
    emitter.onError(error -> connection.close());
    return emitter;
  }
}
