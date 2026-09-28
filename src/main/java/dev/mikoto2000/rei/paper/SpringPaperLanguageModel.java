package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.List;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class SpringPaperLanguageModel implements PaperLanguageModel {
  private final LlmModelProvider provider;
  private final PaperProperties config;

  public SpringPaperLanguageModel(LlmModelProvider provider, PaperProperties config) {
    this.provider = provider;
    this.config = config;
  }

  public String model() {
    var model = provider.subAgentChatModel();
    String name =
        provider.model(
            "chat",
            model.getDefaultOptions() == null ? null : model.getDefaultOptions().getModel());
    return name == null ? "configured-default" : name;
  }

  public String generate(String system, String input, PaperOperation op) {
    op.check();
    var options = provider.chatOptions("chat", model());
    options.setInternalToolExecutionEnabled(false);
    options.setToolCallbacks(List.of());
    var prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(input)), options);
    // Streaming allows disposal to reach the HTTP subscription when the owning run is cancelled.
    var future =
        provider.subAgentChatModel().stream(prompt)
            .map(
                r ->
                    r.getResult() == null || r.getResult().getOutput() == null
                        ? ""
                        : java.util.Objects.toString(r.getResult().getOutput().getText(), ""))
            .scan(
                "",
                (a, b) -> {
                  op.check();
                  if (a.length() + b.length() > 64000)
                    throw new PaperException(PaperException.Code.SUMMARY_FAILED, "LLM 出力上限");
                  return a + b;
                })
            .last("")
            .toFuture();
    return op.await(future, config.getLlmTimeout());
  }
}
