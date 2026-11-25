package dev.mikoto2000.rei.llm;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Three paired synthetic trials, fixed before execution; no production replay omission. */
final class ResponsesContinuityLiveScenario {
  private ResponsesContinuityLiveScenario() {}

  static void run(ResponsesAcceptanceRuntime runtime) throws Exception {
    runtime.report(
        "CONTINUITY_PLAN",
        Map.of(
            "paired_trials",
            3,
            "max_requests",
            30,
            "A",
            "original completed assistant with native reasoning",
            "B",
            "NOT_RUN: strict production replay contract is retained",
            "C",
            "existing LlmConversationCompressor on completed history",
            "D",
            "existing ToolDiscovery, three independent specialist tasks; not a matched quality comparison"));
    for (int trial = 0; trial < 3; trial++) {
      final int index = trial;
      var owner =
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              "continuity-verified-" + trial,
              "continuity-verified-" + trial,
              runtime.root,
              "acceptance-project");
      var originalOptions =
          (OpenAiResponsesChatOptions)
              runtime.models.requestOptions(LlmFeature.CHAT, runtime.model, false, owner);
      final var options =
          runtime.target.equals("openai")
              ? originalOptions.mutate().reasoningEffort("medium").maxOutputTokens(2048).build()
              : originalOptions;
      String nonce = "fact-" + java.util.UUID.randomUUID();
      var system =
          new SystemMessage(
              "Synthetic acceptance. Retain the exact fact identifier and computed integer. Do not invent tool operations. Return only JSON with fact and answer fields.");
      var user =
          new UserMessage(
              "Fact identifier: "
                  + nonce
                  + ". Compute the unique integer from 0 to 7428 that is 5 modulo 17, 7 modulo 19, and 11 modulo 23. Return fact and answer.");
      var first = runtime.metered().call(new Prompt(List.of(system, user), options));
      verify(first.getResult().getOutput().getText(), nonce, 7417);
      var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
      var ref =
          runtime.payloads.save(
              scope, owner.runId(), "continuity-original", first.getResult().getOutput());
      var restored = runtime.payloads.load(scope, ref);
      List<Message> complete = List.of(system, user, restored);
      var next =
          new UserMessage(
              "Now add one to the computed integer. Retain the original fact. Return only JSON with fact and answer fields.");
      var replay = new java.util.ArrayList<Message>(complete);
      replay.add(next);
      long started = System.nanoTime();
      var continued = runtime.metered().call(new Prompt(replay, options));
      verify(continued.getResult().getOutput().getText(), nonce, 7418);
      runtime.report(
          "CONTINUITY_A",
          Map.of(
              "trial",
              trial,
              "success",
              true,
              "payload_hash",
              ref.sha256(),
              "duration_ms",
              (System.nanoTime() - started) / 1000000.0,
              "usage",
              dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(continued),
              "budget",
              runtime.budget.snapshot()));

      // Route the existing summarizer through the same real model and durable acceptance meter.
      var summaryModels =
          new LlmModelProvider(runtime.metered(), new LlmProperties()) {
            @Override
            public org.springframework.ai.chat.model.ChatModel chatModel(String feature) {
              if (!LlmFeature.CHAT.equals(feature))
                throw new IllegalArgumentException("Unapproved summary feature");
              return runtime.metered();
            }

            @Override
            public org.springframework.ai.model.tool.ToolCallingChatOptions requestOptions(
                String feature, String selected, boolean streamUsage) {
              if (!LlmFeature.CHAT.equals(feature))
                throw new IllegalArgumentException("Unapproved summary feature");
              return runtime.models.requestOptions(feature, selected, streamUsage);
            }
          };
      var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
      beans.addBean("models", summaryModels);
      var settings = new dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties();
      settings.setSummaryGenerationReserve(1024);
      settings.setStandaloneSummaryMaxLlmCalls(1);
      settings.setStandaloneSummaryMaxTotalTokens(50000);
      var compressor =
          new dev.mikoto2000.rei.core.contextbudget.LlmConversationCompressor(
              beans.getBeanProvider(LlmModelProvider.class), settings);
      started = System.nanoTime();
      String summary = compressor.summarize("", complete, 512, new Prompt(complete, options));
      var compressed =
          runtime
              .metered()
              .call(
                  new Prompt(
                      List.of(
                          system,
                          new UserMessage("Summary of completed prior work:\n" + summary),
                          next),
                      options));
      verify(compressed.getResult().getOutput().getText(), nonce, 7418);
      runtime.report(
          "CONTINUITY_C",
          Map.of(
              "trial",
              index,
              "success",
              true,
              "summary_bytes",
              summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
              "duration_ms",
              (System.nanoTime() - started) / 1000000.0,
              "usage",
              dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(compressed),
              "budget",
              runtime.budget.snapshot()));
    }
    runtime.properties.getOutputLimit().setMaxLlmCallsPerRun(6);
    for (int trial = 0; trial < 3; trial++)
      ResponsesCoreLiveAcceptance.discovery(runtime, "continuity-" + trial);
  }

  private static void verify(String text, String fact, int expected) throws Exception {
    var value = ResponsesAcceptanceRuntime.JSON.readTree(text);
    if (!value.isObject()
        || value.size() != 2
        || !fact.equals(value.path("fact").asText())
        || !value.path("answer").isIntegralNumber()
        || value.path("answer").intValue() != expected)
      throw new IllegalStateException("Continuity state did not match the synthetic oracle");
  }
}
