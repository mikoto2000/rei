package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.*;

@Tag("integration")
class GoalBudgetChatIntegrationTest {
  @TempDir Path dir;
  @Test void goalOnlyTokenLimitIsEnforcedAtActualChatBoundaryBeforeTools() {
    var toolCalls=new AtomicInteger();
    var model=mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","readFile","{}"))).build())),
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(new org.springframework.ai.chat.metadata.DefaultUsage(1,10)).build())));
    ToolCallback read=new ToolCallback() {
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build();}
      public String call(String input){toolCalls.incrementAndGet();return "read";}
    };
    var properties=new LlmProperties();properties.getOutputLimit().setMaxTotalTokensPerGoal(10);
    assertEquals(0,properties.getOutputLimit().getMaxTotalTokensPerRun());
    var goals=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC(),properties);
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
    var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");
    var chat=new ChatExecutionService(new FixedLlmChatClientProvider(ChatClient.builder(new StagnationChatModel(model))
        .defaultAdvisors(new RunAwareToolCallingAdvisor()).defaultToolCallbacks(read).build()),
        holder,new FixedLlmModelProvider(),properties,new CommandCancellationService(),Optional.empty(),Optional.empty());
    var loop=new GoalLoopService(goals,new FileGoalVerifier(),(claim,run,done)->done.accept(new GoalLoopService.Outcome(
        chat.execute(new AgentRunContext(run,"session",dir,"project"),"Artifact",new UserInterventionQueue(),goals.modelBudget(claim,run)))),
        new ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    var stopped=loop.run("project",goal.id());
    assertEquals("BLOCKED",stopped.status());assertEquals("token_budget_exhausted",stopped.reason());
    assertEquals(11,stopped.totalTokens());assertEquals(0,stopped.pendingLlmCalls());assertEquals(0,toolCalls.get());
    assertThrows(IllegalStateException.class,()->loop.run("project",goal.id()));
    verify(model,times(1)).stream(any(Prompt.class));
  }
  @Test void actualChatToolLoopReservesEveryCallAndStopsAtDurableGoalLimit() {
    var modelCalls=new AtomicInteger();
    ChatModel model=new ChatModel() {
      @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
      public ChatResponse call(Prompt prompt){throw new UnsupportedOperationException();}
      public Flux<ChatResponse> stream(Prompt prompt) {
        int call=modelCalls.incrementAndGet();
        return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-"+call,"function","readFile","{}"))).build()))));
      }
    };
    ToolCallback read=new ToolCallback() {
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build();}
      public String call(String input){return "unchanged";}
    };
    var holder=mock(ModelHolderService.class);when(holder.get()).thenReturn("test");
    var client=ChatClient.builder(new StagnationChatModel(model))
        .defaultAdvisors(new RunAwareToolCallingAdvisor()).defaultToolCallbacks(read).build();
    var chat=new ChatExecutionService(new FixedLlmChatClientProvider(client),holder,new FixedLlmModelProvider(),new LlmProperties(),
        new CommandCancellationService(),Optional.empty(),Optional.empty());
    var goals=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC());
    var goal=goals.create(new AgentRunContext("source","session",dir,"project"),"Produce artifact","out.txt","a".repeat(64),3,2);
    var loop=new GoalLoopService(goals,new FileGoalVerifier(),(claim,run,done)->{
      var reservation=new OutputLimitRunBudget.LlmCallReservation(){public boolean tryReserve(){return goals.reserveLlm(claim);}public int remaining(){return goals.remainingLlm(claim);}};
      var result=chat.execute(new AgentRunContext(run,"session",dir,"project"),"Produce artifact",new UserInterventionQueue(),reservation);
      done.accept(new GoalLoopService.Outcome(result));
    },new ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    var stopped=loop.run("project",goal.id());assertEquals("BLOCKED",stopped.status());assertEquals(2,modelCalls.get());
    assertEquals(2,stopped.llmCallsUsed());assertEquals(1,stopped.attempts());
    assertThrows(IllegalStateException.class,()->loop.run("project",goal.id()));assertEquals(2,modelCalls.get());
  }
}
