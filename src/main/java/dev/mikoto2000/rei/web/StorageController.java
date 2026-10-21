package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.command.StorageCommand;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 保存をWeb APIの操作として受け付ける。
 *
 * <p>Fixed maintenance endpoints preserve the existing retention consent and protected-object
 * checks.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/storage")
public final class StorageController {
  /** 保存。 */
  private final StorageCommand storage;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す計画を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param current 現在
   * @param category 分類
   * @param maxObjects 最大対象データ一覧
   * @param maxBytes バイト数の上限
   */
  public record Plan(
      String projectId, boolean current, String category, Integer maxObjects, Long maxBytes)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す判断基準を表す。
   *
   * @param category 分類
   * @param retentionDays 保持期間日数
   * @param maxCount 最大件数
   * @param maxPolicyBytes 最大判断基準バイト数
   * @param clearCount 保持内容の消去件数
   * @param clearPolicyBytes 保持内容の消去判断基準バイト数
   */
  public record Policy(
      String category,
      String retentionDays,
      Long maxCount,
      Long maxPolicyBytes,
      boolean clearCount,
      boolean clearPolicyBytes)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す自動を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param current 現在
   * @param category 分類
   * @param maxObjects 最大対象データ一覧
   * @param maxBytes バイト数の上限
   * @param intervalSeconds 実行間隔秒数
   */
  public record Automatic(
      String projectId,
      boolean current,
      String category,
      Integer maxObjects,
      Long maxBytes,
      Long intervalSeconds)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すページ一覧を表す。
   *
   * @param pages ページ一覧
   */
  public record Pages(Integer pages) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す範囲を表す。
   *
   * @param windowSeconds 範囲秒数
   */
  public record Window(Long windowSeconds) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、StorageControllerを初期化する。 */
  public StorageController(StorageCommand storage, ProjectRegistry projects) {
    this.storage = storage;
    this.projects = projects;
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @GetMapping("/status")
  public Text status(
      @RequestParam(required = false) String projectId,
      @RequestParam(defaultValue = "false") boolean current) {
    return scoped(projectId, current, () -> storage.reportStatus(current ? "current" : null));
  }

  /** 依頼を進めるための候補や手順を組み立てる。 */
  @PostMapping("/retention/plans")
  public Text plan(@RequestBody Plan request) {
    if (request == null) throw new IllegalArgumentException("Plan required");
    optional(request.category());
    return scoped(
        request.projectId(),
        request.current(),
        () ->
            storage.reportPlan(
                request.category() == null ? "raw-results" : request.category(),
                request.current() ? "current" : null,
                request.maxObjects() == null ? 100 : request.maxObjects(),
                request.maxBytes() == null ? 16777216 : request.maxBytes()));
  }

  /** Web APIによる操作で用いる承認を返す。 */
  @PostMapping("/retention/plans/{id}/approve")
  public Text approve(@PathVariable String id) {
    required(id);
    return text(storage.reportApprove(id));
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  @PostMapping("/retention/plans/{id}/apply")
  public Text apply(@PathVariable String id) {
    required(id);
    return text(storage.reportApply(id));
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/retention/history")
  public Text history() {
    return text(storage.reportRetentionHistory());
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  @PostMapping("/retention/executions/{id}/restore")
  public Text restore(@PathVariable String id) {
    required(id);
    return text(storage.reportRestore(id));
  }

  /** Web APIによる操作で用いる承認保持期限を過ぎた情報の削除を返す。 */
  @PostMapping("/retention/executions/{id}/approve-purge")
  public Text approvePurge(@PathVariable String id) {
    required(id);
    return text(storage.reportApprovePurge(id));
  }

  /** Web APIによる操作で用いる保持期限を過ぎた情報の削除を返す。 */
  @PostMapping("/retention/executions/{id}/purge")
  public Text purge(@PathVariable String id) {
    required(id);
    return text(storage.reportPurge(id));
  }

  /** Web APIによる操作で用いる判断基準を返す。 */
  @PostMapping("/retention/policy")
  public Text policy(@RequestBody Policy request) {
    if (request == null) throw new IllegalArgumentException("Policy required");
    required(request.category());
    optional(request.retentionDays());
    return text(
        storage.reportPolicy(
            request.category(),
            request.retentionDays(),
            request.maxCount(),
            request.maxPolicyBytes(),
            request.clearCount(),
            request.clearPolicyBytes()));
  }

  /** 後続の判断で選べる候補を用意するため、保存受付窓口を提案する。 */
  @PostMapping("/retention/automatic/proposals")
  public Text propose(@RequestBody Automatic request) {
    if (request == null
        || request.maxObjects() == null
        || request.maxBytes() == null
        || request.intervalSeconds() == null)
      throw new IllegalArgumentException("Explicit automatic retention limits required");
    required(request.category());
    return scoped(
        request.projectId(),
        request.current(),
        () ->
            storage.reportAutoPropose(
                request.category(),
                request.current() ? "current" : null,
                request.maxObjects(),
                request.maxBytes(),
                request.intervalSeconds()));
  }

  /** Web APIによる操作で用いる承認自動を返す。 */
  @PostMapping("/retention/automatic/{id}/approve")
  public Text approveAutomatic(@PathVariable String id) {
    required(id);
    return text(storage.reportAutoApprove(id));
  }

  /** Web APIによる操作で用いる無効化自動を返す。 */
  @PostMapping("/retention/automatic/{id}/disable")
  public Text disableAutomatic(@PathVariable String id) {
    required(id);
    return text(storage.reportAutoDisable(id));
  }

  /** Web APIによる操作で用いる自動履歴を返す。 */
  @GetMapping("/retention/automatic/history")
  public Text automaticHistory() {
    return text(storage.reportAutoHistory());
  }

  /** Web APIによる操作で用いる保守状態を返す。 */
  @GetMapping("/maintenance/status")
  public Text maintenanceStatus() {
    return text(storage.reportMaintenanceStatus());
  }

  /** Web APIによる操作で用いる再開用の記録を返す。 */
  @PostMapping("/maintenance/checkpoint")
  public Text checkpoint() {
    return text(storage.reportCheckpoint());
  }

  /** Web APIによる操作で用いるincremental保存領域の整理を返す。 */
  @PostMapping("/maintenance/incremental-vacuum")
  public Text incrementalVacuum(@RequestBody Pages request) {
    if (request == null || request.pages() == null)
      throw new IllegalArgumentException("Explicit page budget required");
    return text(storage.reportIncrementalVacuum(request.pages()));
  }

  /** Web APIによる操作で用いる保存領域の整理を返す。 */
  @PostMapping("/maintenance/vacuum")
  public Text vacuum(@RequestBody Window request) {
    if (request == null || request.windowSeconds() == null)
      throw new IllegalArgumentException("Explicit maintenance window required");
    return text(storage.reportVacuum(request.windowSeconds()));
  }

  /** Web APIによる操作で用いる対象を限定したを返す。 */
  private Text scoped(String projectId, boolean current, Supplier<String> operation) {
    if (projectId == null) {
      if (current) throw new IllegalArgumentException("Select a Project first");
      return text(operation.get());
    }
    required(projectId);
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext(
                UUID.randomUUID().toString(),
                "",
                project.root(),
                project.id(),
                AgentRunContext.RequestSource.WEB))) {
      return text(operation.get());
    }
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Value required");
    optional(value);
  }

  /** Web APIによる操作に関する任意を処理する。 */
  private static void optional(String value) {
    if (value != null && (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid value");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }
}
