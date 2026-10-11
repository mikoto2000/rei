package dev.mikoto2000.rei.application.run;

public final class SessionBusyException extends RuntimeException {
  private final String projectId,sessionId,runId;
  public SessionBusyException(String projectId,String sessionId,String runId) {
    super("Session already has an admitted conversation Run");
    this.projectId=projectId;this.sessionId=sessionId;this.runId=runId;
  }
  public String projectId(){return projectId;}
  public String sessionId(){return sessionId;}
  public String runId(){return runId;}
}
