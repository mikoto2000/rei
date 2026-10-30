package dev.mikoto2000.rei.core.policy;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理者の設定に基づいてツールの利用権限を判断し、呼び出し引数の契約を検証する。
 *
 * <p>Capability grants come from administrator configuration, never model-provided arguments.
 */
@Component
@EnableConfigurationProperties(ToolPermissionProperties.class)
public class ToolPermissionPolicy implements dev.mikoto2000.rei.secretary.SecretaryExecutionPolicy {
  @Override
  public dev.mikoto2000.rei.secretary.SecretaryExecutionPolicy.Decision decision(String kind) {
    // Fixed intrinsic host capabilities cannot be relabeled as reads by a caller/tool override.
    var required =
        switch (kind) {
          case "DRAFT", "UPDATE_TASK" -> Set.of(ActionCapability.LOCAL_WRITE);
          case "GOAL", "ROUTINE" -> Set.of(ActionCapability.EXECUTE, ActionCapability.LOCAL_WRITE);
          case "RESEARCH", "ORGANIZE", "SUMMARY", "PREPARE", "STATUS" ->
              Set.of(ActionCapability.READ);
          case "EXTERNAL_READ" -> Set.of(ActionCapability.NETWORK_READ);
          case "EXTERNAL_CALENDAR_CREATE",
              "EXTERNAL_CALENDAR_UPDATE",
              "EXTERNAL_MAIL_DRAFT",
              "EXTERNAL_MAIL_SEND",
              "EXTERNAL_SLACK_POST",
              "EXTERNAL_SLACK_REPORT" ->
              Set.of(
                  ActionCapability.NETWORK_READ,
                  ActionCapability.NETWORK_WRITE,
                  ActionCapability.EXTERNAL_SIDE_EFFECT);
          case "EXTERNAL_CALENDAR_CANCEL", "EXTERNAL_REVOKE" ->
              Set.of(
                  ActionCapability.NETWORK_READ,
                  ActionCapability.NETWORK_WRITE,
                  ActionCapability.EXTERNAL_SIDE_EFFECT,
                  ActionCapability.DESTRUCTIVE);
          default -> Set.copyOf(EnumSet.allOf(ActionCapability.class));
        };
    return new dev.mikoto2000.rei.secretary.SecretaryExecutionPolicy.Decision(
        properties.enabled(),
        properties.enabled() ? evaluateCapabilities(required).name() : "DENY",
        authorityFingerprint());
  }

  /** 既定で慎重な権限判断を必要とする、実行や変更を伴うツール名。 */
  private static final Set<String> CONSERVATIVE_TOOLS =
      Set.of(
          "runCommand",
          "executeExternalProgram",
          "selfReviewPatch",
          "selfRepairPatch",
          "reviewPatchRequirements",
          "applyDiagnosedRepair",
          "requestCodexImplementation",
          "requestClaudeCodeImplementation",
          "computerUse",
          "checkpointResume");

  /** ローカル情報の参照権限として扱うツール名。 */
  private static final Set<String> READ =
      Set.of(
          "restartReview",
          "reviewPending",
          "beginReview",
          "beginRepositoryReview",
          "scanReviewSnapshot",
          "reviewTaskStatus",
          "listReviewTargets",
          "listReviewTasks",
          "resumeRepositoryReview",
          "claimReviewWorkItem",
          "readReviewWorkItem",
          "submitReviewWorkItem",
          "readReviewFile",
          "readReviewPages",
          "submitReviewResult",
          "submitReviewPage",
          "submitReviewPages",
          "dismissReviewFinding",
          "reviewStatus",
          "reviewFindings",
          "countFileLines",
          "readFile",
          "readMultiFile",
          "readSkillResource",
          "readRawToolResult",
          "grepMultiQuery",
          "readPdfFile",
          "searchAndRead",
          "searchFilePage",
          "today",
          "now",
          "findFile",
          "listFile",
          "repositoryMap",
          "changeTestImpact",
          "diagnoseTestReport",
          "diagnoseTestReports",
          "readTextChangeSetBase",
          "inspectTextChangeSet",
          "getShellProcessStatus",
          "waitForShellProcess",
          "waitForFile",
          "dependencyStatus",
          "dependencyHistory",
          "checkDependency",
          "waitForDependency",
          "listScheduledActions",
          "listCodexReviews",
          "getCodexReview");

  /** 外部情報の取得権限として扱うツール名。 */
  private static final Set<String> NETWORK_READ =
      Set.of(
          "webSearch",
          "webSearchAndRead",
          "fetchUrlContent",
          "httpRequestRead",
          "checkHttpDependency",
          "waitForHttpDependency");

