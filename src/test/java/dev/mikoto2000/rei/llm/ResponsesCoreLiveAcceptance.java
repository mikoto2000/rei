package dev.mikoto2000.rei.llm;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/** Actual Core entry path using owned tools/data and no application configuration discovery. */
public final class ResponsesCoreLiveAcceptance {
  private static final String SYSTEM =
      "You are executing an isolated synthetic acceptance test. Use only the supplied owned fixture tools. Follow the requested final-answer format.";

  static dev.mikoto2000.rei.core.chat.ChatExecutionService core(
      ResponsesAcceptanceRuntime runtime,
      List<ToolCallback> tools,
      dev.mikoto2000.rei.llm.discovery.ToolDiscovery discovery) {
    return core(
        runtime,
        tools,
        discovery,
        new dev.mikoto2000.rei.core.service.CommandCancellationService(),
        new dev.mikoto2000.rei.event.InMemoryAgentEventBus());
  }

  static dev.mikoto2000.rei.core.chat.ChatExecutionService core(
      ResponsesAcceptanceRuntime runtime,
      List<ToolCallback> tools,
      dev.mikoto2000.rei.llm.discovery.ToolDiscovery discovery,
      dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,
      dev.mikoto2000.rei.event.AgentEventPublisher events) {
    var model =
        new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(runtime.metered(), null, 0);
    if (discovery != null) model.withToolDiscovery(discovery);
    org.springframework.ai.chat.model.ChatModel observed =
        new org.springframework.ai.chat.model.ChatModel() {
          public org.springframework.ai.chat.model.ChatResponse call(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            try {
              return model.call(prompt);
            } catch (RuntimeException failure) {
              runtime.trace("STAGNATION", failure);
              throw failure;
            }
          }

          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            return model.stream(prompt).doOnError(failure -> runtime.trace("STAGNATION", failure));
          }

          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return model.getOptions();
          }
        };
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(observed)
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultSystem(SYSTEM)
            .defaultTools(tools.toArray())
            .build();
    var clients =
        new FixedLlmChatClientProvider(client) {
          public org.springframework.ai.chat.client.ChatClient parallelChatClient(
              dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
            return client;
          }
        };
    var core =
        new dev.mikoto2000.rei.core.chat.ChatExecutionService(
            clients,
            new dev.mikoto2000.rei.core.service.ModelHolderService(runtime.model),
            runtime.models,
            runtime.properties,
            cancellation,
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            Clock.systemUTC(),
            java.util.Optional.empty(),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            events);
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            runtime.root.resolve("logs"), Clock.systemUTC(), ResponsesAcceptanceRuntime.JSON);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        core, "setConversationLogStore", logs);
    return core;
  }

  private static void coreSmoke(ResponsesAcceptanceRuntime runtime) throws Exception {
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "core-smoke", "core-session", runtime.root, "acceptance-project");
    var outcome =
        core(runtime, List.of(), null)
            .execute(
                owner,
                "Return exactly REI_ACCEPTANCE_OK with no other text.",
                new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
    runtime.report(
        "CORE",
        Map.of(
            "success",
            outcome.success(),
            "stop_code",
            outcome.stopCode() == null ? "NONE" : outcome.stopCode(),
            "answer_correct",
            "REI_ACCEPTANCE_OK".equals(outcome.text().trim()),
            "budget",
            runtime.budget.snapshot()));
    if (!outcome.success() || !"REI_ACCEPTANCE_OK".equals(outcome.text().trim()))
      throw new IllegalStateException("Core acceptance criterion failed");
  }

  static void discovery(ResponsesAcceptanceRuntime runtime, String suffix) throws Exception {
    com.fasterxml.jackson.databind.JsonNode catalog;
    try (var input =
        ResponsesCoreLiveAcceptance.class.getResourceAsStream("/tool-discovery-catalog.json")) {
      catalog = ResponsesAcceptanceRuntime.JSON.readTree(input);
    }
    var groups = new java.util.HashMap<String, Integer>();
    for (var entry : catalog) groups.merge(entry.path("set").asText(), 1, Integer::sum);
    String chosen = null;
    for (var entry : catalog)
      if (!entry.path("basic").asBoolean()
          && entry.path("name").asText().startsWith("get")
          && entry.path("dependencies").isEmpty()
          && groups.get(entry.path("set").asText()) == 1) {
        chosen = entry.path("name").asText();
        break;
      }
    if (chosen == null) throw new IllegalStateException("Owned specialist fixture unavailable");
    final String specialist = chosen;
    String nonce =
        runtime.target.equals("fixture")
            ? "fixture-owned-value"
            : "accepted-" + java.util.UUID.randomUUID();
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "discovery-" + suffix, "discovery-" + suffix, runtime.root, "acceptance-project");
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true,
                    null,
                    null,
                    Map.of(
                        specialist,
                        java.util.Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ)))),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    var callbacks = new java.util.ArrayList<ToolCallback>();
    for (var entry : catalog) {
      String name = entry.path("name").asText();
      callbacks.add(
          new ToolCallback() {
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
              return org.springframework.ai.tool.definition.ToolDefinition.builder()
                  .name(name)
                  .description(
                      name.equals(specialist)
                          ? "Owned fixture READ: return the accepted-value field. Call once."
                          : "Disabled owned fixture callback; do not call.")
                  .inputSchema(
                      "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                  .build();
            }

            public String call(String input) {
              if (!name.equals(specialist))
                throw new dev.mikoto2000.rei.core.policy.ToolPermissionException(
                    name, dev.mikoto2000.rei.core.policy.PermissionDecision.DENY);
              long started = System.nanoTime();
              guard.validateArguments(name, input, owner);
              guard.check(name, input, owner);
              if (executions.incrementAndGet() != 1)
                throw new IllegalStateException("Synthetic specialist repeated");
              try {
                runtime.report(
                    "TOOL_DURATION",
                    Map.of("tool", name, "duration_ms", (System.nanoTime() - started) / 1000000.0));
              } catch (java.io.IOException failure) {
                throw new IllegalStateException("Owned tool timing evidence failed");
              }
              return "{\"accepted-value\":\"" + nonce + "\"}";
            }
          });
    }
    var settings = new dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties();
    settings.setEnabled(true);
    settings.setMaxActiveTools(36);
    var store =
        new dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore(
            runtime.source("discovery.db"),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.review.ReviewCoverageLedger.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.durable.DurableTaskStore.class));
    runtime.beans.addBean("discovery", store);
    var discovery =
        new dev.mikoto2000.rei.llm.discovery.ToolDiscovery(
            settings,
            runtime.beans.getBeanProvider(
                dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore.class));
    var outcome =
        core(runtime, callbacks, discovery)
            .execute(
                owner,
                "Use searchTools to discover "
                    + specialist
                    + ", then call "
                    + specialist
                    + " exactly once. Return only its accepted-value field, without quotes or other text.",
                new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
    runtime.report(
        "DISCOVERY",
        Map.of(
            "success",
            outcome.success(),
            "answer_correct",
            nonce.equals(outcome.text().trim()),
            "specialist",
            specialist,
            "executions",
            executions.get(),
            "budget",
            runtime.budget.snapshot(),
            "metadata_saved",
            store.load(owner).isPresent(),
            "stop_code",
            outcome.stopCode()));
    if (!outcome.success() || !nonce.equals(outcome.text().trim()) || executions.get() != 1)
      throw new IllegalStateException("Discovery acceptance criterion failed");
  }

  private static void checkpoint(ResponsesAcceptanceRuntime runtime, boolean writer)
      throws Exception {
    checkpoint(runtime, writer, "", false);
  }

  private static void checkpoint(
      ResponsesAcceptanceRuntime runtime, boolean writer, String namespace, boolean abrupt)
      throws Exception {
    if (!List.of("", "operational-").contains(namespace))
      throw new IllegalArgumentException("Owned checkpoint namespace required");
    Path nonceFile = runtime.root.resolve(namespace + "checkpoint-nonce.txt");
    if (!java.nio.file.Files.exists(nonceFile))
      java.nio.file.Files.writeString(
          nonceFile,
          "checkpoint-" + java.util.UUID.randomUUID(),
          java.nio.file.StandardOpenOption.CREATE_NEW);
    String nonce = java.nio.file.Files.readString(nonceFile);
    Path effects = runtime.root.resolve(namespace + "checkpoint-effects.txt");
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            namespace + "checkpoint-before",
            namespace + "checkpoint-session",
            runtime.root,
            "acceptance-project");
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true, null, null, null)),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            event -> {});
    ToolCallback callback =
        new ToolCallback() {
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("now")
                .description(
                    "Read the owned checkpoint fixture's accepted-value exactly once. No current-time information is returned.")
                .inputSchema(
                    "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .build();
          }

          public String call(String input) {
            guard.validateArguments("now", input, owner);
            guard.check("now", input, owner);
            try {
              if (java.nio.file.Files.exists(effects))
                throw new IllegalStateException("Successful checkpoint prefix repeated");
              java.nio.file.Files.writeString(
                  effects, "fixture-read\n", java.nio.file.StandardOpenOption.CREATE_NEW);
              return "{\"accepted-value\":\"" + nonce + "\"}";
            } catch (java.io.IOException failure) {
              throw new IllegalStateException("Owned counter write failed");
            }
          }
        };
    var catalog = dev.mikoto2000.rei.llm.discovery.ToolCatalog.standard(List.of(callback));
    var core = core(runtime, List.of(catalog.callback("now")), null);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        core, "setToolPermissionGuard", guard);
    var settings = new dev.mikoto2000.rei.checkpoint.CheckpointProperties();
    var repository =
        new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(
            runtime.source("checkpoint.db"), settings);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        repository,
        "configureModelMessagePayloads",
        runtime.beans.getBeanProvider(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
    var outcome =
        new java.util.concurrent.atomic.AtomicReference<
            dev.mikoto2000.rei.core.chat.ChatExecutionResult>();
    runtime.beans.addBean(
        "router",
        new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
            Runnable::run,
            (resumed, prompt, interventions) ->
                outcome.set(core.execute(resumed, prompt, interventions))));
    try (var checkpoints =
        new dev.mikoto2000.rei.checkpoint.PersistentCheckpointService(
            repository,
            new dev.mikoto2000.rei.checkpoint.CheckpointReconciler(null),
            settings,
            new dev.mikoto2000.rei.event.InMemoryAgentEventBus(),
            runtime.beans.getBeanProvider(
                dev.mikoto2000.rei.core.chat.ConversationInputRouter.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
            runtime.beans.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
            runtime.beans.getBeanProvider(
                dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class)) {
          @Override
          public void preserveResults(
              dev.mikoto2000.rei.core.chat.AgentRunContext run,
              List<org.springframework.ai.chat.messages.Message> history,
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState progress) {
            super.preserveResults(run, history, progress);
            if (writer
                && progress != null
                && progress.protocolHistory() != null
                && progress.protocolHistory().entries().stream()
                    .anyMatch(entry -> entry.key().endsWith("/TOOL"))) {
              try {
                java.nio.file.Files.writeString(
                    runtime.root.resolve(namespace + "checkpoint-task.txt"),
                    repository.findByRun(run.projectId(), run.runId()).orElseThrow().taskId(),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
                runtime.report(
                    "CHECKPOINT_WRITE",
                    Map.of(
                        "status",
                        "SAVED",
                        "operational_profile",
                        runtime.operational,
                        "abrupt",
                        abrupt,
                        "namespace",
                        namespace,
                        "effects",
                        java.nio.file.Files.readAllLines(effects).size(),
                        "budget",
                        runtime.budget.snapshot()));
              } catch (java.io.IOException failure) {
                throw new IllegalStateException("Checkpoint evidence write failed");
              }
              if (abrupt) Runtime.getRuntime().halt(0);
              System.out.println("CHECKPOINT_WRITER_EXITED_AFTER_DURABLE_RESULT");
              System.exit(0);
            }
          }
        }) {
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          core, "setCheckpoints", checkpoints);
      if (writer) {
        if (java.nio.file.Files.exists(runtime.root.resolve(namespace + "checkpoint-task.txt")))
          throw new IllegalStateException("Checkpoint fixture already started");
        core.execute(
            owner,
            "Call now exactly once. Return only the accepted-value field from its result, with no quotes or other text.",
            new dev.mikoto2000.rei.core.chat.UserInterventionQueue());
        throw new IllegalStateException("Checkpoint writer did not reach durable tool boundary");
      }
      String task =
          java.nio.file.Files.readString(runtime.root.resolve(namespace + "checkpoint-task.txt"));
      var resumed =
          checkpoints.resume(
              "acceptance-project",
              task,
              dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL);
      var completed = outcome.get();
      boolean correct =
          completed != null && completed.success() && nonce.equals(completed.text().trim());
      runtime.report(
          "CHECKPOINT_READ",
          Map.of(
              "success",
              correct,
              "operational_profile",
              runtime.operational,
              "namespace",
              namespace,
              "effects",
              java.nio.file.Files.readAllLines(effects).size(),
              "task_status",
              repository.get("acceptance-project", task).status(),
              "previous_run_matches",
              (namespace + "checkpoint-before").equals(resumed.previousRunId()),
              "budget",
              runtime.budget.snapshot()));
      if (!correct || java.nio.file.Files.readAllLines(effects).size() != 1)
        throw new IllegalStateException("Checkpoint replay acceptance criterion failed");
    }
  }

  public static void main(String[] args) {
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    ResponsesAcceptanceRuntime runtime = null;
    try {
      if (args.length != 3)
        throw new IllegalArgumentException("Target, owned root and action required");
      runtime =
          new ResponsesAcceptanceRuntime(
              args[0],
              Path.of(args[1]),
              args[2].startsWith("checkpoint-operational-")
                  ? ResponsesAcceptanceRuntime.Candidate.OPERATIONAL
                  : ResponsesAcceptanceRuntime.Candidate.BASE);
      final var tracingRuntime = runtime;
      try (var diagnostics =
          org.mockito.Mockito.mockStatic(
              dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.class,
              invocation -> {
                for (var argument : invocation.getArguments())
                  if (argument instanceof Throwable failure)
                    tracingRuntime.trace("CORE_DIAGNOSTIC", failure);
                return invocation.callRealMethod();
              })) {
        if (args[2].equals("core")) coreSmoke(runtime);
        else if (args[2].equals("discovery")) discovery(runtime, "run");
        else if (args[2].equals("discovery-debug")) discovery(runtime, "debug");
        else if (args[2].equals("discovery-trace")) discovery(runtime, "trace");
        else if (args[2].equals("discovery-verified")) discovery(runtime, "verified");
        else if (args[2].equals("discovery-metrics")) discovery(runtime, "metrics");
        else if (args[2].equals("checkpoint-write")) checkpoint(runtime, true);
        else if (args[2].equals("checkpoint-read")) checkpoint(runtime, false);
        else if (args[2].equals("checkpoint-operational-write"))
          checkpoint(runtime, true, "operational-", true);
        else if (args[2].equals("checkpoint-operational-read"))
          checkpoint(runtime, false, "operational-", false);
        else if (args[2].startsWith("approval-"))
          ResponsesApprovalLiveScenario.run(runtime, args[2]);
        else if (args[2].equals("features")) ResponsesFeatureLiveScenario.run(runtime);
        else if (args[2].equals("continuity")) ResponsesContinuityLiveScenario.run(runtime);
        else if (args[2].equals("storage-metrics")) ResponsesStorageLiveMetrics.run(runtime);
        else if (args[2].equals("output-limit")) {
          var owner =
              new dev.mikoto2000.rei.core.chat.AgentRunContext(
                  "output-limit", "output-limit", runtime.root, "acceptance-project");
          ResponsesFeatureLiveScenario.outputLimit(
              runtime,
              (org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)
                  runtime.models.requestOptions(LlmFeature.CHAT, runtime.model, false, owner));
        } else throw new IllegalArgumentException("Explicit acceptance action required");
      }
      System.out.println("CORE_ACCEPTANCE_ACTION_PASSED");
      System.exit(0);
    } catch (Exception failure) {
      try {
        if (runtime != null)
          runtime.report("FAILURE", Map.of("category", failure.getClass().getSimpleName()));
      } catch (java.io.IOException ignored) {
        System.err.println("Evidence write failed");
      }
      System.err.println("CORE_ACCEPTANCE_ACTION_FAILED (provider details suppressed)");
      System.exit(1);
    }
  }
}
