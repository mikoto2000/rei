package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Real durable approval lifecycle around a strictly confined synthetic write. */
final class ResponsesApprovalLiveScenario {
  static void run(ResponsesAcceptanceRuntime runtime, String action) throws Exception {
    boolean writer = action.equals("approval-write") || action.equals("approval-deny-write");
    boolean denied = action.equals("approval-deny") || action.equals("approval-deny-write");
    if (!writer && !action.equals("approval-read") && !action.equals("approval-deny"))
      throw new IllegalArgumentException("Explicit approval action required");
    String prefix = denied ? "deny-approval-" : "approval-";
    Path output = runtime.root.resolve(prefix + "result.txt");
    Path readCount = runtime.root.resolve(prefix + "read.txt");
    Path noncePath = runtime.root.resolve(prefix + "nonce.txt");
    Path idPath = runtime.root.resolve(prefix + "id.txt");
    if (!Files.exists(noncePath))
      Files.writeString(
          noncePath, "approved-" + java.util.UUID.randomUUID(), StandardOpenOption.CREATE_NEW);
    String nonce = Files.readString(noncePath);
    var clock = Clock.systemUTC();
    var projectRegistry =
        new dev.mikoto2000.rei.core.project.ProjectRegistry(runtime.root.resolve("projects.json"));
    var project = projectRegistry.resolve(runtime.root);
    var owner = new AgentRunContext(prefix + "run", prefix + "session", runtime.root, project.id());
    var source = runtime.source("payload.db");
    var approvals = new dev.mikoto2000.rei.core.policy.ToolApprovalRepository(source, clock);
    var saved = new dev.mikoto2000.rei.core.policy.ApprovalContinuationStore(source);
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(clock, source);
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var cancellation = new dev.mikoto2000.rei.core.service.CommandCancellationService();
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true,
                    null,
                    null,
                    Map.of("writeMultiFile", java.util.Set.of(ActionCapability.LOCAL_WRITE)))),
            new dev.mikoto2000.rei.event.AgentEventFactory(clock),
            bus);
    guard.setApprovals(approvals);
    ToolCallback read =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("now")
                .description(
                    "Read the owned approval fixture data once. Returns accepted-value, moduli, and remainders. Not current time.")
                .inputSchema(
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .build();
          }

          public String call(String input) {
            try {
              if (Files.exists(readCount))
                throw new IllegalStateException("Successful approval prefix repeated");
              Files.writeString(readCount, "read\n", StandardOpenOption.CREATE_NEW);
              return "{\"accepted-value\":\""
                  + nonce
                  + "\",\"moduli\":[17,19,23],\"remainders\":[5,7,11]}";
            } catch (java.io.IOException failure) {
              throw new IllegalStateException("Owned read evidence failed");
            }
          }
        };
    ToolCallback write =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("writeMultiFile")
                .description(
                    "Approval-required owned fixture write. Write exactly one file under the owned root, once.")
                .inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"files\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}}},\"required\":[\"files\"],\"additionalProperties\":false}")
                .build();
          }

          public String call(String input) {
            try {
              var values = ResponsesAcceptanceRuntime.JSON.readTree(input).path("files");
              if (!values.isArray() || values.size() != 1)
                throw new IllegalStateException("Owned write bounds rejected");
              Path requested =
                  runtime
                      .root
                      .resolve(values.get(0).path("path").asText())
                      .toAbsolutePath()
                      .normalize();
              String content = values.get(0).path("content").asText();
              if (!requested.equals(output) || !(nonce + ":7417").equals(content))
                throw new IllegalStateException("Owned write bounds rejected");
              Files.writeString(output, content, StandardOpenOption.CREATE_NEW);
              return "{\"written\":true,\"accepted-value\":\"" + nonce + "\"}";
            } catch (java.io.IOException failure) {
              throw new IllegalStateException("Owned write evidence failed");
            }
          }
        };
    var catalog = dev.mikoto2000.rei.llm.discovery.ToolCatalog.standard(List.of(read, write));
    try (var migration =
            new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(
                runtime.root.resolve("data"));
        var runs =
            new dev.mikoto2000.rei.application.run.RunService(
                registry,
                bus,
                new dev.mikoto2000.rei.event.AgentEventFactory(clock),
                cancellation,
                id -> false)) {
      migration.prepare();
      var sessions =
          new dev.mikoto2000.rei.conversation.SqliteSessionRepository(runtime.root.resolve("data"));
      if (writer)
        sessions.accept(
            new dev.mikoto2000.rei.application.session.SessionMetadata(
                owner.conversationId(),
                project.id(),
                "Owned approval fixture",
                clock.instant(),
                clock.instant()),
            () -> {});
      var core =
          ResponsesCoreLiveAcceptance.core(
              runtime,
              List.of(catalog.callback("now"), catalog.callback("writeMultiFile")),
              null,
              cancellation,
              bus);
      runtime.beans.addBean("registry", registry);
      runtime.beans.addBean("runs", runs);
      runtime.beans.addBean("projects", projectRegistry);
      runtime.beans.addBean("sessions", sessions);
      runtime.beans.addBean("chat", core);
      runtime.beans.addBean(
          "router",
          new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
              Runnable::run, (context, request, queue) -> core.execute(context, request, queue)));
      var continuation =
          new dev.mikoto2000.rei.core.policy.ApprovalContinuationService(
              approvals,
              saved,
              runtime.beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
              runtime.beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
              runtime.beans.getBeanProvider(
                  dev.mikoto2000.rei.core.chat.ConversationInputRouter.class),
              runtime.beans.getBeanProvider(
                  dev.mikoto2000.rei.core.chat.ChatExecutionService.class),
              runtime.beans.getBeanProvider(dev.mikoto2000.rei.slack.SlackAgentService.class),
              runtime.beans.getBeanProvider(dev.mikoto2000.rei.core.project.ProjectRegistry.class),
              runtime.beans.getBeanProvider(
                  dev.mikoto2000.rei.application.session.SessionRepository.class),
              source,
              clock);
      runtime.beans.addBean("continuation", continuation);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          core,
          "setContinuations",
          runtime.beans.getBeanProvider(dev.mikoto2000.rei.core.chat.RunContinuation.class));
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          core, "setToolPermissionGuard", guard);
      if (writer) {
        if (Files.exists(idPath))
          throw new IllegalStateException("Approval fixture already started");
        registry.register(owner);
        var outcome = new java.util.concurrent.atomic.AtomicReference<ChatExecutionResult>();
        runs.execute(
            owner,
            () ->
                outcome.set(
                    core.execute(
                        owner,
                        "First call now exactly once to get the fixture data. Find the smallest positive x matching its moduli and remainders. Then call writeMultiFile to write one file named "
                            + output.getFileName()
                            + " with content accepted-value + ':' + the decimal x, without any newline. After the approved write succeeds, return only accepted-value. Do not repeat completed operations.",
                        new dev.mikoto2000.rei.core.chat.UserInterventionQueue())));
        var pending =
            approvals.list(project.id()).stream()
                .filter(request -> request.runId().equals(owner.runId()))
                .findFirst()
                .orElseThrow();
        boolean waiting =
            outcome.get() != null
                && outcome.get().status() == ChatExecutionResult.Status.WAITING
                && saved.find(pending.id()).orElseThrow().state().equals("WAITING_APPROVAL")
                && !Files.exists(output);
        Files.writeString(idPath, pending.id(), StandardOpenOption.CREATE_NEW);
        runtime.report(
            "APPROVAL_WAIT",
            Map.of(
                "success",
                waiting,
                "scenario",
                prefix,
                "run_status",
                registry.get(owner.runId()).status(),
                "prefix_reads",
                Files.readAllLines(readCount).size(),
                "write_exists",
                Files.exists(output),
                "budget",
                runtime.budget.snapshot()));
        if (!waiting) throw new IllegalStateException("Approval did not safely suspend");
      } else {
        String id = Files.readString(idPath);
        continuation.decide(project.id(), id, !denied, !denied);
        continuation.pump();
        String state = continuation.state(id).orElseThrow();
        boolean correct =
            denied
                ? !Files.exists(output) && state.equals("DENIED")
                : Files.exists(output)
                    && (nonce + ":7417").equals(Files.readString(output))
                    && state.equals("COMPLETED");
        runtime.report(
            denied ? "APPROVAL_DENIED" : "APPROVAL_RESUMED",
            Map.of(
                "success",
                correct,
                "state",
                state,
                "run_status",
                registry.get(owner.runId()).status(),
                "prefix_reads",
                Files.readAllLines(readCount).size(),
                "write_exists",
                Files.exists(output),
                "budget",
                runtime.budget.snapshot()));
        if (!correct || Files.readAllLines(readCount).size() != 1)
          throw new IllegalStateException("Approval replay criterion failed");
      }
    }
  }
}
