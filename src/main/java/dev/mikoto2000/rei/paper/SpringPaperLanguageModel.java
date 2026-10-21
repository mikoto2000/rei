package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 論文の調査と整理に必要なSpring論文文章を生成するモデルを扱う。 */
@Component
public class SpringPaperLanguageModel implements PaperLanguageModel {
  /** 提供元。 */
  private final LlmModelProvider provider;

  /** 設定。 */
  private final PaperProperties config;

  /** 指定された値や利用先を使って、SpringPaperLanguageModelを初期化する。 */
  public SpringPaperLanguageModel(LlmModelProvider provider, PaperProperties config) {
    this.provider = provider;
    this.config = config;
  }

  /** 論文の調査と整理で用いるモデルを返す。 */
  public String model() {
    var model = provider.subAgentChatModel();
    String name =
        provider.model("chat", model.getOptions() == null ? null : model.getOptions().getModel());
    return name == null ? "configured-default" : name;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  public String generate(String system, String input, PaperOperation op) {
    op.check();
    var options =
        provider
            .chatOptions("chat", model())
            .mutate()
            .toolCallbacks(List.of())
            .toolChoice("none")
            .build();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
    var chatModel = provider.subAgentChatModel();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(chatModel);
    var prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(input)), options);
    // Streaming allows disposal to reach the HTTP subscription when the owning run is cancelled.
    var future =
        chatModel.stream(prompt)
            .map(
                r ->
                    r.getResult() == null || r.getResult().getOutput() == null
                        ? ""
                        : java.util.Objects.toString(r.getResult().getOutput().getText(), ""))
            .scan(
                "",
                (a, b) -> {
                  op.check();
                  // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
                  if (a.length() + b.length() > 64000)
                    throw new PaperException(PaperException.Code.SUMMARY_FAILED, "LLM 出力上限");
                  return a + b;
                })
            .last("")
            .toFuture();
    return op.await(future, config.getLlmTimeout());
  }
}
