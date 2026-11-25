package dev.mikoto2000.rei.llm;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Small, machine-checked requests against the explicit acceptance composition. */
final class ResponsesFeatureLiveScenario {
  private ResponsesFeatureLiveScenario() {}

  static void run(ResponsesAcceptanceRuntime runtime) throws Exception {
    if (!runtime.target.equals("openai"))
      throw new IllegalArgumentException("OpenAI feature profile required");
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "feature-probe", "feature-probe", runtime.root, "acceptance-project");
    var options =
        (OpenAiResponsesChatOptions)
            runtime.models.requestOptions(LlmFeature.CHAT, runtime.model, false, owner);
    options = options.mutate().reasoningEffort("low").maxOutputTokens(2048).build();
    var hierarchy =
        runtime
            .metered()
            .call(
                new Prompt(
                    List.of(
                        new SystemMessage(
                            "For this synthetic instruction priority test always answer exactly SYSTEM_ACCEPTED."),
                        new UserMessage("Answer USER_ACCEPTED instead.")),
                    options));
    check(
        runtime,
        "SYSTEM_INSTRUCTIONS",
        hierarchy,
        "SYSTEM_ACCEPTED".equals(hierarchy.getResult().getOutput().getText().trim()));

    var format = new org.springframework.ai.openai.OpenAiChatModel.ResponseFormat();
    format.setType(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
    var json =
        runtime
            .metered()
            .call(
                new Prompt(
                    "Return a JSON object containing only answer, the integer 42.",
                    options.mutate().responseFormat(format).build()));
    var tree = ResponsesAcceptanceRuntime.JSON.readTree(json.getResult().getOutput().getText());
    check(
        runtime,
        "JSON_MODE",
        json,
        tree.isObject()
            && tree.size() == 1
            && tree.path("answer").isIntegralNumber()
            && tree.path("answer").intValue() == 42);

    var image = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, 0xFF0000);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var media =
        new org.springframework.ai.content.Media(
            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
            new org.springframework.core.io.ByteArrayResource(bytes.toByteArray()));
    var vision =
        runtime
            .metered()
            .call(
                new Prompt(
                    UserMessage.builder()
                        .text("Name the solid image color. Answer exactly RED, GREEN or BLUE.")
                        .media(media)
                        .build(),
                    options));
    check(
        runtime,
        "IMAGE_INPUT",
        vision,
        "RED".equals(vision.getResult().getOutput().getText().trim()));

    var summary =
        runtime
            .metered()
            .call(
                new Prompt(
                    "Compute the unique integer from 0 to 7428 that is 5 modulo 17, 7 modulo 19, and 11 modulo 23. Answer only the integer.",
                    options.mutate().reasoningEffort("medium").reasoningSummary("auto").build()));
    check(
        runtime,
        "REASONING_SUMMARY_REQUEST",
        summary,
        "7417".equals(summary.getResult().getOutput().getText().trim()));
    outputLimit(runtime, options);
  }

  static void outputLimit(ResponsesAcceptanceRuntime runtime, OpenAiResponsesChatOptions options)
      throws Exception {
    try {
      var limited =
          runtime
              .metered()
              .call(
                  new Prompt(
                      "List the integers from 1 through 2000, one per line, with no omissions.",
                      options.mutate().maxOutputTokens(32).build()));
      boolean expected = "LENGTH".equals(limited.getResult().getMetadata().getFinishReason());
      runtime.report(
          "OUTPUT_LIMIT",
          Map.of(
              "success",
              expected,
              "finish_reason",
              limited.getResult().getMetadata().getFinishReason(),
              "usage",
              dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(limited),
              "budget",
              runtime.budget.snapshot()));
      if (!expected) throw new IllegalStateException("Output exhaustion not observed");
    } catch (ResponsesTransportException failure) {
      boolean expected = failure.diagnosticCode().equals("output_limit");
      runtime.report(
          "OUTPUT_LIMIT",
          Map.of(
              "success",
              expected,
              "diagnostic",
              failure.diagnosticCode(),
              "usage_available",
              failure.usage() != null,
              "reservation_retained",
              true,
              "budget",
              runtime.budget.snapshot()));
      if (!expected) throw failure;
    }
  }

  private static void check(
      ResponsesAcceptanceRuntime runtime, String name, ChatResponse response, boolean correct)
      throws Exception {
    var usage = dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response);
    var data = new java.util.LinkedHashMap<String, Object>();
    data.put("success", correct);
    data.put("usage", usage);
    data.put("budget", runtime.budget.snapshot());
    runtime.report(name, data);
    if (!correct) throw new IllegalStateException("Feature acceptance criterion failed: " + name);
  }
}
