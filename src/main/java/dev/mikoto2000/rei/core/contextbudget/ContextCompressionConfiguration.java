package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.memory.ChatMemory;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContextCompressionProperties.class)
public class ContextCompressionConfiguration {
  @Bean public TokenEstimator contextTokenEstimator() { return TokenEstimator.conservative(); }
  @Bean public ConversationSummaryRepository conversationSummaryRepository(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ConversationSummaryRepository(java.nio.file.Path.of(directory));
  }
  @Bean public RawToolResultStore rawToolResultStore(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new RawToolResultStore(java.nio.file.Path.of(directory));
  }
  @Bean public RawToolResultTools rawToolResultTools(RawToolResultStore store) { return new RawToolResultTools(store); }
  @Bean public ContextHistoryAdvisor contextHistoryAdvisor(ConversationTurnStore turns, ChatMemory memory,
      ConversationSummaryRepository summaries, dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    return new ContextHistoryAdvisor(turns, memory, summaries, logs);
  }
  @Bean public ContextAssembler contextAssembler(ContextCompressionProperties properties, TokenEstimator estimator,
      ConversationSummaryRepository summaries, RawToolResultStore raw, ObjectProvider<LlmModelProvider> models,
      AgentEventFactory events, AgentEventPublisher publisher,
      ObjectProvider<dev.mikoto2000.rei.core.working.WorkingSet> workingSet) {
    properties.validate();
    var assembler = new ContextAssembler(properties, estimator, summaries,
        new ToolResultCompressor(raw, estimator, properties.getToolResultThreshold(), properties.getToolResultTokens()),
        new LlmConversationCompressor(models, properties), events, publisher);
    assembler.setWorkingSet(() -> workingSet.getObject().renderForPrompt());
    return assembler;
  }
}
