package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.launcher.BackendEndpoint;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** Protected by the existing stateless Bearer filter; health cannot identify an instance. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class InstanceController {
  private final BackendInstance instance;
  public InstanceController(BackendInstance instance) { this.instance = instance; }
  @GetMapping("/api/v1/instance")
  public ResponseEntity<BackendEndpoint> get() {
    try { return ResponseEntity.ok(instance.get()); }
    catch (IllegalStateException starting) { return ResponseEntity.status(503).build(); }
  }
}
