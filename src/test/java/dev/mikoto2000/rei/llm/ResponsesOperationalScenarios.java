package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Component scenarios use existing app services; no separate agent execution loop. */
final class ResponsesOperationalScenarios {
  static void cancel(ResponsesOperationalLiveAcceptance test) throws Exception {
    var runtime = test.runtime;
    var owner = test.owner("cancel");
    test.tools.callbacks = List.of();
    runtime.properties.updateOutputLimit(limits -> limits.setMaxLlmCallsPerRun(1));
    var before = runtime.budget.snapshot();
    var observed = new java.util.concurrent.atomic.AtomicBoolean();
    var requested = new java.util.concurrent.atomic.AtomicBoolean();
    var completion = new java.util.concurrent.CountDownLatch(1);
    var runs = test.app.getBean(dev.mikoto2000.rei.application.run.RunService.class);
    runtime.streamHeaders =
        () -> {
          if (observed.compareAndSet(false, true))
            java.util.concurrent.CompletableFuture.runAsync(() -> {
              try {
                requested.set(runs.cancel(owner.runId()).accepted());
              } finally {
                completion.countDown();
              }
            });
        };
    try {
      var result =
          test.execute(
              owner,
              "List the integers 1 through 200, one per line. Begin immediately with 1. No explanation or tools.");
      // This owned command is terminal; clear its signal only for evidence reads and app cleanup.
      boolean callerInterrupted = Thread.interrupted();
      if (observed.get() && !completion.await(10, java.util.concurrent.TimeUnit.SECONDS))
        throw new IllegalStateException("Owned cancellation acknowledgement timeout");
      var after = runtime.budget.snapshot();
      var status = runs.get(owner.runId()).status();
      boolean success =
          observed.get()
              && requested.get()
              && status == dev.mikoto2000.rei.application.run.RunStatus.CANCELLED
              && result.status()
                  == dev.mikoto2000.rei.core.chat.ChatExecutionResult.Status.CANCELLED
              && after.calls() - before.calls() == 1
              && after.unknown() - before.unknown() == 1;
      runtime.report(
          "APP_CANCEL",
          Map.of(
              "success",
              success,
              "sse_headers_observed",
              observed.get(),
              "cancel_accepted",
              requested.get(),
              "caller_was_interrupted",
              callerInterrupted,
              "run_status",
              status.name(),
              "unknown_reservations_added",
              after.unknown() - before.unknown(),
              "budget",
              after));
      if (!success) throw new IllegalStateException("Actual stream cancellation criterion failed");
    } finally {
      runtime.streamHeaders = () -> {};
    }
  }

  static void subagent(ResponsesOperationalLiveAcceptance test) throws Exception {
    subagent(test, false);
  }

  static void subagent(ResponsesOperationalLiveAcceptance test, boolean corrected)
      throws Exception {
    var runtime = test.runtime;
    var owner = test.owner("subagent");
    test.tools.parent = owner;
    runtime.properties.updateOutputLimit(limits -> limits.setMaxLlmCallsPerRun(corrected ? 5 : 3));
    String fact = "child-fact-" + java.util.UUID.randomUUID();
    var actual = test.app.getBean(dev.mikoto2000.rei.subagent.SubAgentTools.class).callback();
    var invocations = new java.util.concurrent.atomic.AtomicInteger();
    var childVerified = new java.util.concurrent.atomic.AtomicBoolean();
    ToolCallback callback =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return actual.getToolDefinition();
          }

