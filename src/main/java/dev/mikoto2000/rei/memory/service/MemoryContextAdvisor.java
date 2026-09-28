package dev.mikoto2000.rei.memory.service;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.chat.*;

/** Supplemental system message after history assembly; never written to history or rolling summaries. */
@Component
public class MemoryContextAdvisor implements BaseAdvisor {
  private final MemoryRetriever retriever;
  public MemoryContextAdvisor(MemoryRetriever retriever) { this.retriever=retriever; }
  @Override public ChatClientRequest before(ChatClientRequest request,AdvisorChain chain) {
    var owner=request.context().get(AgentRunContext.class.getName());
    if(!(owner instanceof AgentRunContext run)) return request;
    var users=request.prompt().getInstructions().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).toList();
    if(users.isEmpty()) return request;
    try {
      var result=retriever.retrieve(users.getLast().getText(),run.projectId());
      if(result.context().isBlank()) return request;
      var messages=new ArrayList<Message>(request.prompt().getInstructions());
      messages.add(SystemMessage.builder().text(result.context()).metadata(Map.of("rei.longTermMemory",true)).build());
      return request.mutate().prompt(new Prompt(messages,request.prompt().getOptions())).build();
    } catch(RuntimeException error) {
      RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Memory retrieval unavailable; continuing without memories ({})",error.getClass().getSimpleName());
      return request;
    }
  }
  @Override public ChatClientResponse after(ChatClientResponse response,AdvisorChain chain) { return response; }
  @Override public int getOrder() { return -110; }
}
