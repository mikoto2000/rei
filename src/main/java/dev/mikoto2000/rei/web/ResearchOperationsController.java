package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.externalagent.BeginnerReviewRequest;
import dev.mikoto2000.rei.externalagent.ExternalAgentCommandRequest;
import dev.mikoto2000.rei.paper.PaperCommandRequest;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Named domain requests, validated by the same grammar as the existing Shell. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ResearchOperationsController {
  private final ChatSubmitService chat;

  public record Arguments(String projectId, String sessionId, List<String> arguments)
      implements StrictApiRequest {}

  public record Agent(
      String projectId, String sessionId, String agent, String action, String target)
      implements StrictApiRequest {}

  public ResearchOperationsController(ChatSubmitService chat) {
    this.chat = chat;
  }

  @PostMapping("/api/v1/paper/operations")
  public ResponseEntity<ChatResponse> paper(
      @RequestBody Arguments request, @RequestHeader("Idempotency-Key") String key) {
    validate(request, key);
    try {
      PaperCommandRequest.parse(request.arguments().toArray(String[]::new));
    } catch (dev.mikoto2000.rei.paper.PaperException invalid) {
      throw new IllegalArgumentException(invalid.getMessage());
    }
    return submit(
        "/paper " + quoted(request.arguments()), request.projectId(), request.sessionId(), key);
  }

  @PostMapping("/api/v1/material-reviews")
  public ResponseEntity<ChatResponse> material(
      @RequestBody Arguments request, @RequestHeader("Idempotency-Key") String key) {
    validate(request, key);
    BeginnerReviewRequest.parse(request.arguments().toArray(String[]::new));
    return submit(
        "/material-review " + quoted(request.arguments()),
        request.projectId(),
        request.sessionId(),
        key);
  }

  @PostMapping("/api/v1/external-agent/operations")
  public ResponseEntity<ChatResponse> agent(
      @RequestBody Agent request, @RequestHeader("Idempotency-Key") String key) {
    if (request == null || request.agent() == null || request.action() == null)
      throw new IllegalArgumentException("External agent and named action required");
    validateKey(key);
    String command =
        "/agent "
            + request.agent()
            + " "
            + request.action()
            + (request.target() == null ? "" : " " + request.target());
    if (command.length() > 16384 || command.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid external agent request");
    var parsed = ExternalAgentCommandRequest.parse(command);
    if (!parsed.agent().equals(request.agent()) || !parsed.action().equals(request.action()))
      throw new IllegalArgumentException("Invalid external agent request");
    return submit(command, request.projectId(), request.sessionId(), key);
  }

  private static void validate(Arguments request, String key) {
    validateKey(key);
    if (request == null
        || request.arguments() == null
        || request.arguments().size() > 2048
        || request.arguments().stream()
            .anyMatch(
                value -> value == null || value.codePoints().anyMatch(Character::isISOControl))
        || request.arguments().stream().mapToLong(String::length).sum() > 16384)
      throw new IllegalArgumentException("Invalid domain arguments");
  }

  private static void validateKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Receipt key required");
  }

  private static String quoted(List<String> arguments) {
    return arguments.stream()
        .map(value -> UserInputParser.quote(value, true, (char) 0))
        .collect(java.util.stream.Collectors.joining(" "));
  }

  private ResponseEntity<ChatResponse> submit(
      String command, String project, String session, String key) {
    var run = chat.submit(command, project, session, AgentRunContext.Mode.EXCLUSIVE, key);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }
}