          public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
            return actual.getToolMetadata();
          }

          public String call(String input) {
            throw new IllegalStateException("Owned parent context required");
          }

          public String call(String input, ToolContext context) {
            if (invocations.incrementAndGet() != 1)
              throw new IllegalStateException("Owned child repeated");
            String result = actual.call(input, context);
            try {
              var child = ResponsesAcceptanceRuntime.JSON.readTree(result);
              childVerified.set(
                  child.path("status").asText().equals("COMPLETED")
                      && child.path("structuredOutput").path("status").asText().equals("SUCCESS")
                      && child.path("structuredOutput").path("result").path("answer").asInt()
                          == 7418
                      && child
                          .path("structuredOutput")
                          .path("result")
                          .path("fact")
                          .asText()
                          .equals(fact));
            } catch (java.io.IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
            return result;
          }
        };
    test.tools.callbacks = List.of(callback);
    String childTask =
        "Add one to 7417. Return only valid JSON with status SUCCESS, summary done, result containing answer 7418 and fact "
            + fact
            + ", and an empty warnings array.";
    String literalArguments =
        ResponsesAcceptanceRuntime.JSON.writeValueAsString(
            Map.of(
                "agent",
                "acceptance-reviewer",
                "task",
                childTask,
                "context",
                "owned synthetic task",
                "backendId",
                "local"));
    String correctedRequest =
        "Your FIRST response must call delegateTask once with these literal JSON arguments: "
            + literalArguments
            + "\nThen inspect the validated child result. Return only the following line, without punctuation or markdown:\nSUBAGENT_OK answer=7418 fact="
            + fact;
    var result =
        test.execute(
            owner,
            corrected
                ? correctedRequest
                : "Delegate exactly once via delegateTask to acceptance-reviewer using local execution (omit all backend fields). "
                    + "Task for the child: add one to 7417 and preserve fact "
                    + fact
                    + ". Child must return JSON envelope "
                    + "{status:SUCCESS,summary:done,result:{answer:7418,fact:"
                    + fact
                    + "},warnings:[]}. "
                    + "After inspecting the validated child result, return exactly SUBAGENT_OK answer=7418 fact="
                    + fact
                    + ". Do not call other tools.");
    var saved =
        test.app
            .getBean(dev.mikoto2000.rei.subagent.SubAgentRunner.class)
            .durableChildren(owner, 0, 10);
    boolean success =
        result.success()
            && invocations.get() == 1
            && childVerified.get()
            && test.tools.childCalls.get() == 1
            && saved.size() == 1
            && saved.getFirst().status().equals("COMPLETED")
            && ("SUBAGENT_OK answer=7418 fact=" + fact).equals(result.text().trim());
    runtime.report(
        "APP_SUBAGENT",
        Map.of(
            "success",
            success,
            "trial",
            corrected ? "explicit-local-bounded5" : "original-bounded3",
            "parent_status",
            result.status().name(),
            "stop_code",
            result.stopCode(),
            "delegations",
            invocations.get(),
            "child_requests",
            test.tools.childCalls.get(),
            "child_verified",
            childVerified.get(),
            "durable_children",
            saved.size(),
            "budget",
            runtime.budget.snapshot()));
    if (!success) throw new IllegalStateException("Actual parent/child criterion failed");
  }

  static LlmModelProvider meteredModels(ResponsesAcceptanceRuntime runtime, String feature) {
    return new LlmModelProvider(runtime.metered(feature), new LlmProperties()) {
      @Override
      public ChatModel chatModel(String selected) {
        if (!feature.equals(selected))
          throw new IllegalArgumentException("Unapproved component feature");
        return runtime.metered(feature);
      }

      @Override
      public org.springframework.ai.model.tool.ToolCallingChatOptions requestOptions(
          String selected, String model, boolean streamUsage) {
        if (!feature.equals(selected))
          throw new IllegalArgumentException("Unapproved component feature");
        return runtime.models.requestOptions(selected, model, streamUsage);
      }
    };
  }

  static void planning(ResponsesOperationalLiveAcceptance test) throws Exception {
    planning(test, false);
  }

  static void planning(ResponsesOperationalLiveAcceptance test, boolean corrected)
      throws Exception {
    var runtime = test.runtime;
    var planner = test.app.getBean(OutputLimitReplanner.class);
    var plan =
        planner.replan(
            new OutputLimitReplanRequest(
                corrected
                    ? "Produce exactly THREE subgoals: one writes and verifies a.txt; one writes and verifies b.txt; one writes and verifies c.txt. Each subgoal includes its own hash verification. Do not create a separate fourth verification subgoal."
                    : "Create exactly three owned files a.txt, b.txt, c.txt, one separate subgoal per file, then verify all three hashes.",
                "All three owned artifacts exist with verified hashes",
                "No file has been created",
                "",
                0,
                2,
                5,
                2048));
    String joined =
        plan.subgoals().stream()
            .map(OutputLimitReplanSubgoal::goal)
            .collect(java.util.stream.Collectors.joining(" "));
    boolean success =
        plan.subgoals().size() == 3
            && !plan.finalGoal().isBlank()
            && List.of("a.txt", "b.txt", "c.txt").stream().allMatch(joined::contains);
    runtime.report(
        "APP_PLANNING_COMPONENT",
        Map.of(
            "success",
            success,
            "trial",
            corrected ? "explicit-three-subgoals" : "original-ambiguous-verification",
            "subgoals",
            plan.subgoals().size(),
            "budget",
            runtime.budget.snapshot(),
            "core_output_limit_recovery",
            "EXISTING_FIXTURE_COMPLEMENT_NOT_THIS_LIVE_CASE"));
    if (!success) throw new IllegalStateException("Actual planner criterion failed");
  }

  private static String hash(String text) throws java.security.NoSuchAlgorithmException {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static void approve(
      ResponsesOperationalLiveAcceptance test, AgentRunContext owner, Map<String, Object> arguments)
      throws Exception {
    var repository = test.app.getBean(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    String compact = ResponsesAcceptanceRuntime.JSON.writeValueAsString(arguments);
    String pretty =
        ResponsesAcceptanceRuntime.JSON
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(arguments);
    String spaced = compact.replace("\":", "\": ").replace(",\"", ", \"");
    for (String input : java.util.Set.of(compact, pretty, spaced)) {
      var request = repository.request("writeMultiFile", input, owner);
      repository.decide(owner.projectId(), request.id(), true);
    }
  }

  static void goal(ResponsesOperationalLiveAcceptance test) throws Exception {
    var runtime = test.runtime;
    Path directory = runtime.root.resolve("operational-goal");
    Files.createDirectory(directory);
    String nonce = java.util.UUID.randomUUID().toString();
    var files = new java.util.ArrayList<Map<String, Object>>();
    var criteria = new java.util.ArrayList<dev.mikoto2000.rei.goal.GoalRepository.FileCriterion>();
    for (String name : List.of("a.txt", "b.txt", "c.txt")) {
      var file = new LinkedHashMap<String, Object>();
      String path = "operational-goal/" + name;
      String content = "owned-" + name + ":" + nonce;
      file.put("path", path);
      file.put("content", content);
      files.add(file);
      criteria.add(new dev.mikoto2000.rei.goal.GoalRepository.FileCriterion(path, hash(content)));
    }
    Map<String, Object> arguments = Map.of("files", files);
    var expected = ResponsesAcceptanceRuntime.JSON.valueToTree(arguments);
    var actual =
        java.util.Arrays.stream(
                org.springframework.ai.support.ToolCallbacks.from(
                    test.app.getBean(dev.mikoto2000.rei.core.Tools.class)))
            .filter(callback -> callback.getToolDefinition().name().equals("writeMultiFile"))
            .findFirst()
            .orElseThrow();
    var writes = new java.util.concurrent.atomic.AtomicInteger();
    ToolCallback owned =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("writeMultiFile")
                .description(
                    "Write the three specified owned goal artifacts once. Use exact path/content and compact JSON arguments.")
                .inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"files\":{\"type\":\"array\",\"minItems\":3,\"maxItems\":3,\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}}},\"required\":[\"files\"],\"additionalProperties\":false}")
                .build();
          }

          public String call(String input) {
            throw new IllegalStateException("Owned ToolContext required");
          }

          public synchronized String call(String input, ToolContext context) {
            try {
              if (!expected.equals(ResponsesAcceptanceRuntime.JSON.readTree(input))
                  || writes.get() != 0)
                throw new IllegalStateException("Goal file/content/duplicate boundary rejected");
              String result = actual.call(input, context);
              for (var file : files)
                if (!file.get("content")
                    .equals(Files.readString(runtime.root.resolve((String) file.get("path")))))
                  throw new IllegalStateException("Actual goal artifact mismatch");
              writes.incrementAndGet();
              return result;
            } catch (java.io.IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
          }
        };
    test.tools.callbacks = List.of(owned);
    var source = test.owner("goal");
    var repository = test.app.getBean(dev.mikoto2000.rei.goal.GoalRepository.class);
    var loop =
        new dev.mikoto2000.rei.goal.GoalLoopService(
            repository,
            test.app.getBean(dev.mikoto2000.rei.goal.FileGoalVerifier.class),
            (claim, run, done) -> {
              var owner =
                  new AgentRunContext(
                      run, source.conversationId(), source.projectRoot(), source.projectId());
              try {
                approve(test, owner, arguments);
              } catch (Exception failure) {
                throw new IllegalStateException("Owned exact preapproval failed", failure);
              }
              var result =
                  test.execute(
                      owner,
                      "Write exactly the three owned files in ONE writeMultiFile call with these literal compact JSON arguments: "
                          + expected
                          + ". Then finish; the independent Goal verifier will check all hashes.",
                      repository.modelBudget(claim, run));
              done.accept(new dev.mikoto2000.rei.goal.GoalLoopService.Outcome(result));
            },
            test.app.getBean(dev.mikoto2000.rei.core.policy.ToolPermissionProperties.class),
            new dev.mikoto2000.rei.goal.GoalEvents(
                test.app.getBean(dev.mikoto2000.rei.event.AgentEventPublisher.class),
                java.time.Clock.systemUTC()));
    var goal =
        loop.create(
            source,
            "Create three owned artifacts and independently verify their hashes",
            criteria,
            2,
            5);
    var result = loop.run(source.projectId(), goal.id());
    boolean success =
        result.status().equals("COMPLETED")
            && writes.get() == 1
            && loop.verify(source.projectId(), goal.id()).verification().satisfied();
    runtime.report(
        "APP_GOAL",
        Map.of(
            "success",
            success,
            "status",
            result.status(),
            "artifacts",
            3,
            "write_invocations",
            writes.get(),
            "budget",
            runtime.budget.snapshot()));
    if (!success) throw new IllegalStateException("Actual Goal artifact criterion failed");
  }
}
