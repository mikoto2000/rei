package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.memory.ChatMemory;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ContextCompressionProperties.class, ToolResultDeliveryProperties.class})
public class ContextCompressionConfiguration {
  @Bean public dev.mikoto2000.rei.review.ReviewCoverageLedger reviewCoverageLedger(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new dev.mikoto2000.rei.review.ReviewCoverageLedger(new dev.mikoto2000.rei.storage.StorageDatabase(java.nio.file.Path.of(directory)));
  }
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="rei.tool-result-delivery.review-ledger.enabled", havingValue="true", matchIfMissing=true)
  public dev.mikoto2000.rei.review.ReviewTools reviewTools(dev.mikoto2000.rei.review.ReviewCoverageLedger ledger,
      RawToolResultStore raw, ObjectProvider<dev.mikoto2000.rei.core.Tools> files, TokenEstimator estimator) {
    return new dev.mikoto2000.rei.review.ReviewTools(ledger, raw, files::getObject, estimator);
  }
  @Bean public TokenEstimator contextTokenEstimator() { return TokenEstimator.tokenizerAware(); }
  @Bean public ConversationSummaryRepository conversationSummaryRepository(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ConversationSummaryRepository(java.nio.file.Path.of(directory));
  }
  @Bean public RawToolResultStore rawToolResultStore(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory,dev.mikoto2000.rei.storage.StorageObjectRegistry registry, ToolResultDeliveryProperties delivery) {
    delivery.validate(); return new RawToolResultStore(java.nio.file.Path.of(directory),registry,delivery.getRawResultMaxBytes(),delivery.getRawStoreMaxBytes(),delivery.getRawCacheMaxBytes());
  }
  @Bean public RawToolResultTools rawToolResultTools(RawToolResultStore store, TokenEstimator estimator, ToolResultDeliveryProperties delivery) { return new RawToolResultTools(store,estimator,delivery.getPageTargetTokens()); }
  @Bean public ContextHistoryAdvisor contextHistoryAdvisor(ConversationTurnStore turns, ChatMemory memory,
      ConversationSummaryRepository summaries, dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    return new ContextHistoryAdvisor(turns, memory, summaries, logs);
  }
  @Bean public ContextAssembler contextAssembler(ContextCompressionProperties properties, TokenEstimator estimator,
      ConversationSummaryRepository summaries, RawToolResultStore raw, ObjectProvider<LlmModelProvider> models,
      AgentEventFactory events, AgentEventPublisher publisher,
      ObjectProvider<dev.mikoto2000.rei.core.working.WorkingSet> workingSet,
      ObjectProvider<dev.mikoto2000.rei.review.ReviewTools> reviewTools, ToolResultDeliveryProperties delivery) {
    properties.validate();
    var toolDelivery = new ToolResultCompressor(raw, estimator, properties.getToolResultThreshold(), properties.getToolResultTokens());
    toolDelivery.setDeliveryProperties(delivery);
    var assembler = new ContextAssembler(properties, estimator, summaries,
        toolDelivery,
        new LlmConversationCompressor(models, properties), events, publisher);
    assembler.setReviewTools(reviewTools.getIfAvailable());
    assembler.setRequireReviewCoverage(delivery.getReviewLedger().isRequireCoverage());
    assembler.setWorkingSet(() -> workingSet.getObject().renderForPrompt());
    return assembler;
  }
}
