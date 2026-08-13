package dev.mikoto2000.rei.agent.progress;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

@Component
public class AgentProgressSessionRegistry {

  public static final String TOOL_CONTEXT_SESSION_ID = "rei.agent.progress.session-id";

  private final ConcurrentMap<String, AgentProgressSession> sessions = new ConcurrentHashMap<>();

  public String start(String goal, int maxNoProgressIterations) {
    String id = UUID.randomUUID().toString();
    sessions.put(id, new AgentProgressSession(goal, maxNoProgressIterations));
    return id;
  }

  public Optional<AgentProgressSession> find(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessions.get(id));
  }

  public Optional<AgentProgressSession> finish(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(sessions.remove(id));
  }
}
