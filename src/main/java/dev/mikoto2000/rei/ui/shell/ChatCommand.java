package dev.mikoto2000.rei.ui.shell;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
name = "chat",
description = "AI と会話します")
@Component
public class ChatCommand implements Runnable {

  private final ChatExecutionService chatExecutionService;
  private final ChatResponseNarrator chatResponseNarrator;
  private dev.mikoto2000.rei.application.session.ShellConversationService conversations;

  @Autowired
  public void setShellConversations(dev.mikoto2000.rei.application.session.ShellConversationService conversations) { this.conversations = conversations; }
  public boolean acceptsAsynchronously() { return conversations != null; }

  @Parameters(arity = "1..*", paramLabel = "PROMPT", description = "メッセージ")
  private String[] prompts;
  @picocli.CommandLine.Option(names="--mode",defaultValue="EXCLUSIVE",description="EXCLUSIVE / CONVERSATION / READ_ONLY")
  private dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode;
  @picocli.CommandLine.Option(names="--run",description="指定Runへ追加指示を送る（独立Runは作成しない）")
  private String targetRun;

  public ChatCommand(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService, ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService) {
    this(new ChatExecutionService(chatClient, currentModelHolder, cancellationService, memoryConsolidatorService),
        chatResponseNarrator);
  }

  public ChatCommand(ChatClient chatClient, ModelHolderService currentModelHolder,
      CommandCancellationService cancellationService, ChatResponseNarrator chatResponseNarrator,
      Optional<MemoryConsolidatorService> memoryConsolidatorService, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(new ChatExecutionService(chatClient, currentModelHolder, cancellationService, memoryConsolidatorService,
        eventFactory, eventPublisher), chatResponseNarrator);
  }

  public ChatCommand(LlmChatClientProvider chatClientProvider, ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider, LlmProperties llmProperties, CommandCancellationService cancellationService,
      ChatResponseNarrator chatResponseNarrator, Optional<MemoryConsolidatorService> memoryConsolidatorService,
      Optional<OutputLimitReplanner> outputLimitReplanner) {
    this(new ChatExecutionService(chatClientProvider, currentModelHolder, modelProvider, llmProperties,
        cancellationService, memoryConsolidatorService, outputLimitReplanner), chatResponseNarrator);
  }

  @Autowired
  public ChatCommand(ChatExecutionService chatExecutionService, ChatResponseNarrator chatResponseNarrator) {
    this.chatExecutionService = chatExecutionService;
    this.chatResponseNarrator = chatResponseNarrator;
  }

  @Override
  public void run() {
    if (conversations != null) {
      if(targetRun!=null) {
        if(mode!=dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE || !conversations.intervene(targetRun,String.join(" ",prompts)))
          throw new IllegalArgumentException("Target Run is unavailable for guidance");
      } else if(mode==null || mode==dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)conversations.submit(String.join(" ",prompts));
      else conversations.submit(String.join(" ", prompts),mode);
      return;
    }
    if(targetRun!=null || mode!=null && mode!=dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Concurrent conversation router is unavailable");
    chatResponseNarrator.reset();
    ChatExecutionResult result = chatExecutionService.execute(String.join(" ", prompts));
    if (result.success()) {
      chatResponseNarrator.narrateIfCompleted(result.text());
    }
  }
}
