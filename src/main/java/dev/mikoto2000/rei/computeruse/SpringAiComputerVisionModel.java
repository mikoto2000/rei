package dev.mikoto2000.rei.computeruse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.core.io.*;
import org.springframework.util.MimeTypeUtils;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;

/** Provider adapter only. No ChatClient, advisors, memory, or tool-calling loop is used. */
public final class SpringAiComputerVisionModel implements ComputerVisionModel {
  private final ChatModel model;
  private final Supplier<OpenAiChatOptions.Builder> options;
  private final BooleanSupplier cancelled;
  private final int repairs;
  private final ActionParser parser = new ActionParser();
  private final String system = resource("system.md");
  private final String schema = resource("action.schema.json");

  public SpringAiComputerVisionModel(ChatModel model, Supplier<OpenAiChatOptions.Builder> options,
      BooleanSupplier cancelled, int repairs) {
    if (repairs < 0 || repairs > 2) throw new IllegalArgumentException("Invalid repair budget");
    ToolLoopSupport.requireNoDefaultTools(model);
    this.model = model; this.options = options; this.cancelled = cancelled; this.repairs = repairs;
  }

  public ComputerAction decide(ComputerObservation observation) throws Exception {
    var screen = observation.screenshot();
    var media = new ArrayList<Media>();
    var displayInfo = new StringBuilder();
    int imageIndex = 0;
    for (var display : screen.displays()) {
      var bytes = new ByteArrayOutputStream();
      if (!ImageIO.write(display.image(), "png", bytes)) throw new IllegalStateException("PNG encoder unavailable");
      media.add(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes.toByteArray())));
      displayInfo.append("\nImage ").append(++imageIndex).append(": displayId=").append(display.geometry().id())
          .append("; ").append(display.image().getWidth()).append("x").append(display.image().getHeight())
          .append(" pixels; primary=").append(display.geometry().primary());
    }
    var format = new ResponseFormat();
    format.setType(ResponseFormat.Type.JSON_SCHEMA);
    format.setJsonSchema(ResponseFormat.JsonSchema.builder().name("computer_action").strict(true).schema(schema).build());
    String context = "Goal:\n" + observation.goal() + "\nRecent dispatch history:\n"
        + String.join("\n", observation.recentHistory()) + "\nStep: " + observation.step() + "/" + observation.maxSteps()
        + "\nScreenshots in attachment order (coordinates are local to each image):" + displayInfo;
    String validationReason = "";
    for (int attempt = 0; attempt <= repairs; attempt++) {
      checkCancelled();
      // No-tools inference must omit the wire fields: compatible APIs can reject tools: [].
      var requestOptions = options.get().responseFormat(format).toolChoice(null)
          .tools(null).toolCallbacks(List.of()).toolNames(Set.of()).internalToolExecutionEnabled(false).build();
      var prompt = new Prompt(List.of(new SystemMessage(system + "\nJSON schema:\n" + schema), UserMessage.builder().text(context
          + (attempt == 0 ? "" : "\nPrevious response was invalid: " + validationReason
              + ". Correct this validation error; return exactly one decision matching the schema."))
          .media(media).build()), requestOptions);
      var response = model.call(prompt);
      checkCancelled();
      if (dev.mikoto2000.rei.llm.OutputLimitDetector.isOutputLimitReached(response))
        throw new InvalidComputerDecision("Model reached output token limit (finish_reason=length); check computer-use model output/reasoning budget");
      try {
        if (response == null || response.getResults().size() != 1 || response.hasToolCalls())
          throw new InvalidComputerDecision("Expected one non-tool decision");
        return parser.parse(response.getResult().getOutput().getText(), screen);
      } catch (InvalidComputerDecision invalid) {
        validationReason = invalid.getMessage();
        if (attempt == repairs) throw invalid;
      }
    }
    throw new IllegalStateException("Repair budget exhausted");
  }

  private void checkCancelled() {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
  }
  private static String resource(String name) {
    try { return new ClassPathResource("computer-use/" + name).getContentAsString(StandardCharsets.UTF_8); }
    catch (Exception error) { throw new IllegalStateException("Missing computer use resource: " + name, error); }
  }
}
