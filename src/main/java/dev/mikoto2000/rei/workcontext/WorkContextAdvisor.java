package dev.mikoto2000.rei.workcontext;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.chat.*;

@Component
public class WorkContextAdvisor implements BaseAdvisor {
  private final WorkContextService service;
  private final WorkContextProperties properties;
  private final WorkContextGit git;
  public WorkContextAdvisor(WorkContextService service,WorkContextProperties properties,WorkContextGit git) {this.service=service;this.properties=properties;this.git=git;}
  @Override public ChatClientRequest before(ChatClientRequest request,AdvisorChain chain) {
    var owner=request.context().get(AgentRunContext.class.getName());
    if(!(owner instanceof AgentRunContext run)||run.projectId()==null) return request;
    try {
      var saved=service.current(run.projectId()).orElse(null);
      if(saved==null) return request;
      String text=new WorkContextFormatter().context(saved,git.capture(run.projectRoot(),Instant.now()),properties.maxContextTokens());
      if(text.isBlank()) return request;
      var messages=new ArrayList<Message>(request.prompt().getInstructions());
      messages.add(SystemMessage.builder().text(text).metadata(Map.of("rei.workContext",true)).build());
      return request.mutate().prompt(new Prompt(messages,request.prompt().getOptions())).build();
    } catch(RuntimeException error) {
      RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Work Context unavailable; continuing chat ({})",error.getClass().getSimpleName());return request;
    }
  }
  @Override public ChatClientResponse after(ChatClientResponse response,AdvisorChain chain) {return response;}
  @Override public int getOrder() {return -109;}
}
