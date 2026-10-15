package dev.mikoto2000.rei.application.run;

public final class SessionBusyException extends RuntimeException {
  private final String projectId, sessionId, runId;
  private final String status;

  public SessionBusyException(String projectId, String sessionId, String runId) {
    this(projectId, sessionId, runId, null);
  }

  public SessionBusyException(String projectId, String sessionId, String runId, String status) {
    super("Session already has an admitted conversation Run");
    this.projectId = projectId;
    this.sessionId = sessionId;
    this.runId = runId;
    this.status = status;
  }

  public String projectId() {
    return projectId;
  }

  public String sessionId() {
    return sessionId;
  }

  public String runId() {
    return runId;
  }

  public String status() {
    return status;
  }
}
