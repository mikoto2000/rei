package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.modeloptions.AcceptedRunOptions;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Inference export authority is independent of callback execution authority. */
@org.springframework.stereotype.Component
public final class InferenceExportGuard {
  private static final Set<String> REQUIRED =
      Set.of("endpoint", "provider", "api", "authentication", "model", "base_url");
  private static final Set<String> DESCRIPTION =
      Set.of(
          "endpoint",
          "provider",
          "api",
          "authentication",
          "model",
          "base_url",
          "account",
          "registration");
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final ToolApprovalRepository approvals;
  private final Clock clock;

  public InferenceExportGuard(ToolApprovalRepository approvals, Clock clock) {
    this.approvals = java.util.Objects.requireNonNull(approvals);
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  public record Decision(Prompt prompt, String approvalId) {
    public boolean permitted() {
      return approvalId == null;
    }

    @Override
    public String toString() {
      return "InferenceExportDecision[permitted="
          + permitted()
          + ", approvalId="
          + approvalId
          + "]";
    }
  }

  /** Verify a saved input's exact approval binding without granting or consuming authority. */
  public void verifySavedApproval(
      String id,
      AgentRunContext owner,
      AcceptedRunOptions connection,
      ApprovalContinuationStore.InferenceInput input) {
    // API introduced for the next content-bound continuation test; not wired into dispatch.
  }

  public Decision authorize(AgentRunContext owner, AcceptedRunOptions connection, Prompt prompt) {
    java.util.Objects.requireNonNull(connection, "Accepted inference connection required");
    if (!connection.named()) return new Decision(prompt, null);
    if (owner == null
        || owner.projectId() == null
        || owner.projectId().isBlank()
        || owner.conversationId().isBlank()
        || owner.runId().isBlank())
      throw new IllegalArgumentException("Inference export requires an owning project/session/Run");
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
        || options.getToolContext() == null
        || !(options
                .getToolContext()
                .get(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY)
            instanceof AcceptedRunOptions accepted)
        || !connection.fingerprint().equals(accepted.fingerprint())
        || !connection.fingerprint().matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Inference export accepted connection mismatch");
    var prepared = InferenceExportRequest.prepare(prompt);
    String canonical = canonical(owner, connection, prepared);
    if (approvals.consume("inferenceExport", canonical, owner))
      return new Decision(prepared.prompt(), null);
    var request = approvals.request("inferenceExport", canonical, owner);
    if (!clock.instant().isBefore(request.expiresAt()))
      throw new SecurityException("Inference export approval expired");
    if (request.status().equals("APPROVED")) {
      if (approvals.consume("inferenceExport", canonical, owner))
        return new Decision(prepared.prompt(), null);
      throw new SecurityException("Inference export approval was already consumed");
    }
    if (!request.status().equals("PENDING"))
      throw new SecurityException("Inference export approval is unavailable");
    return new Decision(prepared.prompt(), request.id());
  }

  private static String canonical(
      AgentRunContext owner, AcceptedRunOptions connection, InferenceExportRequest prepared) {
    var destination = new TreeMap<>(connection.inferenceDescription());
    if (!destination.keySet().containsAll(REQUIRED)
        || !DESCRIPTION.containsAll(destination.keySet())
        || destination.values().stream()
            .anyMatch(value ->
                value == null
                    || value.isBlank()
                    || value.length() > 4096
                    || value.codePoints().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Inference export destination is incomplete or invalid");
    var identity =
        Map.of(
            "project", owner.projectId(),
            "root", owner.projectRoot().toAbsolutePath().normalize().toString(),
            "session", owner.conversationId(),
            "run", owner.runId(),
            "mode", owner.modeName(),
            "source", owner.sourceName(),
            "voice", owner.voiceInput());
    try {
      return JSON.writeValueAsString(
          Map.of(
              "purpose",
              "inferenceExport",
              "version",
              1,
              "owner",
              identity,
              "connection",
              connection.fingerprint(),
              "destination",
              destination,
              "content",
              prepared.fingerprint()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Inference export binding could not be encoded");
    }
  }
}
