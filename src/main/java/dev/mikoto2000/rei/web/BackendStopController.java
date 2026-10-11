package dev.mikoto2000.rei.web;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.context.ConfigurableApplicationContext;

/** Authenticated, identity-bound graceful stop after the acknowledgment is delivered. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public final class BackendStopController {
  private final BackendInstance instance;
  private final ConfigurableApplicationContext context;
  public BackendStopController(BackendInstance instance,ConfigurableApplicationContext context){this.instance=instance;this.context=context;}
  public record StopRequest(String instanceId,String storageId) implements StrictApiRequest {}
  @PostMapping("/api/v1/instance/stop")
  public DeferredResult<ResponseEntity<Void>> stop(@RequestBody StopRequest request) {
    var current=instance.get();
    if(request==null||!current.instanceId().equals(request.instanceId())||!current.storageId().equals(request.storageId()))
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    var result=new DeferredResult<ResponseEntity<Void>>(10000L);
    result.onCompletion(()->Thread.startVirtualThread(context::close));
    result.setResult(ResponseEntity.accepted().build());return result;
  }
}
