package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文脈圧縮の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  ContextCompressionProperties.class,
  ToolResultDeliveryProperties.class
})
public class ContextCompressionConfiguration {
  @Bean
  public dev.mikoto2000.rei.tooldelivery.ReadingDelivery readingDelivery(
      dev.mikoto2000.rei.tooldelivery.ReadingStateStore store, RawToolResultStore raw) {
    return new dev.mikoto2000.rei.tooldelivery.ReadingDelivery(store,
        new dev.mikoto2000.rei.tooldelivery.ReadingRawAccess(
            raw::save, raw::readingBody, RawToolResultTools::readingPage),
        dev.mikoto2000.rei.core.Tools::readingPath);
  }

  @Bean
  @org.springframework.context.annotation.DependsOn("storageMigrationLease")
  public dev.mikoto2000.rei.tooldelivery.ReadingStateStore readingStateStore(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    var database = new dev.mikoto2000.rei.storage.StorageDatabase(java.nio.file.Path.of(directory));
    return new dev.mikoto2000.rei.tooldelivery.ReadingStateStore((write, work) ->
        write ? database.transaction(work::run) : database.read(work::run));
  }

  /** モデルへ渡す文脈の管理で用いるレビュー対象範囲記録台帳を返す。 */
  @Bean
  public dev.mikoto2000.rei.review.ReviewCoverageLedger reviewCoverageLedger(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new dev.mikoto2000.rei.review.ReviewCoverageLedger(
        new dev.mikoto2000.rei.storage.StorageDatabase(java.nio.file.Path.of(directory)));
  }

  /** モデルへ渡す文脈の管理で用いるレビューツール一覧を返す。 */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "rei.tool-result-delivery.review-ledger.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public dev.mikoto2000.rei.review.ReviewTools reviewTools(
      dev.mikoto2000.rei.review.ReviewCoverageLedger ledger,
      RawToolResultStore raw,
      ObjectProvider<dev.mikoto2000.rei.core.Tools> files,
      TokenEstimator estimator) {
    return new dev.mikoto2000.rei.review.ReviewTools(ledger, raw, files::getObject, estimator);
  }

  /** モデルへ渡す文脈の管理で用いる文脈トークン概算を返す。 */
  @Bean
  public TokenEstimator contextTokenEstimator() {
    return TokenEstimator.tokenizerAware();
  }

  /** モデルへ渡す文脈の管理で用いる会話要約保存と検索の窓口を返す。 */
  @Bean
  public ConversationSummaryRepository conversationSummaryRepository(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ConversationSummaryRepository(java.nio.file.Path.of(directory));
  }

  /** モデルへ渡す文脈の管理で用いる元のツールの結果保存先を返す。 */
  @Bean
  public RawToolResultStore rawToolResultStore(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory,
      dev.mikoto2000.rei.storage.StorageObjectRegistry registry,
      ToolResultDeliveryProperties delivery) {
    delivery.validate();
    return new RawToolResultStore(
        java.nio.file.Path.of(directory),
        registry,
        delivery.getRawResultMaxBytes(),
        delivery.getRawStoreMaxBytes(),
        delivery.getRawCacheMaxBytes());
  }

  /** モデルへ渡す文脈の管理で用いる元のツールの結果ツール一覧を返す。 */
  @Bean
  public RawToolResultTools rawToolResultTools(
      RawToolResultStore store, TokenEstimator estimator, ToolResultDeliveryProperties delivery) {
    return new RawToolResultTools(store, estimator, delivery.getPageTargetTokens());
  }

  /** モデルへ渡す文脈の管理で用いる文脈履歴対話の補助を返す。 */
  @Bean
  public ContextHistoryAdvisor contextHistoryAdvisor(
      ConversationTurnStore turns,
      ChatMemory memory,
      ConversationSummaryRepository summaries,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    return new ContextHistoryAdvisor(turns, memory, summaries, logs);
  }

  /** モデルへ渡す文脈の管理で用いる文脈文脈の組み立てを返す。 */
  @Bean
  public ContextAssembler contextAssembler(
      ContextCompressionProperties properties,
      TokenEstimator estimator,
      ConversationSummaryRepository summaries,
      RawToolResultStore raw,
      ObjectProvider<LlmModelProvider> models,
      AgentEventFactory events,
      AgentEventPublisher publisher,
      ObjectProvider<dev.mikoto2000.rei.core.working.WorkingSet> workingSet,
      ObjectProvider<dev.mikoto2000.rei.review.ReviewTools> reviewTools,
      ToolResultDeliveryProperties delivery,
      dev.mikoto2000.rei.tooldelivery.ReadingDelivery readingDelivery) {
    properties.validate();
    var toolDelivery =
        new ToolResultCompressor(
            raw, estimator, properties.getToolResultThreshold(), properties.getToolResultTokens());
    toolDelivery.setDeliveryProperties(delivery);
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            summaries,
            toolDelivery,
            new LlmConversationCompressor(models, properties),
            events,
            publisher);
    assembler.setReviewTools(reviewTools.getIfAvailable());
    assembler.setReadingDelivery(readingDelivery);
    assembler.setRequireReviewCoverage(delivery.getReviewLedger().isRequireCoverage());
    assembler.setWorkingSet(() -> workingSet.getObject().renderForPrompt());
    return assembler;
  }
}
