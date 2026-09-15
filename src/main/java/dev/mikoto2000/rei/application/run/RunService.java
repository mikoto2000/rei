package dev.mikoto2000.rei.application.run;

public class RunService {
  private final RunRegistry registry;
  public RunService(RunRegistry registry) { this.registry = registry; }
  public RunSnapshot get(String runId) { return registry.get(runId); }
}
