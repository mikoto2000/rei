package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.chat.AgentRunContext.Mode;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitReplanner;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** AI と会話しますを行うための利用者コマンド。 */
@Command(name = "chat", description = "AI と会話します")
@Component
public class ChatCommand implements Runnable {

  /** チャット実行サービス。 */
  private final Function<String, ChatExecutionResult> executeChat;

  /** チャット応答読み上げの案内。 */
  private final ChatResponseNarrator chatResponseNarrator;

  /** 会話一覧。 */
  private BiConsumer<String, Mode> submitConversation;

  private BiPredicate<String, String> intervene;

  /** ターミナルでの対話で使用するシェル会話一覧を設定する。 */
  @Autowired
  public void setShellConversations(
      dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.intervene =
        conversations == null ? null : (run, text) -> conversations.intervene(run, text);
    this.submitConversation =
        conversations == null
            ? null
            : (text, requestedMode) -> {
              if (requestedMode == null || requestedMode == Mode.EXCLUSIVE)
                conversations.submit(text);
              else conversations.submit(text, requestedMode);
            };
  }

  /** ターミナルでの対話で判断に用いるacceptsAsynchronouslyの条件を確認する。 */
  public boolean acceptsAsynchronously() {
    return submitConversation != null;
  }

  /** プロンプト一覧。 */
  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;

  /** 動作モード。 */
  @picocli.CommandLine.Option(
      names = "--mode",
      defaultValue = "EXCLUSIVE",
      description = "実行モード（EXCLUSIVE / CONVERSATION / READ_ONLY）")
  private Mode mode;

  /** 対象実行。 */
  @picocli.CommandLine.Option(names = "--run", description = "指定Runへ追加指示を送る（独立Runは作成しない）")
  private String targetRun;

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatCommand(
      ChatClient chatClient,
      ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService) {
    this(
        new ChatExecutionService(
            chatClient, currentModelHolder, cancellationService, memoryConsolidatorService),
        chatResponseNarrator);
  }

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatCommand(
      ChatClient chatClient,
      ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(
        new ChatExecutionService(
            chatClient,
            currentModelHolder,
            cancellationService,
            memoryConsolidatorService,
            eventFactory,
            eventPublisher),
        chatResponseNarrator);
  }

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatCommand(
      LlmChatClientProvider chatClientProvider,
      ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider,
      LlmProperties llmProperties,
      CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner) {
    this(
        new ChatExecutionService(
            chatClientProvider,
            currentModelHolder,
            modelProvider,
            llmProperties,
            cancellationService,
            memoryConsolidatorService,
            outputLimitReplanner),
        chatResponseNarrator);
  }

  /** 指定された値や利用先を使って、ChatCommandを初期化する。 */
  @Autowired
  public ChatCommand(
      ChatExecutionService chatExecutionService, ChatResponseNarrator chatResponseNarrator) {
    this.executeChat = text -> chatExecutionService.execute(text);
    this.chatResponseNarrator = chatResponseNarrator;
  }

  /** AI と会話しますを行う。 */
  @Override
  public void run() {
    if (submitConversation != null) {
      if (targetRun != null) {
        if (mode != Mode.EXCLUSIVE || !intervene.test(targetRun, String.join(" ", prompts)))
          throw new IllegalArgumentException("Target Run is unavailable for guidance");
      } else submitConversation.accept(String.join(" ", prompts), mode);
      return;
    }
    if (targetRun != null || mode != null && mode != Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Concurrent conversation router is unavailable");
    chatResponseNarrator.reset();
    ChatExecutionResult result = executeChat.apply(String.join(" ", prompts));
    if (result.success()) {
      chatResponseNarrator.narrateIfCompleted(result.text());
    }
  }
}