  /** ローカル状態の変更権限として扱うツール名。 */
  private static final Set<String> LOCAL_WRITE =
      Set.of(
          "applyTextDiff",
          "writeMultiFile",
          "proposeTextChangeSet",
          "applyTextChangeSet",
          "discardTextChangeSet",
          "reconcileTextChangeSet",
          "createDirectory",
          "copyFile",
          "scheduleAfter",
          "scheduleAt",
          "scheduleInterval",
          "scheduleCron",
          "scheduleOnEvent",
          "cancelScheduledAction",
          "registerDependency",
          "cancelDependency");

  /** この機能の動作設定。 */
  private final ToolPermissionProperties properties;

  /** 自動実行の指定。 */
  private final boolean fullAuto;

  /** 検索件数など、Web検索ツールの引数検証に用いる上限設定。 */
  private dev.mikoto2000.rei.websearch.WebSearchProperties webSearchProperties =
      new dev.mikoto2000.rei.websearch.WebSearchProperties();

  /** 委譲先と委譲内容に応じた利用条件を判断する窓口。 */
  private dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver delegation;

  /** ツール利用の権限制御で使用するWeb検索設定を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setWebSearchProperties(dev.mikoto2000.rei.websearch.WebSearchProperties properties) {
    this.webSearchProperties = properties;
  }

  /** ツール利用の権限制御で使用する委譲対象の解決を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setDelegationResolver(
      dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver resolver) {
    this.delegation = resolver;
  }

  /** 各ツールの引数の契約を検証し、不正な指定を権限確認や実行へ進めない。 */
  public void validateArguments(String tool, String input) {
    validateCommonArguments(tool, input, webSearchProperties.getMaxResults());
    validateDelegation(tool, input);
  }

  /** ポリシー設定の有無によらず、引数補正を扱うツールを判定する。 */
  public static boolean supportsArgumentCorrection(String tool) {
    return Set.of("delegateTask", "delegateTasks").contains(tool)
        || dev.mikoto2000.rei.core.taskstate.TaskStateArguments.supports(tool)
        || ShellFileReadArguments.supports(tool)
        || ProcessWaitArguments.supports(tool)
        || FindFileArguments.supports(tool)
        || TextEditArguments.supports(tool)
        || dev.mikoto2000.rei.websearch.WebSearchArguments.supports(tool)
        || RawToolResultArguments.supports(tool);
  }

  /** 登録された入力契約も含めて、実行前の引数補正を扱えるか判定する。 */
  public static boolean supportsArgumentCorrection(String tool, String schema) {
    return supportsArgumentCorrection(tool) || ToolSchemaArguments.supports(schema);
  }

  /** 専用の編集契約がないツールは、登録された必須項目を検証する。 */
  public static void validateSchemaArguments(String tool, String schema, String input) {
    if (!TextEditArguments.supports(tool)) ToolSchemaArguments.validate(tool, schema, input);
  }

  /** 承認や実行より前に、設定不要の検証とWeb検索件数の検証を共通で行う。 */
  public static void validateCommonArguments(String tool, String input, int webSearchMaxResults) {
    dev.mikoto2000.rei.websearch.WebSearchArguments.validate(tool, input, webSearchMaxResults);
    ShellFileReadArguments.validate(tool, input);
    ProcessWaitArguments.validate(tool, input);
    FindFileArguments.validate(tool, input);
    TextEditArguments.validate(tool, input);
    RawToolResultArguments.validate(tool, input);
    dev.mikoto2000.rei.core.taskstate.TaskStateArguments.validate(tool, input);
  }

  /** 委譲先の選択に必要な条件を検証し、修正可能な入力不足を引数エラーとして伝える。 */
  public void validateDelegation(String tool, String input) {
    try {
      if (delegation != null) delegation.validate(tool, input);
      else dev.mikoto2000.rei.subagent.DelegationArguments.validate(tool, input);
    } catch (InvalidToolArgumentsException invalid) {
      throw invalid;
    } catch (IllegalArgumentException invalid) {
      throw new InvalidToolArgumentsException(invalid.getMessage());
    } catch (dev.mikoto2000.rei.chatgpt.ChatGptException denied) {
      if (denied.permissionReason()
              == dev.mikoto2000.rei.chatgpt.ChatGptException.PermissionReason.ACCOUNT_REQUIRED
          || denied.permissionReason()
              == dev.mikoto2000.rei.chatgpt.ChatGptException.PermissionReason.MODEL_REQUIRED)
        throw new InvalidToolArgumentsException(denied.getMessage());
      throw denied;
    }
  }

