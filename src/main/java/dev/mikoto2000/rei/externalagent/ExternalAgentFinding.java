package dev.mikoto2000.rei.externalagent;

public record ExternalAgentFinding(Severity severity, String title, String reason, String recommendation, String location) {
  public enum Severity { critical, high, medium, low, info }
}
