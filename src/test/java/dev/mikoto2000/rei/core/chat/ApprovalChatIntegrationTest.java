package dev.mikoto2000.rei.core.chat;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class ApprovalChatIntegrationTest {
  @TempDir Path directory;
  @Test void realChatReleasesItsWorkerThenContinuesInTheSameRunWithoutFailedNotificationOrNewArguments() {
    var clock=Clock.systemUTC();String project=UUID.randomUUID().toString();
    var owner=new AgentRunContext("run","session",directory,project);
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+directory.resolve("approval.db"));
    var approvals=new ToolApprovalRepository(source,clock);var store=new ApprovalContinuationStore(source);
    var registry=new RunRegistry(clock);registry.register(owner);registry.transition(owner.runId(),RunStatus.RUNNING,null);
    var bus=new InMemoryAgentEventBus();var events=new ArrayList<AgentEvent>();bus.subscribe(events::add);
    var router=new ConversationInputRouter(Runnable::run,(o,p,q)->{});var cancellation=new CommandCancellationService();
    var runs=new RunService(registry,bus,new AgentEventFactory(clock),cancellation,router::cancelQueued);
    var projects=mock(ProjectRegistry.class);when(projects.resolveById(project)).thenReturn(Optional.of(new ProjectContext(project,"test",directory)));
    var sessions=mock(SessionRepository.class);when(sessions.findById("session")).thenReturn(Optional.of(new SessionMetadata("session",project,"review",clock.instant(),clock.instant())));
    var beans=new StaticListableBeanFactory();beans.addBean("registry",registry);beans.addBean("runs",runs);beans.addBean("router",router);beans.addBean("projects",projects);beans.addBean("sessions",sessions);
    var continuation=new ApprovalContinuationService(approvals,store,beans.getBeanProvider(RunRegistry.class),beans.getBeanProvider(RunService.class),beans.getBeanProvider(ConversationInputRouter.class),beans.getBeanProvider(ChatExecutionService.class),beans.getBeanProvider(dev.mikoto2000.rei.slack.SlackAgentService.class),beans.getBeanProvider(ProjectRegistry.class),beans.getBeanProvider(SessionRepository.class),source,clock);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,null,Set.of(),null)),new AgentEventFactory(clock),bus);guard.setApprovals(approvals);
    var effects=new AtomicInteger();var calls=new AtomicInteger();String arguments="{\"request\":{\"command\":\"npm run textlint\",\"timeoutSeconds\":30}}";
    var tool=new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("runCommand").description("validate").inputSchema("{}").build();}public String call(String input){assertEquals(arguments,input);effects.incrementAndGet();return "validation passed";}};
    ChatModel model=new ChatModel(){public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions(){return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();}public ChatResponse call(Prompt p){throw new AssertionError();}public Flux<ChatResponse> stream(Prompt p){
      if(calls.incrementAndGet()==1)return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("check").toolCalls(List.of(new AssistantMessage.ToolCall("command","function","runCommand",arguments))).build()))));
      assertEquals(1,effects.get());assertTrue(p.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).anyMatch(m->m.getResponses().getFirst().responseData().equals("validation passed")));
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("review completed")))));
    }};
    var client=ChatClient.builder(new StagnationChatModel(model)).defaultAdvisors(new RunAwareToolCallingAdvisor()).defaultTools(List.of(tool)).build();
    var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");var settings=new LlmProperties();settings.getOutputLimit().setMaxLlmCallsPerRun(2);
    var chat=new ChatExecutionService(new FixedLlmChatClientProvider(client),holder,new FixedLlmModelProvider(),settings,cancellation,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),clock,Optional.empty(),new AgentEventFactory(clock),bus);
    beans.addBean("chat",chat);beans.addBean("continuation",continuation);chat.setContinuations(beans.getBeanProvider(RunContinuation.class));chat.setToolPermissionGuard(guard);
    var checkpoints=mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);chat.setCheckpoints(checkpoints);
    var first=chat.execute(owner,"review",new UserInterventionQueue());
    assertEquals(ChatExecutionResult.Status.WAITING,first.status());assertEquals(RunStatus.WAITING_APPROVAL,registry.get("run").status());
    assertEquals("tool_approval_waiting",first.stopCode());verify(checkpoints).finish(owner,"WAITING_APPROVAL");
    assertEquals(0,effects.get());assertEquals(1,calls.get());assertTrue(events.stream().noneMatch(e->e.type()==AgentEventType.AGENT_RUN_FAILED));
    var request=approvals.list(project).getFirst();continuation.decide(project,request.id(),true,true);
    continuation.pump();continuation.pump();
    assertEquals(1,effects.get());assertEquals(2,calls.get());assertEquals(RunStatus.COMPLETED,registry.get("run").status());
    assertEquals(Set.of("run"),registry.runIds());assertEquals("COMPLETED",continuation.state(request.id()).orElseThrow());
    verify(checkpoints).finish(owner,"COMPLETED");verify(checkpoints,never()).finish(owner,"FAILED");
    assertTrue(events.stream().noneMatch(e->e.type()==AgentEventType.AGENT_RUN_FAILED));
  }
}
