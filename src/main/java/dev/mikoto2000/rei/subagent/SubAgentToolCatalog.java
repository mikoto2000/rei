package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Local file helpers get transient caches/Working Set, never project-scoped mutable beans. */
@Component
public class SubAgentToolCatalog {
  private final SearchTools knowledge;
  private final WebSearchTools web;

  public SubAgentToolCatalog(SearchTools knowledge, WebSearchTools web) {
    this.knowledge = knowledge;
    this.web = web;
  }

  private dev.mikoto2000.rei.review.ReviewCoverageLedger ledger;
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultStore raw;
  private dev.mikoto2000.rei.core.contextbudget.TokenEstimator estimator =
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator.tokenizerAware();
  private dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties properties =
      new dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties();

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
    return List.of(
        dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(objects.toArray())
            .getToolCallbacks());
  }

  public Set<String> knownNames() {
    Set<String> names = new HashSet<>();
    createTools().forEach(callback -> names.add(callback.getToolDefinition().name()));
    names.add("delegateTask");
    names.add("delegateTasks");
    return Set.copyOf(names);
  }
}