  /** ツール利用の権限制御を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ToolPermissionPolicy(ToolPermissionProperties properties) {
    this(properties, false);
  }

  /** ツール利用の権限制御を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ToolPermissionPolicy(
      ToolPermissionProperties properties, dev.mikoto2000.rei.computeruse.FullAutoOptions options) {
    this(properties, options.enabled());
  }

  /** 指定された値や利用先を使って、ToolPermissionPolicyを初期化する。 */
  private ToolPermissionPolicy(ToolPermissionProperties properties, boolean fullAuto) {
    this.properties = properties;
    this.fullAuto = fullAuto;
  }

  /** ツール利用の権限制御において、自動実行の指定に該当するか判定する。 */
  public boolean fullAuto() {
    return fullAuto;
  }

  /**
   * 承認時の権限設定を後から照合できるよう、管理者設定と自動実行指定の識別値を返す。
   *
   * <p>Stable administrator policy identity for durable authorization audit.
   */
  public String authorityFingerprint() {
    return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(
        (properties.enabled()
                + "|"
                + new TreeSet<>(properties.autoApprove())
                + "|"
                + new TreeSet<>(properties.denied())
                + "|"
                + new TreeMap<>(properties.capabilities())
                    .entrySet().stream()
                        .map(e -> e.getKey() + "=" + new TreeSet<>(e.getValue()))
                        .toList()
                + (fullAuto ? "|fullauto=true" : ""))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 権限制御が設定で有効か、または自動実行モードによって必要になるかを返す。 */
  public boolean enforced() {
    return properties.enabled() || fullAuto;
  }

  /** ツールと委譲内容から必要な操作権限を求め、管理者の指定を反映する。 */
  public Set<ActionCapability> capabilities(String tool, String input) {
    if ("runCommand".equals(tool) && ShellHttpUsage.input(input))
      return intrinsicCapabilities(tool);
    if (dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)) {
      var required = EnumSet.noneOf(ActionCapability.class);
      required.addAll(
          delegation == null
              ? EnumSet.allOf(ActionCapability.class)
              : delegation.resolve(tool, input, this::capabilities));
      var configured = properties.capabilities().get(tool);
      if (configured != null) required.addAll(configured);
      return Set.copyOf(required);
    }
    return capabilities(tool);
  }

  /** 要求された操作が、ローカルまたはネットワークの参照権限だけで実施できるか判定する。 */
  public boolean readOnly(String tool, String input) {
    var required =
        dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)
            ? capabilities(tool, input)
            : intrinsicCapabilities(tool);
    return required.stream()
        .allMatch(c -> c == ActionCapability.READ || c == ActionCapability.NETWORK_READ);
  }

