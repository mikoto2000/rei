package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.subagent.*;
import reactor.core.publisher.Flux;

class DelegationPreflightFailureTest {
  @Test void argumentRepairFollowedByForbiddenModelReportsPolicyFailureWithoutAnotherRepair() {
    var owner=new AgentRunContext("run","session",Path.of("."),"project");
    var approvals=mock(ToolApprovalRepository.class);
    var automatic=new PlanDelegationProperties(true,Set.of("default"),Set.of("allowed-model"));
    var registry=mock(SubAgentRegistry.class);
    when(registry.findById("reviewer")).thenReturn(Optional.of(new SubAgentDefinition(
        "reviewer","reviewer","review","review",List.of(),"local-model",4,Duration.ofSeconds(30),Path.of("reviewer.yaml"))));
    var beans=new StaticListableBeanFactory();beans.addBean("registry",registry);beans.addBean("tools",new SubAgentToolPolicy(Set.of()));
    var resolver=new DelegationToolPermissionResolver(beans.getBeanProvider(SubAgentRegistry.class),beans.getBeanProvider(SubAgentToolPolicy.class));
    resolver.setAutomatic(automatic);
    var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of()));policy.setDelegationResolver(resolver);
    var recorded=new CopyOnWriteArrayList<AgentEvent>();var refusalBoundaries=new AtomicInteger();
    var publisher=new AgentEventPublisher(){
      public void publish(AgentEvent event){recorded.add(event);}
      public void publishBoundary(AgentEvent event){
        if(event.payload() instanceof ToolFailedPayload failed && "chatgpt_permission_denied".equals(failed.error().code()))refusalBoundaries.incrementAndGet();
        publish(event);
      }
    };
    var factory=new AgentEventFactory(Clock.systemUTC());
    var guard=new ToolPermissionGuard(policy,factory,publisher);guard.setApprovals(approvals);
    var budget=new OutputLimitRunBudget(2,20);
    var context=new RunExecutionContext("run",budget,new ProgressEvaluator(Path.of("."),null),factory,publisher);
    context.setRunContext(owner);context.setToolPermissionGuard(guard);
    var effects=new AtomicInteger();
    var tool=new ToolCallback(){
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("delegateTask").description("delegate").inputSchema("{}").build();}
      public String call(String input){effects.incrementAndGet();throw new AssertionError("A forbidden model must never be executed or substituted");}
    };
    String corrected="{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\"default\",\"modelId\":\"private-forbidden-model\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
    String malformed=corrected.substring(0,corrected.length()-1)+",\"context\":\"private-context\"}";
    var calls=new AtomicInteger();
    var model=new ChatModel(){
      public ChatResponse call(Prompt prompt){throw new AssertionError();}
      public Flux<ChatResponse> stream(Prompt prompt){
        int number=calls.incrementAndGet();
        if(number==2) {
          var feedback=prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
              .flatMap(message->message.getResponses().stream()).findFirst().orElseThrow().responseData();
          assertTrue(feedback.contains("INVALID_TOOL_ARGUMENTS"));assertTrue(feedback.contains("request.context"));assertFalse(feedback.contains("private-context"));
        }
        assertTrue(number<=2,"A policy refusal must not trigger a third model request");
        return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
            new AssistantMessage.ToolCall(number==1?"malformed":"corrected","function","delegateTask",number==1?malformed:corrected))).build()))));
      }
    };
    var prompt=new Prompt(List.of(new UserMessage("ChatGPT にレビューして")),ToolCallingChatOptions.builder()
        .toolCallbacks(List.of(tool)).toolContext(Map.of(RunExecutionContext.KEY,context)).build());
    var error=assertThrows(ChatGptException.class,()->new StagnationChatModel(model).call(prompt));
    assertEquals(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED,error.permissionReason());
    assertEquals(2,calls.get());assertEquals(1,budget.snapshot().argumentCorrections());assertEquals(0,effects.get());verifyNoInteractions(approvals);
    assertEquals(Set.of("allowed-model"),automatic.allowedModels());
    var failures=recorded.stream().filter(event->event.payload() instanceof ToolFailedPayload).toList();
    assertEquals(2,failures.size());assertEquals(1,refusalBoundaries.get());
    var refused=failures.stream().filter(event->((ToolFailedPayload)event.payload()).error().code().equals("chatgpt_permission_denied")).findFirst().orElseThrow();
    var payload=(ToolFailedPayload)refused.payload();
    assertEquals("corrected",payload.toolCallId());assertEquals("delegateTask",payload.toolName());
    assertEquals("run",refused.runId());assertEquals("project",refused.projectId());assertEquals("session",refused.sessionId());
    assertTrue(payload.error().message().contains("MODEL_NOT_ALLOWED"));assertTrue(payload.error().message().contains("allowed-models"));
    assertFalse(payload.error().message().contains("private-forbidden-model"));assertFalse(payload.error().message().contains("private-context"));
    assertTrue(recorded.stream().noneMatch(event->event.type()==AgentEventType.TOOL_STARTED));
  }
}
