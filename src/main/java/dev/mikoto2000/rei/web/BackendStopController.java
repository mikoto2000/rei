package dev.mikoto2000.rei.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * バックエンド停止をWeb APIの操作として受け付ける。
 *
 * <p>Authenticated, identity-bound graceful stop after the acknowledgment is delivered.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class BackendStopController {
  /** 起動単位の識別子。 */
  private final BackendInstance instance;

  /** 文脈。 */
  private final ConfigurableApplicationContext context;

  /** 指定された値や利用先を使って、BackendStopControllerを初期化する。 */
  public BackendStopController(BackendInstance instance, ConfigurableApplicationContext context) {
    this.instance = instance;
    this.context = context;
  }

  /**
   * Web APIによる操作で受け渡す停止要求を表す。
   *
   * @param instanceId 起動単位の識別子識別子
   * @param storageId 保存識別子
   */
  public record StopRequest(String instanceId, String storageId) implements StrictApiRequest {}

  /** 実行中の処理を停止する。 */
  @PostMapping("/api/v1/instance/stop")
  public DeferredResult<ResponseEntity<Void>> stop(@RequestBody StopRequest request) {
    var current = instance.get();
    if (request == null
        || !current.instanceId().equals(request.instanceId())
        || !current.storageId().equals(request.storageId()))
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    var result = new DeferredResult<ResponseEntity<Void>>(10000L);
    result.onCompletion(() -> Thread.startVirtualThread(context::close));
    result.setResult(ResponseEntity.accepted().build());
    return result;
  }
}
