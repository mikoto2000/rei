package dev.mikoto2000.rei.computeruse;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** 画面操作利用をモデルから呼び出せるツールとして提供する。 */
public final class ComputerUseTools {
  /** サービス。 */
  private final ComputerUseService service;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** 生成元。 */
  private final AgentEventFactory factory;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 指定された値や利用先を使って、ComputerUseToolsを初期化する。 */
  public ComputerUseTools(
      ComputerUseService service,
      CommandCancellationService cancellation,
      AgentEventFactory factory,
      AgentEventPublisher publisher) {
    this.service = service;
    this.cancellation = cancellation;
    this.factory = factory;
    this.publisher = publisher;
  }

  /** 画面操作の支援で用いるorchestrationプロンプトを返す。 */
  public String orchestrationPrompt() {
    try {
      return new org.springframework.core.io.ClassPathResource("computer-use/orchestration.md")
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Missing desktop orchestration instructions", error);
    }
  }

  /** 画面操作の支援で用いる画面操作利用を返す。 */
  @Tool(
      description =
          "Visual interaction across attached Windows displays. First use existing Shell/file/API tools for programmable preparation such as opening URLs or launching applications. Pass the remaining goal and completed preparation here; observe a fresh screenshot and perform one action at a time. Returns DONE only when visually verified; safety-blocked actions require human approval. Do not retry blocked or failed workflows blindly.")
  public ComputerUseResult computerUse(String goal) {
    var owner = AgentRunScope.current();
    Thread worker = Thread.currentThread();
    var registration =
        cancellation.onCancel(owner == null ? null : owner.runId(), worker::interrupt);
    try {
      return service.run(goal);
    } finally {
      registration.dispose();
    }
  }

  /** 画面操作の支援で用いる完了時の処理を返す。 */
  public ToolCallback callback() {
    var delegate =
        MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks()[0];
    return new ToolEventCallbackDecorator(delegate, factory, publisher);
  }
}