  /**
   * ツールが既知の権限分類を持つか確認し、未分類の外部ツールと区別する。
   *
   * <p>Explicit conservative classifications are distinct from unknown third-party callbacks.
   */
  public static boolean classified(String tool) {
    return CONSERVATIVE_TOOLS.contains(tool)
        || dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)
        || !intrinsicCapabilities(tool).equals(Set.copyOf(EnumSet.allOf(ActionCapability.class)));
  }

  /** ツールと委譲内容から必要な操作権限を求め、管理者の指定を反映する。 */
  public Set<ActionCapability> capabilities(String tool) {
    if (Set.of("askExternalAgent", "requestDotResearch", "httpRequestRead", "httpRequestWrite")
        .contains(tool)) return intrinsicCapabilities(tool);
    // 通常のツールでは管理者が指定した権限分類を優先する。
    var configured = properties.capabilities().get(tool);
    if (configured != null) return configured;
    return intrinsicCapabilities(tool);
  }

  /**
   * 管理者による分類の上書きに依存せず、ツール本来の動作に必要な権限を返す。
   *
   * <p>Parallel authority uses known intrinsic behavior, never an administrator relabeling of
   * arbitrary commands.
   */
  public static Set<ActionCapability> intrinsicCapabilities(String tool) {
    if ("httpRequestWrite".equals(tool))
      return Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if (CONSERVATIVE_TOOLS.contains(tool)) return Set.copyOf(EnumSet.allOf(ActionCapability.class));
    if (Set.of(
            "activityWorkInferences",
            "activitySummary",
            "activityTimeline",
            "activityBetween",
            "checkpointList",
            "checkpointShow",
            "checkpointInspect",
            "searchConversationHistory",
            "getConversationHistory",
            "readBinaryFile",
            "feedList",
            "reminderList",
            "getLastSummary",
            "taskList",
            "getCurrentTime",
            "getHolidayInfo",
            "listHolidays",
            "countCharacters",
            "vectorDocumentList",
            "workContextGet",
            "workContextHistory",
            "workContextRevision",
            "searchPaperLibrary")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if (Set.of(
            "createDirectories",
            "writeBinaryFile",
            "updateTaskState",
            "checkpointSave",
            "checkpointAbandon",
            "checkpointAnnotate",
            "checkpointPlan",
            "checkpointConfirm",
            "feedAdd",
            "reminderCreate",
            "taskCreate",
            "taskUpdate",
            "taskComplete",
            "taskUpdateDeadline",
            "workContextUpdate",
            "workContextEdit")
        .contains(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if (Set.of("taskDelete", "feedDelete").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE, ActionCapability.DESTRUCTIVE);
    if (Set.of("googleCalendarListEvents", "googleCalendarListEventsForDate").contains(tool))
      return Set.of(ActionCapability.NETWORK_READ);
    if (Set.of("blueskyPost", "googleCalendarCreateEvent").contains(tool))
      return Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if ("soundNotify".equals(tool))
      return Set.of(ActionCapability.EXECUTE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if (Set.of("dailyBriefing", "vectorDocumentSearch").contains(tool))
      return Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ);
    if (Set.of(
            "feedUpdate",
            "feedSummarizeBriefing",
            "feedSummarizeItem",
            "searchPapers",
            "getPaper",
            "getPaperContent",
            "getPaperSection",
            "summarizePaper",
            "translatePaper",
            "getPaperTranslationChunk")
        .contains(tool))
      return Set.of(
          ActionCapability.READ, ActionCapability.NETWORK_READ, ActionCapability.LOCAL_WRITE);
    if ("vectorDocumentAdd".equals(tool))
      return Set.of(
          ActionCapability.READ,
          ActionCapability.LOCAL_WRITE,
          ActionCapability.NETWORK_WRITE,
          ActionCapability.EXTERNAL_SIDE_EFFECT);
    if (Set.of("vectorDocumentDeleteByDocId", "vectorDocumentDeleteBySource").contains(tool))
      return Set.of(
          ActionCapability.LOCAL_WRITE,
          ActionCapability.NETWORK_WRITE,
          ActionCapability.EXTERNAL_SIDE_EFFECT,
          ActionCapability.DESTRUCTIVE);
    if (Set.of(
            "requestClaudeCodeReview",
            "requestClaudeCodeContinueReview",
            "requestParallelClaudeCodeReviews",
            "requestParallelCodexReviews",
            "requestCodexReview",
            "requestCodexReReview",
            "requestCodexContinueReview")
        .contains(tool))
      return Set.of(
          ActionCapability.READ,
          ActionCapability.EXECUTE,
          ActionCapability.NETWORK_WRITE,
          ActionCapability.EXTERNAL_SIDE_EFFECT);
    if (Set.of("requestClaudeCodeFixProposal", "requestCodexFixProposal").contains(tool))
      return Set.of(
          ActionCapability.READ,
          ActionCapability.LOCAL_WRITE,
          ActionCapability.EXECUTE,
          ActionCapability.NETWORK_WRITE,
          ActionCapability.EXTERNAL_SIDE_EFFECT);
    // Saved local children can only use the audited read/network-read allowlist. Resuming also
    // updates receipts.
    if (Set.of("resumeSubAgent", "resumeSubAgentGraph").contains(tool))
      return Set.of(
          ActionCapability.READ, ActionCapability.NETWORK_READ, ActionCapability.LOCAL_WRITE);
    if (Set.of("askExternalAgent", "requestDotResearch").contains(tool))
      return Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if ("externalAgentStatus".equals(tool)) return Set.of(ActionCapability.READ);
    if (Set.of("searchMemoryHistory", "episodeGet", "episodeSources", "episodeProcessingStatus")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if (Set.of("doctorConnectivity", "doctorInference").contains(tool))
      return Set.of(ActionCapability.NETWORK_READ);
    if (Set.of("doctorCodexVersion", "doctorClaudeVersion").contains(tool))
      return Set.of(ActionCapability.EXECUTE);
    if ("doctorMicrophone".equals(tool))
      return Set.of(ActionCapability.EXECUTE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if ("inspectDocumentRender".equals(tool)) return Set.of(ActionCapability.READ);
    if ("validateDocumentRender".equals(tool))
      return Set.of(ActionCapability.READ, ActionCapability.EXECUTE, ActionCapability.LOCAL_WRITE);
    if (Set.of(
            "inspectTextDocumentChangeSet", "getTextDocumentChangeSetDiff", "getTextChangeSetDiff")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if (Set.of("proposeTextDocumentChangeSet", "discardTextDocumentChangeSet").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE);
    if (Set.of(
            "applyTextDocumentChangeSet",
            "rollbackTextDocumentChangeSet",
            "cleanTextDocumentStaging")
        .contains(tool)) return Set.of(ActionCapability.LOCAL_WRITE, ActionCapability.DESTRUCTIVE);
    if (Set.of(
            "inspectDiagnosedRepair",
            "getPatchRequirementReview",
            "inspectPatchRequirementReview",
            "listPatchRequirementReviews",
            "getGoalCompletionDefinition")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if ("attachGoalCompletionEvidence".equals(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if (Set.of("attachGoalStageEvidence", "proposeGoalStageSplit").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE);
    if ("proposeDiagnosedRepair".equals(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if (Set.of(
            "listDurableSubAgents",
            "getDurableSubAgent",
            "getSubAgentGraph",
            "compareSubAgentAnswers")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if ("reconcileSubAgent".equals(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if (Set.of(
            "getCodexImplementationRequest",
            "getExternalImplementation",
            "inspectExternalImplementation")
        .contains(tool)) return Set.of(ActionCapability.READ);
    if (Set.of("prepareCodexImplementation", "recordCodexAcceptanceEvaluation").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE);
    if ("mergeExternalImplementation".equals(tool))
      return Set.of(ActionCapability.LOCAL_WRITE, ActionCapability.EXECUTE);
    if (Set.of("listClaudeCodeReviews", "getClaudeCodeReview").contains(tool))
      return Set.of(ActionCapability.READ);
    if (READ.contains(tool)) return Set.of(ActionCapability.READ);
    if (NETWORK_READ.contains(tool)) return Set.of(ActionCapability.NETWORK_READ);
    if ("requestCodexAppServerSubAgent".equals(tool))
      return Set.of(
          ActionCapability.EXECUTE,
          ActionCapability.NETWORK_WRITE,
          ActionCapability.EXTERNAL_SIDE_EFFECT);
    if (Set.of("requestChatGptPlanSubAgent", "requestChatGptPlanInference").contains(tool))
      return Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if ("deliverAttention".equals(tool))
      return Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT);
    if ("registerDependency".equals(tool))
      return Set.of(ActionCapability.READ, ActionCapability.LOCAL_WRITE);
    if (LOCAL_WRITE.contains(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if ("searchKnowledge".equals(tool))
      return Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ);
    if (Set.of("deleteFile", "moveFile", "killShellProcess").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE, ActionCapability.DESTRUCTIVE);
    // 任意コマンドや未分類の外部ツールは、参照専用と仮定せず全権限を必要とするものとして扱う。
    return Set.copyOf(EnumSet.allOf(ActionCapability.class));
  }

  /** ツール本来の動作が参照操作だけに収まるかを判定し、並行実行の判断に用いる。 */
  public static boolean intrinsicallyReadOnly(String tool) {
    return intrinsicCapabilities(tool).stream()
        .allMatch(c -> c == ActionCapability.READ || c == ActionCapability.NETWORK_READ);
  }

  /** 対象を判断基準に照らして評価する。 */
  public PermissionDecision evaluate(String tool) {
    if ("httpRequestWrite".equals(tool) && !properties.enabled() && !fullAuto)
      return PermissionDecision.REQUIRE_APPROVAL;
    return evaluateCapabilities(capabilities(tool));
  }

  /** 対象を判断基準に照らして評価する。 */
  public PermissionDecision evaluate(String tool, String input) {
    if ("httpRequestWrite".equals(tool) && !properties.enabled() && !fullAuto)
      return PermissionDecision.REQUIRE_APPROVAL;
    return evaluateCapabilities(capabilities(tool, input));
  }

  /** 判断基準を満たすか確認するため、許可された操作一覧を評価する。 */
  private PermissionDecision evaluateCapabilities(Set<ActionCapability> required) {
    // 権限確認が有効な場合は、禁止設定を自動承認より先に適用する。
    if (!properties.enabled()) return PermissionDecision.AUTO_APPROVE;
    if (required.stream().anyMatch(properties.denied()::contains)) return PermissionDecision.DENY;
    if (fullAuto) return PermissionDecision.AUTO_APPROVE;
    return properties.autoApprove().containsAll(required)
        ? PermissionDecision.AUTO_APPROVE
        : PermissionDecision.REQUIRE_APPROVAL;
  }
}
