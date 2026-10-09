package dev.mikoto2000.rei.llm.capture;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.event.*;
/** Metadata-only terminal subscription also covers cancellation before execution. */
@Component
public final class CaptureLifecycle implements AutoCloseable {
  private final AgentEventBus.Subscription subscription;
  public CaptureLifecycle(CaptureStore store,AgentEventBus bus){subscription=bus.subscribe(event->{
    if(event.runId()==null)return;
    switch(event.type()){
      case AGENT_RUN_COMPLETED -> store.finish(event.runId(),"COMPLETED");
      case AGENT_RUN_FAILED -> store.finish(event.runId(), event.payload() instanceof AgentRunFailedPayload failed && failed.error()!=null && "cancelled".equals(failed.error().code()) ? "CANCELLED" : "FAILED");
      case AGENT_RUN_CANCELLED -> store.finish(event.runId(),"CANCELLED");
      default -> {}
    }
  });}
  public void close(){subscription.unsubscribe();}
}
