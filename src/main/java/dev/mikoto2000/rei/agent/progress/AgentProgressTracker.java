package dev.mikoto2000.rei.agent.progress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AgentProgressTracker {

  private static final Pattern VOLATILE_VALUE = Pattern.compile(
      "\\b(?:\\d{4}-\\d{2}-\\d{2}[tT ][0-9:.+\\-Z]+|[0-9a-f]{8}-[0-9a-f-]{27,})\\b");
  private static final Pattern JSON_KEY_VALUE = Pattern.compile("\\s*:\\s*");
  private static final Pattern JSON_SEPARATOR = Pattern.compile("\\s*,\\s*");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final int maxNoProgressIterations;
  private AgentProgressState previousState;
  private int noProgressCount;
  private ProgressEvaluation lastEvaluation = new ProgressEvaluation(ProgressLevel.MEANINGFUL, List.of("initial state"));

  public AgentProgressTracker(String goal, int maxNoProgressIterations) {
    this.maxNoProgressIterations = Math.max(1, maxNoProgressIterations);
    this.previousState = AgentProgressState.empty(goal);
  }

  public ProgressTrackerSnapshot update(AgentProgressState currentState) {
    ProgressEvaluation evaluation = evaluate(previousState, currentState);
    noProgressCount = evaluation.progressed() ? 0 : noProgressCount + 1;
    previousState = currentState;
    lastEvaluation = evaluation;
    return snapshot();
  }

  public ProgressTrackerSnapshot snapshot() {
    return new ProgressTrackerSnapshot(
        previousState,
        lastEvaluation,
        noProgressCount,
        maxNoProgressIterations,
        noProgressCount >= maxNoProgressIterations);
  }

  public ProgressEvaluation evaluate(AgentProgressState previous, AgentProgressState current) {
    List<String> reasons = new ArrayList<>();
    Set<String> previousCompleted = normalizedSet(previous.completedSteps());
    Set<String> currentCompleted = normalizedSet(current.completedSteps());
    Set<String> previousIssues = normalizedSet(previous.unresolvedIssues());
    Set<String> currentIssues = normalizedSet(current.unresolvedIssues());
    Set<String> previousObservations = observationFingerprints(previous);
    Set<String> currentObservations = observationFingerprints(current);

    int completedAdded = differenceSize(currentCompleted, previousCompleted);
    int issuesResolved = differenceSize(previousIssues, currentIssues);
    int newObservations = differenceSize(currentObservations, previousObservations);
    int newIssues = differenceSize(currentIssues, previousIssues);

    if (completedAdded > 0) {
      reasons.add("completed steps increased by " + completedAdded);
    }
    if (issuesResolved > 0) {
      reasons.add("unresolved issues decreased by " + issuesResolved);
    }
    if (newObservations > 0) {
      reasons.add("new useful observations added by " + newObservations);
    }
    if (newIssues > 0) {
      reasons.add("new unresolved issues added by " + newIssues);
    }

    boolean progressed = completedAdded > 0 || issuesResolved > 0 || newObservations > 0;
    if (progressed) {
      return new ProgressEvaluation(ProgressLevel.MEANINGFUL, reasons);
    }
    if (newIssues > 0) {
      return new ProgressEvaluation(ProgressLevel.REGRESSED, reasons);
    }

    reasons.add("completed steps unchanged");
    reasons.add("unresolved issues unchanged");
    reasons.add("observations duplicate previous state");
    return new ProgressEvaluation(ProgressLevel.NONE, reasons);
  }

  private Set<String> observationFingerprints(AgentProgressState state) {
    Set<String> fingerprints = new LinkedHashSet<>();
    for (String observation : state.observations()) {
      addIfNotBlank(fingerprints, normalize(observation));
    }
    for (ToolExecutionObservation tool : state.toolHistory()) {
      addIfNotBlank(fingerprints, toolFingerprint(tool));
    }
    return fingerprints;
  }

  private String toolFingerprint(ToolExecutionObservation tool) {
    String result = normalize(tool.result());
    String errorMarker = tool.error() ? "error" : "success";
    return errorMarker + ":" + result;
  }

  private Set<String> normalizedSet(List<String> values) {
    return values.stream()
        .map(this::normalize)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private int differenceSize(Set<String> left, Set<String> right) {
    int count = 0;
    for (String value : left) {
      if (!right.contains(value)) {
        count++;
      }
    }
    return count;
  }

  private void addIfNotBlank(Set<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  String normalize(String value) {
    if (value == null) {
      return "";
    }
    String normalized = VOLATILE_VALUE.matcher(value).replaceAll("<volatile>");
    normalized = JSON_KEY_VALUE.matcher(normalized).replaceAll(":");
    normalized = JSON_SEPARATOR.matcher(normalized).replaceAll(",");
    normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
    return normalized.trim().toLowerCase(Locale.ROOT);
  }
}
