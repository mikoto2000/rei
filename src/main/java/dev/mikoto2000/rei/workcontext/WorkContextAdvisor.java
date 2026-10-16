package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class WorkContextAdvisor implements BaseAdvisor {
  private final WorkContextService service;
  private final WorkContextProperties properties;
  private final WorkContextGit git;

  public WorkContextAdvisor(
      WorkContextService service, WorkContextProperties properties, WorkContextGit git) {
    this.service = service;
    this.properties = properties;
    this.git = git;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var owner = request.context().get(AgentRunContext.class.getName());
    if (!(owner instanceof AgentRunContext run) || run.projectId() == null) return request;
    try {
      var saved = service.current(run.projectId()).orElse(null);
      if (saved == null) return request;
      String text =
          new WorkContextFormatter()
              .context(
                  saved,
                  git.capture(run.projectRoot(), Instant.now()),
                  properties.maxContextTokens());
      if (text.isBlank()) return request;
      var messages = new ArrayList<Message>(request.prompt().getInstructions());
      messages.add(
          SystemMessage.builder().text(text).metadata(Map.of("rei.workContext", true)).build());
      return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
    } catch (RuntimeException error) {
      RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Work Context unavailable; continuing chat: {} /work show で保存状態を確認してください。",
              dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error).display());
      return request;
    }
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return -109;
  }
}
