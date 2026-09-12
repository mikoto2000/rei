package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.event.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Independent last-run snapshot, never reconstructed by replaying the event audit log. */
@Component
public class ProjectRunStateStore implements AgentEventListener {
  public record State(String runId, String status, Instant updatedAt) {}
  private final Path base;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private AgentEventBus.Subscription subscription;
  private final java.util.concurrent.ConcurrentMap<String,Object> resultLocks=new java.util.concurrent.ConcurrentHashMap<>();
  public void saveCompleted(dev.mikoto2000.rei.core.execution.CompletedExecution result) {
    synchronized(resultLocks.computeIfAbsent(result.projectId()+":"+result.type(),ignored->new Object())) {
      var previous=latestCompleted(result.projectId(),result.type());
      if(previous.isPresent() && previous.get().completedAt().isAfter(result.completedAt())) return;
      Path file=resultFile(result.projectId(),result.type());
      Path temporary=null;
      try {
        Files.createDirectories(file.getParent());
        temporary=Files.createTempFile(file.getParent(),"completed-",".tmp");
        Files.writeString(temporary,mapper.writeValueAsString(result));
        try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
      } catch(java.io.IOException e) { throw new IllegalStateException("Cannot save completed execution",e); }
      finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(java.io.IOException ignored) {} }
    }
  }
  public Optional<dev.mikoto2000.rei.core.execution.CompletedExecution> latestCompleted(String projectId,dev.mikoto2000.rei.core.execution.ExecutionType type) {
    Path file=resultFile(projectId,type);
    if(!Files.exists(file)) return Optional.empty();
    try { return Optional.of(mapper.readValue(Files.readString(file),dev.mikoto2000.rei.core.execution.CompletedExecution.class)); }
    catch(java.io.IOException e) { throw new IllegalStateException("Cannot read completed execution",e); }
  }
  private Path resultFile(String projectId,dev.mikoto2000.rei.core.execution.ExecutionType type) {
    java.util.UUID.fromString(projectId);
    return base.resolve("projects").resolve(projectId).resolve("state/latest-"+type.name().toLowerCase(java.util.Locale.ROOT)+".json");
  }
  @Autowired public ProjectRunStateStore(AgentEventBus bus) {
    this(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current());
    subscription = bus.subscribe(this);
  }
  public ProjectRunStateStore(Path base) { this.base = base; }
  @jakarta.annotation.PreDestroy public void close() { if (subscription != null) subscription.unsubscribe(); }
  public synchronized void onEvent(AgentEvent event) {
    if (event.projectId() == null) return;
    String status = switch (event.type()) {
      case AGENT_RUN_STARTED -> "RUNNING";
      case AGENT_RUN_COMPLETED -> "COMPLETED";
      case AGENT_RUN_FAILED -> event.payload() instanceof AgentRunFailedPayload failed
          && failed.error() != null && "cancelled".equals(failed.error().code()) ? "CANCELLED" : "FAILED";
      default -> null;
    };
    if (status == null) return;
    Path file = file(event.projectId());
    try {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling("last-run.tmp");
      Files.writeString(temporary, mapper.writeValueAsString(new State(event.runId(), status, event.timestamp())));
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (java.io.IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot save run state: {}", file, e); }
  }
  public synchronized Optional<State> read(String projectId) {
    Path file = file(projectId);
    if (!Files.exists(file)) return Optional.empty();
    try { return Optional.of(mapper.readValue(Files.readString(file), State.class)); }
    catch (java.io.IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot restore run state: {}", file, e); return Optional.empty(); }
  }
  private Path file(String id) { java.util.UUID.fromString(id); return base.resolve("projects").resolve(id).resolve("state/last-run.json"); }
}
