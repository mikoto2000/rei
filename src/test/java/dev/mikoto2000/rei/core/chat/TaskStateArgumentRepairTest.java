package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskStateArgumentRepairTest {
  final AgentRunContext owner=new AgentRunContext("run","session",Path.of("."),"project");
  final ToolApprovalRepository approvals=mock(ToolApprovalRepository.class);
  final String invalid="{\"status\":\"IN_PROGRESS\",\"pending\":\"review,verify\"}";
  final String valid="{\"status\":\"IN_PROGRESS\",\"pending\":[\"review\",\"verify\"]}";
  final AtomicInteger effects=new AtomicInteger();
  RunExecutionContext context(OutputLimitRunBudget budget,boolean permissions) {
    var context=new RunExecutionContext("run",budget,new ProgressEvaluator(Path.of("."),null),new AgentEventFactory(Clock.systemUTC()),event->{});context.setRunContext(owner);
    if(permissions) {
      var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),new AgentEventFactory(Clock.systemUTC()),event->{});
      guard.setApprovals(approvals);context.setToolPermissionGuard(guard);
      when(approvals.request(eq("updateTaskState"),anyString(),eq(owner))).thenReturn(new ToolApprovalRepository.Request("approval","project","session","run","updateTaskState","preview","PENDING",Instant.now().plusSeconds(60)));
    }
    return context;
  }
  ToolCallback callback(String name,java.util.function.Function<String,String> effect) {return new ToolCallback(){
    public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();}
    public String call(String input){return effect.apply(input);}
  };}
  ToolCallback update(){return callback("updateTaskState",input->{effects.incrementAndGet();return "updated";});}
  Prompt prompt(RunExecutionContext context,ToolCallback...callbacks) {return new Prompt(List.of(new UserMessage("review")),ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).toolContext(Map.of(RunExecutionContext.KEY,context)).build());}
  AssistantMessage.ToolCall toolCall(String id,String name,String input){return new AssistantMessage.ToolCall(id,"function",name,input);}
  ChatResponse call(String id,String input){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(toolCall(id,"updateTaskState",input))).build())));}
  @Test void modelReceivesArrayGuidanceAndCorrectedCallRequiresNormalApproval() {
    var context=context(new OutputLimitRunBudget(2,20),true);var modelCalls=new AtomicInteger();
    var model=new ChatModel(){public ChatResponse call(Prompt prompt){throw new AssertionError();}public Flux<ChatResponse> stream(Prompt prompt){
      if(modelCalls.incrementAndGet()==1)return Flux.just(TaskStateArgumentRepairTest.this.call("invalid",invalid));
      var feedback=prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast).flatMap(message->message.getResponses().stream()).findFirst().orElseThrow();
      assertTrue(feedback.responseData().contains("INVALID_TOOL_ARGUMENTS"));assertTrue(feedback.responseData().contains("pending: must be a JSON array"));
      assertEquals(0,effects.get());verifyNoInteractions(approvals);
      return Flux.just(TaskStateArgumentRepairTest.this.call("corrected",valid));
    }};
    assertThrows(ToolPermissionException.class,()->new StagnationChatModel(model).call(prompt(context,update())));
    assertEquals(2,modelCalls.get());assertEquals(0,effects.get());verify(approvals).request("updateTaskState",valid,owner);
  }
  @Test void validationAndDurableRepairLimitWorkWithoutPermissionGuard() {
    var budget=new OutputLimitRunBudget(2,20,null,0,2);var context=context(budget,false);var loop=new ToolLoopSupport();
    for(int i=0;i<2;i++)loop.executeResumable(prompt(context,update()),call("invalid-"+i,invalid),context);
    var restored=new OutputLimitRunBudget(2,20,null,0,2);restored.restore(budget.snapshot());var next=context(restored,false);
    var stopped=assertThrows(ExecutionStoppedException.class,()->loop.executeResumable(prompt(next,update()),call("third",invalid),next));
    assertEquals(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED,stopped.reason());assertEquals(0,effects.get());verifyNoInteractions(approvals);
  }
  @Test void successfulSiblingIsPreservedAndNotRepeatedForARepairedCall() {
    var context=context(new OutputLimitRunBudget(2,20),false);var siblings=new AtomicInteger();
    var sibling=callback("readMultiFile",input->{siblings.incrementAndGet();return "read";});var update=update();
    var calls=List.of(toolCall("read","readMultiFile","{}"),toolCall("bad","updateTaskState",invalid));
    var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())));
    var result=new ToolLoopSupport().executeResumable(prompt(context,sibling,update),response,context);
    assertEquals(1,siblings.get());assertEquals(0,effects.get());assertFalse(result.returnDirect());
    var feedback=(ToolResponseMessage)result.conversationHistory().getLast();assertEquals(2,feedback.getResponses().size());assertEquals("read",feedback.getResponses().getFirst().responseData());
    new ToolLoopSupport().executeResumable(prompt(context,sibling,update),call("fixed",valid),context);
    assertEquals(1,siblings.get());assertEquals(1,effects.get());
  }
  @Test void postExecutionArgumentExceptionIsNeverAutomaticallyRepaired() {
    var context=context(new OutputLimitRunBudget(2,20),false);var failure=new IllegalArgumentException("execution failed after effect");
    var update=callback("updateTaskState",input->{effects.incrementAndGet();throw failure;});
    assertSame(failure,assertThrows(IllegalArgumentException.class,()->new ToolLoopSupport().executeResumable(prompt(context,update),call("valid",valid),context)));
    assertEquals(1,effects.get());assertEquals(0,context.budgetSnapshot().argumentCorrections());
  }
  @Test void validCallWithPolicyDenialIsNotAnArgumentRepair() {
    var context=context(new OutputLimitRunBudget(2,20),false);
    context.setToolPermissionGuard(new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(ActionCapability.LOCAL_WRITE),Map.of())),new AgentEventFactory(Clock.systemUTC()),event->{}));
    var modelCalls=new AtomicInteger();var model=new ChatModel(){public ChatResponse call(Prompt prompt){throw new AssertionError();}public Flux<ChatResponse> stream(Prompt prompt){modelCalls.incrementAndGet();return Flux.just(TaskStateArgumentRepairTest.this.call("denied",valid));}};
    assertThrows(ToolPermissionException.class,()->new StagnationChatModel(model).call(prompt(context,update())));
    assertEquals(1,modelCalls.get());assertEquals(0,effects.get());assertEquals(0,context.budgetSnapshot().argumentCorrections());
  }
}
