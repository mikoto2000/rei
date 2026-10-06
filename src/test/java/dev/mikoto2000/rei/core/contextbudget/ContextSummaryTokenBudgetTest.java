package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.llm.*;
import reactor.core.publisher.Flux;

class ContextSummaryTokenBudgetTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
  ChatResponse response(String text,Integer tokens) {
    var metadata=ChatResponseMetadata.builder();
    if(tokens!=null)metadata.usage(new DefaultUsage(1,tokens-1));
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),metadata.build());
  }
  LlmConversationCompressor compressor(ChatModel model) {
    return compressor(model,new ContextCompressionProperties());
  }
  LlmConversationCompressor compressor(ChatModel model,ContextCompressionProperties properties) {
    var provider=mock(LlmModelProvider.class);
    when(provider.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(provider.chatOptions(eq(LlmFeature.CHAT),any())).thenAnswer(invocation->org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    @SuppressWarnings("unchecked") ObjectProvider<LlmModelProvider> models=mock(ObjectProvider.class);
    when(models.getObject()).thenReturn(provider);
    return new LlmConversationCompressor(models,properties);
  }
  Prompt owner(RunExecutionContext execution) {
    return new Prompt("current",ToolCallingChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY,execution)).build());
  }
  @Test void summaryChargesAggregatedUsageOnceAndBlocksPrepaidParentAtLimit() {
    var budget=new OutputLimitRunBudget(0,10,null,5);
    var execution=new RunExecutionContext("run",budget,null,null,null);execution.consumeNextLlmCall();
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("compact",null),response(" summary",5)));
    assertThat(compressor(model).summarize("",List.of(new UserMessage("history")),100,owner(execution))).isEqualTo("compact summary");
    assertThat(budget.totalTokens()).isEqualTo(5);
    var parent=mock(ChatModel.class);
    assertThatThrownBy(()->new StagnationChatModel(parent).stream(owner(execution)).collectList().block())
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    verifyNoInteractions(parent);
  }
  @Test void unknownUsageAndProviderFailureStopInsteadOfBecomingFreeFallback() {
    for(Flux<ChatResponse> responses:List.of(Flux.just(response("summary",null)),
        Flux.<ChatResponse>error(new IllegalStateException("offline")),Flux.<ChatResponse>empty())) {
      var execution=new RunExecutionContext("run",new OutputLimitRunBudget(0,10,null,5),null,null,null);
      var model=mock(ChatModel.class);
      when(model.stream(any(Prompt.class))).thenReturn(responses);
      assertThatThrownBy(()->compressor(model).summarize("",List.of(new UserMessage("history")),100,owner(execution)))
          .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
      assertThat(execution.sharedLlmReservation().tryReserve()).isFalse();
    }
  }
  @Test void overshootRejectsSummaryAndDisabledLimitPreservesUnknownUsage() {
    var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary",6)));
    var execution=new RunExecutionContext("run",new OutputLimitRunBudget(0,10,null,5),null,null,null);
    assertThatThrownBy(()->compressor(model).summarize("",List.of(new UserMessage("history")),100,owner(execution)))
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    var legacy=new RunExecutionContext("old",new OutputLimitRunBudget(0,10),null,null,null);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary",null)));
    assertThat(compressor(model).summarize("",List.of(new UserMessage("history")),100,owner(legacy))).isEqualTo("summary");
  }
  @Test void truncatedSummaryChargesKnownUsageBeforeOutputLimitFailure() {
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("partial"),
        ChatGenerationMetadata.builder().finishReason("length").build())),ChatResponseMetadata.builder().usage(new DefaultUsage(1,1)).build())));
    var budget=new OutputLimitRunBudget(0,10,null,100);
    var execution=new RunExecutionContext("run",budget,null,null,null);
    assertThatThrownBy(()->compressor(model).summarize("",List.of(new UserMessage("history")),100,owner(execution)))
        .hasMessage("Summary output limit");
    assertThat(budget.totalTokens()).isEqualTo(2);assertThat(budget.usageUnknown()).isFalse();
  }
  @Test void summaryTimeoutCancelsProviderAndBlocksUnknownUsage() {
    var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    var model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never().doOnCancel(()->cancelled.set(true)));
    var execution=new RunExecutionContext("run",new OutputLimitRunBudget(0,10,null,100),null,null,null);
    var properties=new ContextCompressionProperties();properties.setSummaryTimeoutSeconds(1);
    assertThatThrownBy(()->compressor(model,properties).summarize("",List.of(new UserMessage("history")),100,owner(execution)))
        .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
    assertThat(cancelled).isTrue();assertThat(execution.sharedLlmReservation().tryReserve()).isFalse();
  }
  ContextCompressionProperties compressionConfig() {
    var props=new ContextCompressionProperties();
    props.setThreshold(300);props.setHardLimit(600);props.setRecentTokens(80);props.setSummaryTokens(100);
    return props;
  }
  Prompt historical(RunExecutionContext execution) {
    return new Prompt(List.of(ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(500)),1),
        ContextHistoryAdvisor.historical(new AssistantMessage("recent"),2),new UserMessage("current")),owner(execution).getOptions());
  }
  @Test @org.junit.jupiter.api.Tag("integration")
  void actualCompressionChargesDurableGoalAndCannotFallBackIntoParentModel() {
    for(Integer usage:Arrays.asList(5,6,null)) {
      var variant=dir.resolve("case-"+usage);
      var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goal-"+usage+".db"));
      var properties=new LlmProperties();properties.getOutputLimit().setMaxTotalTokensPerGoal(5);
      var goals=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC(),properties);
      var goal=goals.create(new dev.mikoto2000.rei.core.chat.AgentRunContext("source","chat",dir,"project"),"Artifact","out.txt","a".repeat(64),3,10);
      var claim=goals.claim("project",goal.id());var runId=goals.beginAttempt(claim);
      var budget=new OutputLimitRunBudget(0,10,goals.modelBudget(claim,runId));
      var events=new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
      var execution=new RunExecutionContext(runId,budget,new ProgressEvaluator(dir,null),events,event->{});
      execution.setRunContext(new dev.mikoto2000.rei.core.chat.AgentRunContext(runId,"chat",dir,"project"));
      execution.consumeNextLlmCall();
      var summary=mock(ChatModel.class);when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary",usage)));
      var summaries=new ConversationSummaryRepository(variant);
      var tokens=TokenEstimator.conservative();
      var assembler=new ContextAssembler(compressionConfig(),tokens,summaries,
          new ToolResultCompressor(new RawToolResultStore(variant),tokens,100,70),compressor(summary),events,event->{});
      var parent=mock(ChatModel.class);
      assertThatThrownBy(()->new StagnationChatModel(parent,assembler).stream(historical(execution)).collectList().block())
          .hasMessageContaining(usage==null?"TOKEN_USAGE_UNKNOWN":"TOKEN_BUDGET_EXCEEDED");
      verifyNoInteractions(parent);
      var reopened=new dev.mikoto2000.rei.goal.GoalRepository(source,java.time.Clock.systemUTC());
      assertThat(reopened.get("project",goal.id()).totalTokens()).isEqualTo(usage==null?0:usage);
      assertThat(reopened.get("project",goal.id()).llmCallsUsed()).isEqualTo(2);
      assertThat(reopened.get("project",goal.id()).tokenUsageUnknown()).isEqualTo(usage==null);
      assertThat(summaries.read("chat").throughSequence()).isEqualTo(Objects.equals(usage,5)?1:0);
    }
  }
  @Test @org.junit.jupiter.api.Tag("integration")
  void rejectedLowQualitySummaryStillChargesUsageBeforeDegradedProjection() {
    var budget=new OutputLimitRunBudget(0,10,null,100);
    var execution=new RunExecutionContext("run",budget,null,null,null);execution.consumeNextLlmCall();
    var summary=mock(ChatModel.class);when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(response("bad ".repeat(500),2)));
    var summaries=new ConversationSummaryRepository(dir);var tokens=TokenEstimator.conservative();
    var assembler=new ContextAssembler(compressionConfig(),tokens,summaries,
        new ToolResultCompressor(new RawToolResultStore(dir),tokens,100,70),compressor(summary),null,null);
    assertThat(assembler.assemble(historical(execution),"chat","run",execution::checkActive).getContents()).contains("current");
    assertThat(budget.totalTokens()).isEqualTo(2);assertThat(budget.remainingLlmCalls()).isEqualTo(8);
    assertThat(summaries.read("chat").throughSequence()).isZero();
    verify(summary,times(1)).stream(any(Prompt.class));
  }
}
