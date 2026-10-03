package dev.mikoto2000.rei.checkpoint;
import java.util.ArrayList;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

/** Historical data is injected separately from the user's request and remains within ContextAssembler's hard budget. */
@Component
public class ResumeContextAdvisor implements BaseAdvisor {
  private final PersistentCheckpointService service;
  public ResumeContextAdvisor(PersistentCheckpointService service){this.service=service;}
  public ChatClientRequest before(ChatClientRequest request,AdvisorChain chain) {
    var run=AgentRunScope.current();if(run==null)return request;
    String context=service.context(run.runId());if(context.isBlank())return request;
    var messages=new ArrayList<Message>(request.prompt().getInstructions());
    messages.addFirst(SystemMessage.builder().text(context).metadata(java.util.Map.of("rei.resumeContext",true)).build());
    return request.mutate().prompt(new Prompt(messages,request.prompt().getOptions())).build();
  }
  public ChatClientResponse after(ChatClientResponse response,AdvisorChain chain){return response;}
  public int getOrder(){return -66;}
}
