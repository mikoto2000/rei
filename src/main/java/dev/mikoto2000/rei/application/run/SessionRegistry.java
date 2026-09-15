package dev.mikoto2000.rei.application.run;

import java.time.*;
import java.util.*;

/** Sessions expire 30 minutes after registration or a valid continuation, independently of runs. */
public final class SessionRegistry {
  private record Session(String projectId, Instant accessedAt) {}
  private final Map<String, Session> sessions = new HashMap<>();
  private final Clock clock;
  public SessionRegistry(Clock clock) { this.clock = clock; }
  public synchronized void register(String sessionId, String projectId) {
    purgeExpired();
    if (sessions.putIfAbsent(sessionId, new Session(projectId, clock.instant())) != null)
      throw new IllegalArgumentException("Duplicate session");
  }
  public synchronized String requireProject(String sessionId, String projectId) {
    purgeExpired();
    var session = sessions.get(sessionId);
    if (session == null) throw new ResourceNotFoundException("Session");
    if (!session.projectId().equals(projectId)) throw new SessionConflictException();
    sessions.put(sessionId, new Session(projectId, clock.instant()));
    return projectId;
  }
  public synchronized void purgeExpired() {
    sessions.values().removeIf(session -> !clock.instant().isBefore(session.accessedAt().plus(Duration.ofMinutes(30))));
  }
}
