package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import dev.mikoto2000.rei.slack.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;

@Tag("integration")
class SlackAgentChatIntegrationTest {
  @TempDir Path dir;
  @Test void actualChatToolLoopWaitsWithoutModelCallsThenIntegratesReplyInSameRun() {
    var clock=Clock.systemUTC();var owner=new AgentRunContext("parent","session",dir,"project");
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("storage.db"));
    var settings=new SlackAgentProperties();settings.setEnabled(true);settings.setAllowedWorkspaceIds(Set.of("T1"));settings.setAllowedChannelIds(Set.of("C1"));
    settings.setAgents(Map.of("tenten",new SlackAgentProperties.Agent("T1","U2","B2","C1","U2","FINAL:")));
    var repository=new SlackAgentRepository(source,clock);var saved=new SlackAgentContinuationStore(source);
    var registry=new RunRegistry(clock);registry.register(owner);registry.transition(owner.runId(),RunStatus.RUNNING,null);
    var router=new ConversationInputRouter(Runnable::run,(o,p,q)->{});var bus=new InMemoryAgentEventBus();
    var runs=new RunService(registry,bus,new AgentEventFactory(clock),new CommandCancellationService(),router::cancelQueued);
    var service=new SlackAgentService(settings,repository,saved,registry,runs,router,clock);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(false,null,null,null)),new AgentEventFactory(clock),bus);
    var tools=new SlackAgentTools(settings,repository,guard);var modelCalls=new AtomicInteger();
    service.attach("U1","T1",row->SlackTransport.Delivery.sent("100.1"));tools.onRegistered(service::registered);
    var interventions=new UserInterventionQueue();
    ChatModel model=new ChatModel(){
      public ToolCallingChatOptions getOptions(){return ToolCallingChatOptions.builder().build();}
      public ChatResponse call(Prompt prompt){throw new UnsupportedOperationException();}
      public Flux<ChatResponse> stream(Prompt prompt){
        if(modelCalls.incrementAndGet()==1){assertTrue(interventions.offer("Accepted guidance before suspension"));return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("external-call","function","askExternalAgent","{\"agent\":\"tenten\",\"question\":\"short question\",\"timeoutSeconds\":30}"))).build()))));}
        var response=prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        assertTrue(response.getResponses().getFirst().responseData().contains("FINAL: verified reply"));
        assertTrue(response.getResponses().getFirst().responseData().contains("UNTRUSTED DATA"));
        assertTrue(prompt.getInstructions().stream().filter(SystemMessage.class::isInstance).noneMatch(m->m.getText().contains("verified reply")));
        assertTrue(prompt.getInstructions().stream().filter(UserMessage.class::isInstance).anyMatch(m->m.getText().contains("Accepted guidance before suspension")),"Accepted intervention was lost by waiting");
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Integrated verified reply with attribution")))));
      }
    };
    var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");var llm=new LlmProperties();llm.getOutputLimit().setMaxLlmCallsPerRun(2);
    var client=ChatClient.builder(new StagnationChatModel(model)).defaultAdvisors(new RunAwareToolCallingAdvisor()).defaultTools(java.util.Arrays.asList(tools.callbacks())).build();
    var chat=new ChatExecutionService(new FixedLlmChatClientProvider(client),holder,new FixedLlmModelProvider(),llm,new CommandCancellationService(),Optional.empty(),Optional.empty());
    var beans=new StaticListableBeanFactory(Map.of("continuation",service));chat.setContinuations(beans.getBeanProvider(RunContinuation.class));
    service.onResume((o,s)->chat.execute(o,s.request(),new UserInterventionQueue()));
    var first=chat.execute(owner,"Ask tenten, then summarize",interventions);
    assertEquals(ChatExecutionResult.Status.WAITING,first.status());assertEquals(1,modelCalls.get());assertFalse(router.containsRun("project","parent"));
    var q=repository.forRun("parent").getFirst();assertEquals(1,saved.document("parent").budget().llmCalls());
    service.attach("U1","T1",row->SlackTransport.Delivery.sent("100.1"));service.pump();service.pump();assertEquals(1,modelCalls.get());
    assertTrue(service.receive(new SlackMessage("final","T1","C1","101.1","100.1","U2","B2","bot_message","message","channel","FINAL: verified reply")));
    service.pump();service.pump();assertEquals(2,modelCalls.get());assertEquals("COMPLETED",saved.state("parent"));assertEquals(RunStatus.COMPLETED,registry.get("parent").status());assertEquals(Set.of("parent"),registry.runIds());assertEquals("COMPLETED",repository.get(q.id()).state());
  }
}
