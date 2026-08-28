package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.MessageStartedPayload;
import dev.mikoto2000.rei.event.SkillSelectionCompletedPayload;
import dev.mikoto2000.rei.event.SkillSelectionFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingCompletedPayload;
import dev.mikoto2000.rei.event.SkillRoutingFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingStartedPayload;
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemAddedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemRemovedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchCompletedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchStartedPayload;

/** Append-only Shell presentation adapter for Agent events. */
public final class ShellAgentEventRenderer implements AgentEventListener {
  private final ShellEventOutput output;
  private String assistantMessageId;
  private boolean assistantLineOpen;
  private boolean toolInterruptedMessage;

  public ShellAgentEventRenderer(ShellEventOutput output) {
    this.output = output;
  }

  @Override
  public synchronized void onEvent(AgentEvent event) {
    switch (event.type()) {
      case AGENT_RUN_STARTED -> {
        closeAssistantLine();
        assistantMessageId = null;
        toolInterruptedMessage = false;
        output.println("[agent] running");
      }
      case AGENT_RUN_COMPLETED -> {
        closeAssistantLine();
        AgentRunCompletedPayload payload = (AgentRunCompletedPayload) event.payload();
        String tokens = payload.completionTokens() == null
            ? "tokens unavailable"
            : payload.completionTokens() + " tokens";
        String speed = payload.tokensPerSecond() == null
            ? "speed unavailable"
            : formatDecimal(payload.tokensPerSecond()) + " tok/s";
        output.println("[agent] completed (" + formatSeconds(payload.duration()) + " s, " + tokens + ", "
            + speed + ")");
      }
      case AGENT_RUN_FAILED -> {
        closeAssistantLine();
        AgentRunFailedPayload payload = (AgentRunFailedPayload) event.payload();
        output.println("[agent] failed: " + errorMessage(payload.error()));
      }
      case MESSAGE_STARTED -> messageStarted((MessageStartedPayload) event.payload());
      case MESSAGE_DELTA -> messageDelta((MessageDeltaPayload) event.payload());
      case MESSAGE_COMPLETED -> messageCompleted((MessageCompletedPayload) event.payload());
      case TOOL_STARTED -> {
        beforeTool();
        ToolStartedPayload payload = (ToolStartedPayload) event.payload();
        String arguments = oneLineSummary(payload.argumentsSummary());
        output.println("  → " + payload.toolName() + (arguments.isEmpty() ? "" : " " + arguments));
      }
      case TOOL_COMPLETED -> {
        beforeTool();
        ToolCompletedPayload payload = (ToolCompletedPayload) event.payload();
        output.println("  ✓ " + payload.toolName() + " (" + payload.duration() + " ms)");
      }
      case TOOL_FAILED -> {
        beforeTool();
        ToolFailedPayload payload = (ToolFailedPayload) event.payload();
        output.println("  ✗ " + payload.toolName() + ": " + errorMessage(payload.error()));
      }
      case SKILL_SELECTION_STARTED -> {
        closeAssistantLine();
        output.println("[skill] selecting");
      }
      case SKILL_SELECTION_COMPLETED -> {
        closeAssistantLine();
        SkillSelectionCompletedPayload payload = (SkillSelectionCompletedPayload) event.payload();
        payload.warnings().forEach(output::println);
        java.util.List<String> selected = new java.util.ArrayList<>();
        payload.explicitSkillNames().forEach(name -> selected.add(name + " (explicit)"));
        payload.implicitSkillNames().forEach(name -> selected.add(name + " (implicit)"));
        output.println("[skill] selected: " + (selected.isEmpty() ? "none" : String.join(", ", selected)));
      }
      case SKILL_SELECTION_FAILED -> {
        closeAssistantLine();
        SkillSelectionFailedPayload payload = (SkillSelectionFailedPayload) event.payload();
        output.println("[skill] selection failed: " + errorMessage(payload.error()));
      }
      case SKILL_ROUTING_STARTED -> {
        closeAssistantLine();
        SkillRoutingStartedPayload payload = (SkillRoutingStartedPayload) event.payload();
        String invocation = payload.routingInvocation() > 1 ? " (#" + payload.routingInvocation() + ")" : "";
        output.println("[skill] selecting from " + payload.candidateCount() + " skills..." + invocation);
      }
      case SKILL_ROUTING_COMPLETED -> {
        closeAssistantLine();
        SkillRoutingCompletedPayload payload = (SkillRoutingCompletedPayload) event.payload();
        payload.warnings().forEach(output::println);
        String selection = payload.selectedSkill() == null
            ? "no skill selected" : payload.selectedSkill() + " selected";
        output.println("[skill] " + selection + " from " + payload.candidateCount() + " skills ("
            + formatDuration(payload.durationMs()) + selectorSuffix(payload.selectorDurationMs()) + ")");
      }
      case SKILL_ROUTING_FAILED -> {
        closeAssistantLine();
        SkillRoutingFailedPayload payload = (SkillRoutingFailedPayload) event.payload();
        output.println("[skill] selection failed after " + formatDuration(payload.durationMs()) + ": "
            + oneLineSummary(errorMessage(payload.error())));
      }
      case SKILL_CANDIDATES_EVALUATED -> {
        closeAssistantLine();
        SkillCandidatesEvaluatedPayload payload = (SkillCandidatesEvaluatedPayload) event.payload();
        String actual = payload.selected() ? payload.actualSelectedSkill() : "none";
        String top5 = !payload.selected() ? "n/a" : Boolean.TRUE.equals(payload.top5Hit()) ? "hit" : "miss";
        output.println("[skill-candidate] " + payload.totalSkillCount() + " -> " + payload.candidateCount()
            + " skills (" + payload.durationMs() + "ms), actual=" + actual + ", top5=" + top5);
      }
      case WORKING_SET_ITEM_ADDED -> {
        closeAssistantLine();
        WorkingSetItemAddedPayload payload = (WorkingSetItemAddedPayload) event.payload();
        output.println("[working-set] + " + displayName(payload.identifier(), payload.path())
            + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_ITEM_REMOVED -> {
        closeAssistantLine();
        WorkingSetItemRemovedPayload payload = (WorkingSetItemRemovedPayload) event.payload();
        output.println("[working-set] - " + displayName(null, payload.itemId()) + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_SEARCH_STARTED -> {
        closeAssistantLine();
        WorkingSetSearchStartedPayload payload = (WorkingSetSearchStartedPayload) event.payload();
        output.println("[working-set] → search \"" + boundedSummary(payload.query(), 120).replace('"', '\'') + "\"");
      }
      case WORKING_SET_SEARCH_COMPLETED -> {
        closeAssistantLine();
        WorkingSetSearchCompletedPayload payload = (WorkingSetSearchCompletedPayload) event.payload();
        String alreadyPresent = payload.alreadyPresentCount() == 0
            ? "" : ", " + payload.alreadyPresentCount() + " already present";
        output.println("[working-set] ✓ " + payload.hitCount() + " hits → " + payload.candidateCount()
            + " candidates → " + payload.selectedCount() + " selected" + alreadyPresent
            + " (" + payload.durationMs() + " ms)");
      }
      default -> { }
    }
    output.flush();
  }

  private void messageStarted(MessageStartedPayload payload) {
    if ("assistant".equalsIgnoreCase(payload.role())) assistantMessageId = payload.messageId();
  }

  private void messageDelta(MessageDeltaPayload payload) {
    if (!payload.messageId().equals(assistantMessageId) || payload.delta() == null) return;
    if (toolInterruptedMessage) {
      output.println("");
      toolInterruptedMessage = false;
    }
    output.print(payload.delta());
    assistantLineOpen = true;
  }

  private void messageCompleted(MessageCompletedPayload payload) {
    if (!payload.messageId().equals(assistantMessageId)) return;
    closeAssistantLine();
    assistantMessageId = null;
    toolInterruptedMessage = false;
  }

  private void beforeTool() {
    if (assistantMessageId != null) toolInterruptedMessage = true;
    closeAssistantLine();
  }

  private void closeAssistantLine() {
    if (assistantLineOpen) {
      output.println("");
      assistantLineOpen = false;
    }
  }

  private String errorMessage(dev.mikoto2000.rei.event.ErrorInformation error) {
    if (error == null || error.message() == null || error.message().isBlank()) return "unknown error";
    return error.message();
  }

  private String formatSeconds(long durationMillis) {
    return formatDecimal(durationMillis / 1_000.0d);
  }

  private String formatDuration(long durationMillis) {
    return durationMillis < 1_000L ? durationMillis + "ms"
        : String.format(java.util.Locale.ROOT, "%.2fs", durationMillis / 1_000.0d);
  }

  private String selectorSuffix(Long durationMillis) {
    return durationMillis == null ? "" : ", selector " + formatDuration(durationMillis);
  }

  private String formatDecimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.1f", value);
  }

  private String oneLineSummary(String value) {
    if (value == null || value.isBlank()) return "";
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      sanitized.append(Character.isISOControl(character) ? ' ' : character);
    }
    return sanitized.toString().replaceAll("\\s+", " ").trim();
  }

  private String displayName(String identifier, String path) {
    String value = identifier == null || identifier.isBlank() ? path : identifier;
    if (value == null || value.isBlank()) return "item";
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator < 0 ? normalized : normalized.substring(separator + 1);
  }

  private String reasonSuffix(String reason) {
    String safe = oneLineSummary(reason);
    if (safe.isEmpty()) return "";
    int maxLength = 120;
    if (safe.length() > maxLength) safe = safe.substring(0, maxLength - 1) + "…";
    return " (" + safe + ")";
  }

  private String boundedSummary(String value, int maxLength) {
    String safe = oneLineSummary(value);
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }
}
