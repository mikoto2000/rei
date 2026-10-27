package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 補助エージェントへの委譲に必要な補助エージェントツール利用可能な項目を扱う。
 *
 * <p>Local file helpers get transient caches/Working Set, never project-scoped mutable beans.
 */
@Component
public class SubAgentToolCatalog {
  private dev.mikoto2000.rei.httpapi.HttpApiTools httpApiTools;

  /** Only the read callback is exposed to subagents. */
  @org.springframework.beans.factory.annotation.Autowired
  void setHttpApiTools(dev.mikoto2000.rei.httpapi.HttpApiTools tools) {
    this.httpApiTools = tools;
  }

  /** 知識。 */
  private final SearchTools knowledge;

  /** Web経由の入力または調査に使う窓口。 */
  private final WebSearchTools web;

  /** 指定された値や利用先を使って、SubAgentToolCatalogを初期化する。 */
  public SubAgentToolCatalog(SearchTools knowledge, WebSearchTools web) {
    this.knowledge = knowledge;
    this.web = web;
  }

  /** 記録台帳。 */
  private dev.mikoto2000.rei.review.ReviewCoverageLedger ledger;

  /** 元の。 */
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultStore raw;

  /** 概算。 */
  private dev.mikoto2000.rei.core.contextbudget.TokenEstimator estimator =
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator.tokenizerAware();

  /** この機能の動作設定。 */
  private dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties properties =
      new dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties();

  /** 補助エージェントへの委譲に関する設定を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void configure(
      dev.mikoto2000.rei.review.ReviewCoverageLedger ledger,
      dev.mikoto2000.rei.core.contextbudget.RawToolResultStore raw,
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator estimator,
      dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties properties) {
    this.ledger = ledger;
    this.raw = raw;
    this.estimator = estimator;
    this.properties = properties;
  }

  /** 新たな対象を利用できるよう、ツール一覧を作成する。 */
  public List<ToolCallback> createTools() {
    var files = new Tools();
    var localPages = new dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties();
    localPages.setPageTargetTokens(properties.getPageTargetTokens());
    localPages.setPageMinTokens(properties.getPageMinTokens());
    localPages.setPageMaxTokens(properties.getPageTargetTokens());
    localPages.setPageSafetyTokens(properties.getPageSafetyTokens());
    files.configureDelivery(estimator, localPages);
    var objects = new ArrayList<Object>(List.of(files, knowledge, web));
    if (raw != null)
      objects.add(
          new dev.mikoto2000.rei.core.contextbudget.RawToolResultTools(
              raw, estimator, properties.getPageTargetTokens()));
    if (ledger != null && raw != null && properties.getReviewLedger().isEnabled())
      objects.add(new dev.mikoto2000.rei.review.ReviewTools(ledger, raw, files, estimator));
    if (httpApiTools != null) objects.add(httpApiTools);
    return java.util.Arrays.stream(
            dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(
                    objects.toArray())
                .getToolCallbacks())
        .filter(callback -> !callback.getToolDefinition().name().equals("httpRequestWrite"))
        .toList();
  }

  /** 補助エージェントへの委譲で用いる既知名前一覧を返す。 */
  public Set<String> knownNames() {
    Set<String> names = new HashSet<>();
    createTools().forEach(callback -> names.add(callback.getToolDefinition().name()));
    names.add("delegateTask");
    names.add("delegateTasks");
    return Set.copyOf(names);
  }
}
