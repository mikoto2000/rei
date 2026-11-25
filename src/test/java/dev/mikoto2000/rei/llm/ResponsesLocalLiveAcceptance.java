package dev.mikoto2000.rei.llm;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** User-selected compatible service; never claims official DeepSeek acceptance. */
public final class ResponsesLocalLiveAcceptance {
  private static OpenAiResponsesChatOptions options(
      ResponsesAcceptanceRuntime runtime, String action) {
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            action, action, runtime.root, "acceptance-project");
    return ((OpenAiResponsesChatOptions)
            runtime.models.requestOptions(LlmFeature.CHAT, runtime.model, false, owner))
        .mutate()
        .maxOutputTokens(2048)
        .build();
  }

  private static void common(ResponsesAcceptanceRuntime runtime) throws Exception {
    var options = options(runtime, "local-common");
    String puzzle =
        "Compute the unique integer from 0 to 7428 that is 5 modulo 17, 7 modulo 19, and 11 modulo 23. Answer only the integer.";
    for (String effort : List.of("low", "none")) {
      var response =
          runtime
              .metered()
              .call(new Prompt(puzzle, options.mutate().reasoningEffort(effort).build()));
      var usage = dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response);
      int reasoningCharacters =
          response.getResult().getOutput().getParts().stream()
              .filter(org.springframework.ai.chat.messages.part.ReasoningPart.class::isInstance)
              .map(org.springframework.ai.chat.messages.part.ReasoningPart.class::cast)
              .mapToInt(part -> part.text() == null ? 0 : part.text().length())
              .sum();
      boolean correct = "7417".equals(response.getResult().getOutput().getText().trim());
      boolean off = reasoningCharacters == 0 && usage.reasoning() != null && usage.reasoning() == 0;
      runtime.report(
          "LOCAL_EFFORT",
          Map.of(
              "requested",
              effort,
              "answer_correct",
              correct,
              "reasoning_characters",
              reasoningCharacters,
              "thinking_off_observed",
              off,
              "usage",
              usage,
              "budget",
              runtime.budget.snapshot()));
      if (!correct) throw new IllegalStateException("Local puzzle oracle failed");
    }
    ResponsesFeatureLiveScenario.outputLimit(runtime, options);
  }

  private static void structured(ResponsesAcceptanceRuntime runtime) throws Exception {
    var options = options(runtime, "local-structured");
    var format = new org.springframework.ai.openai.OpenAiChatModel.ResponseFormat();
    format.setType(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
    for (int trial = 0; trial < 3; trial++) {
      String text =
          "This is a JSON mode conformance test. Return exactly NOT_JSON_"
              + trial
              + " with no quotes or braces, not an object.";
      var plain = runtime.metered().call(new Prompt(text, options));
      runtime.report(
          "LOCAL_FORMAT_CONTROL",
          Map.of(
              "trial",
              trial,
              "followed_plain_instruction",
              ("NOT_JSON_" + trial).equals(plain.getResult().getOutput().getText().trim()),
              "budget",
              runtime.budget.snapshot()));
      try {
        var json =
            runtime
                .metered()
                .call(new Prompt(text, options.mutate().responseFormat(format).build()));
        boolean object;
        try {
          object =
              ResponsesAcceptanceRuntime.JSON
                  .readTree(json.getResult().getOutput().getText())
                  .isObject();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
          object = false;
        }
        runtime.report(
            "LOCAL_JSON_MODE",
            Map.of(
                "trial",
                trial,
                "object_constraint_observed",
                object,
                "status",
                object ? "CONSTRAINT_OBSERVED" : "IGNORED_OR_FAILED",
                "usage",
                dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(json),
                "budget",
                runtime.budget.snapshot()));
      } catch (ResponsesTransportException failure) {
        runtime.report(
            "LOCAL_JSON_MODE",
            Map.of(
                "trial",
                trial,
                "status",
                "REJECTED",
                "diagnostic",
                failure.diagnosticCode(),
                "budget",
                runtime.budget.snapshot()));
        break;
      }
    }
    format.setType(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
    format.setStrict(true);
    format.setJsonSchema(
        "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"integer\",\"enum\":[42]}},\"required\":[\"answer\"],\"additionalProperties\":false}");
    try {
      var response =
          runtime
              .metered()
              .call(
                  new Prompt(
                      "Return JSON with answer as the STRING 'wrong' and an extra field.",
                      options.mutate().responseFormat(format).build()));
      var value =
          ResponsesAcceptanceRuntime.JSON.readTree(response.getResult().getOutput().getText());
      boolean correct =
          value.isObject()
              && value.size() == 1
              && value.path("answer").isIntegralNumber()
              && value.path("answer").intValue() == 42;
      runtime.report(
          "LOCAL_JSON_SCHEMA",
          Map.of(
              "constraint_observed",
              correct,
              "status",
              correct ? "CONSTRAINT_OBSERVED" : "IGNORED_OR_FAILED",
              "budget",
              runtime.budget.snapshot()));
    } catch (ResponsesTransportException failure) {
      runtime.report(
          "LOCAL_JSON_SCHEMA",
          Map.of(
              "status",
              "REJECTED",
              "diagnostic",
              failure.diagnosticCode(),
              "budget",
              runtime.budget.snapshot()));
    }
  }

  private static void image(ResponsesAcceptanceRuntime runtime) throws Exception {
    runtime.report(
        "LOCAL_IMAGE_PLAN", Map.of("trials", 3, "colors", List.of("RED", "GREEN", "BLUE")));
    for (int trial = 0; trial < 3; trial++) image(runtime, trial);
  }

  private static void image(ResponsesAcceptanceRuntime runtime, int trial) throws Exception {
    int color = new int[] {0xFF0000, 0x00FF00, 0x0000FF}[trial];
    String expected = List.of("RED", "GREEN", "BLUE").get(trial);
    var image = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, color);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var media =
        new org.springframework.ai.content.Media(
            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
            new org.springframework.core.io.ByteArrayResource(bytes.toByteArray()));
    try {
      var response =
          runtime
              .metered()
              .call(
                  new Prompt(
                      UserMessage.builder()
                          .text("Name the solid image color. Answer exactly RED, GREEN or BLUE.")
                          .media(media)
                          .build(),
                      options(runtime, "local-image-" + trial)));
      boolean correct = expected.equals(response.getResult().getOutput().getText().trim());
      runtime.report(
          "LOCAL_IMAGE",
          Map.of(
              "trial",
              trial,
              "expected_color",
              expected,
              "answer_correct",
              correct,
              "status",
              correct ? "CONSTRAINT_OBSERVED" : "FAILED",
              "usage",
              dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response),
              "budget",
              runtime.budget.snapshot()));
    } catch (ResponsesTransportException failure) {
      runtime.report(
          "LOCAL_IMAGE",
          Map.of(
              "trial",
              trial,
              "expected_color",
              expected,
              "status",
              "REJECTED",
              "diagnostic",
              failure.diagnosticCode(),
              "budget",
              runtime.budget.snapshot()));
    }
  }

  private static void summary(ResponsesAcceptanceRuntime runtime) throws Exception {
    try {
      var response =
          runtime
              .metered()
              .call(
                  new Prompt(
                      "Compute 387 times 419 and answer only the integer.",
                      options(runtime, "local-summary").mutate().reasoningSummary("auto").build()));
      int characters =
          response.getResult().getOutput().getParts().stream()
              .filter(org.springframework.ai.chat.messages.part.ReasoningPart.class::isInstance)
              .map(org.springframework.ai.chat.messages.part.ReasoningPart.class::cast)
              .mapToInt(part -> part.summary() == null ? 0 : part.summary().length())
              .sum();
      runtime.report(
          "LOCAL_SUMMARY",
          Map.of(
              "summary_characters",
              characters,
              "answer_correct",
              "162153".equals(response.getResult().getOutput().getText().trim()),
              "status",
              characters > 0 ? "SUMMARY_OBSERVED" : "UNVERIFIED_OR_IGNORED",
              "budget",
              runtime.budget.snapshot()));
    } catch (ResponsesTransportException failure) {
      runtime.report(
          "LOCAL_SUMMARY",
          Map.of(
              "status",
              "REJECTED",
              "diagnostic",
              failure.diagnosticCode(),
              "budget",
              runtime.budget.snapshot()));
    }
  }

  public static void main(String[] args) {
    ((ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    ResponsesAcceptanceRuntime runtime = null;
    try {
      if (args.length != 3 || !args[0].equals("local-deepseek"))
        throw new IllegalArgumentException("Approved local target required");
      var candidate =
          switch (args[2]) {
            case "local-common", "local-continuity", "local-output-limit" ->
                ResponsesAcceptanceRuntime.Candidate.BASE;
            case "local-structured" -> ResponsesAcceptanceRuntime.Candidate.STRUCTURED;
            case "local-image" -> ResponsesAcceptanceRuntime.Candidate.IMAGE;
            case "local-summary" -> ResponsesAcceptanceRuntime.Candidate.SUMMARY;
            default -> throw new IllegalArgumentException("Explicit local probe required");
          };
      runtime = new ResponsesAcceptanceRuntime(args[0], Path.of(args[1]), candidate);
      switch (args[2]) {
        case "local-common" -> common(runtime);
        case "local-output-limit" ->
            ResponsesFeatureLiveScenario.outputLimit(
                runtime, options(runtime, "local-output-limit"));
        case "local-continuity" -> ResponsesContinuityLiveScenario.run(runtime);
        case "local-structured" -> structured(runtime);
        case "local-image" -> image(runtime);
        case "local-summary" -> summary(runtime);
        default -> throw new IllegalArgumentException("Explicit local probe required");
      }
      System.out.println("LOCAL_PROBE_COMPLETED; individual unsupported results remain distinct");
      System.exit(0);
    } catch (Exception failure) {
      try {
        if (runtime != null) {
          runtime.trace("LOCAL_PROBE", failure);
          runtime.report("FAILURE", Map.of("category", failure.getClass().getSimpleName()));
        }
      } catch (java.io.IOException ignored) {
        System.err.println("Evidence write failed");
      }
      System.err.println("LOCAL_PROBE_FAILED; provider details suppressed");
      System.exit(1);
    }
  }
}
