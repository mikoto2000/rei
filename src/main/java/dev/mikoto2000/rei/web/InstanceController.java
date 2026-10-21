package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.launcher.BackendEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 起動単位の識別子をWeb APIの操作として受け付ける。
 *
 * <p>Protected by the existing stateless Bearer filter; health cannot identify an instance.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InstanceController {
  /** 起動単位の識別子。 */
  private final BackendInstance instance;

  /** 指定された値や利用先を使って、InstanceControllerを初期化する。 */
  public InstanceController(BackendInstance instance) {
    this.instance = instance;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  @GetMapping("/api/v1/instance")
  public ResponseEntity<BackendEndpoint> get() {
    try {
      return ResponseEntity.ok(instance.get());
    } catch (IllegalStateException starting) {
      return ResponseEntity.status(503).build();
    }
  }
}
