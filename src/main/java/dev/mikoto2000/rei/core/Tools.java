package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.filesummary.FileSummaryCache;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.process.BackgroundProcessStatus;
import dev.mikoto2000.rei.core.process.RunCommandRequest;
import dev.mikoto2000.rei.core.process.RunCommandResult;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.recentchanges.RecentChanges;
import dev.mikoto2000.rei.core.relatedgraph.RelatedFileGraph;
import dev.mikoto2000.rei.core.searchcache.SearchResultCache;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.tooldelivery.ReadingDelivery;
import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;
import dev.mikoto2000.rei.tooldelivery.ReadingProblem;
import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/** 共通の実行基盤に必要なツール一覧を扱う。 */
@Component
public class Tools {
  public static void checkReadingCancellation(Throwable error) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
  }

  public static String readingPath(dev.mikoto2000.rei.tooldelivery.ReadingRun owner, String input)
      throws IOException {
    if (!(owner instanceof dev.mikoto2000.rei.core.chat.AgentRunContext captured))
      throw new IOException("Unknown reading authority");
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(captured)) {
      var root = owner.projectRoot().toRealPath();
      return root.relativize(FileSnapshots.resolve(root, java.nio.file.Path.of(input)))
          .toString()
          .replace('\\', '/');
    }
  }

  private ReadFileResult applyReadingDelivery(ReadFileRequest request, ReadFileResult result) {
    var notice =
        readingDelivery.inspect(
            dev.mikoto2000.rei.tooldelivery.ReadingRuntime.current(),
            dev.mikoto2000.rei.tooldelivery.ReadingJson.JSON.valueToTree(result),
            result.symbol() == null
                && !result.truncated()
                && (request.offset() == null || request.offset() == 0),
            Boolean.TRUE.equals(request.unreadOnly())
                && request.startLine() != null
                && request.endLine() != null);
    if (notice == null) return result;
    return new ReadFileResult(
        result.path(),
        result.startLine(),
        result.endLine(),
        notice.suppressed() ? List.of() : result.content(),
        result.truncated(),
        result.error(),
        result.version(),
        result.nextRead(),
        notice.suppressed() ? 0 : result.returnedBytes(),
        result.symbol(),
        result.candidates(),
        result.ownerOverview(),
        result.truncationReason(),
        notice,
        result.selection());
  }

  static {
    dev.mikoto2000.rei.tooldelivery.ReadingRuntime.configure(
        dev.mikoto2000.rei.core.chat.AgentRunScope::current,
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative()::text);
  }

  /** ファイル結果JSON変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper FILE_RESULT_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 配信概算。 */
  private dev.mikoto2000.rei.core.contextbudget.TokenEstimator deliveryEstimator =
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator.tokenizerAware();

  /** 配信設定。 */
  private dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties deliveryProperties =
      new dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties();

  /** 共通の実行基盤に関する設定配信を処理する。 */
  @Autowired
  public void configureDelivery(
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator estimator,
      dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties properties) {
    deliveryEstimator = estimator;
    deliveryProperties = properties;
  }

  /** 文書表示への変換。 */
  private DocumentRendererService documentRenderer;

  /** 共通の実行基盤で使用する文書表示への変換を設定する。 */
  @Autowired(required = false)
  void setDocumentRenderer(DocumentRendererService service) {
    documentRenderer = service;
  }

  /** 利用条件に合わない指定を受け付けないよう、文書表示を検証する。 */
  @Tool(
      description =
          "既定OFFの管理者指定PlantUML rendererで現在の.puml/.plantumlと正確なsourceSha256をPNG検証します。directive/function/resource link禁止、SANDBOX、20秒/4MiB/4096px、exit/parse/画像/size/hashを記録。file Applyはせず、Artifact有効時だけ既存deliveryへ公開。モデルの説明は描画証拠ではありません。")
  DocumentRendererService.Receipt validateDocumentRender(DocumentRendererService.Request request)
      throws IOException {
    if (documentRenderer == null) throw new IllegalStateException("Renderer unavailable");
    return documentRenderer.validate(diagnosedOwner(), request);
  }

  /** 共通の実行基盤で用いる状態確認文書表示を返す。 */
  @Tool(
      description =
          "現在Project/root/sessionの保存renderer receiptを読みます。履歴の描画結果で現在sourceの成功保証ではありません。STARTED/UNKNOWNは自動再実行せず、不足環境は成功扱いにしません。")
  DocumentRendererService.Receipt inspectDocumentRender(String id) throws IOException {
    if (documentRenderer == null) throw new IllegalStateException("Renderer unavailable");
    return documentRenderer.inspect(diagnosedOwner(), id);
  }

  /** 目標完了を判定する窓口。 */
  private dev.mikoto2000.rei.goal.GoalCompletionGate goalCompletionGate;

  /** 目標段階一覧。 */
  private dev.mikoto2000.rei.goal.GoalStageService goalStages;

  /** 共通の実行基盤に関する設定目標段階一覧を処理する。 */
  @Autowired(required = false)
  void configureGoalStages(dev.mikoto2000.rei.goal.GoalStageService stages) {
    goalStages = stages;
  }

  /** 共通の実行基盤に関する関連付け目標段階根拠を処理する。 */
  @Tool(
      description =
          "現在のGoal Stageへ保存Review/Artifact IDとSHAのproofを添付します。捕捉Run/root/sessionを照合し、現在patch/report/Artifact/条件はホストが独立検証します。条件や状態を変更する権限はありません。")
  java.util.Map<String, Object> attachGoalStageEvidence(
      String goalId, dev.mikoto2000.rei.goal.GoalCompletionGate.Proof proof) {
    if (goalStages == null) throw new IllegalStateException("Stages unavailable");
    goalStages.attachRunning(diagnosedOwner(), goalId, proof);
    return goalStages.inspectRunning(diagnosedOwner(), goalId);
  }

  /** 後続の判断で選べる候補を用意するため、目標段階分割を提案する。 */
  @Tool(
      description =
          "独立検証で失敗した現在のGoal Stageの子工程案を保存します。expectedPlanVersionとchildren（id,parentStageId,objective,criteria,dependsOnStageIds,requirementIds,completion）を指定。親とGoal条件は不変。ホストが循環/深さ/数/予算/所有者を検証し、試行が未達の場合だけ採用します。Stageを完了にする権限はありません。")
  void proposeGoalStageSplit(
      String goalId,
      long expectedPlanVersion,
      java.util.List<dev.mikoto2000.rei.goal.GoalStageDefinition> children) {
    if (goalStages == null) throw new IllegalStateException("Stages unavailable");
    goalStages.proposeSplit(diagnosedOwner(), goalId, expectedPlanVersion, children);
  }

  /** 共通の実行基盤で使用する目標完了を判定する窓口を設定する。 */
  @Autowired(required = false)
  void setGoalCompletionGate(dev.mikoto2000.rei.goal.GoalCompletionGate gate) {
    goalCompletionGate = gate;
  }

  /** 共通の実行基盤で参照する目標完了条件定義を返す。 */
  @Tool(
      description =
          "現在Project/root/sessionのGoal completion definitionと保存proofを読みます。READのみ。モデルの完了宣言は証拠ではなく、定義変更やRun起動は行いません。")
  dev.mikoto2000.rei.goal.GoalRepository.Goal getGoalCompletionDefinition(String goalId)
      throws IOException {
    if (goalCompletionGate == null) throw new IllegalStateException("Goal gate unavailable");
    return goalCompletionGate.inspect(diagnosedOwner(), goalId);
  }

  /** 共通の実行基盤で用いる関連付け目標完了条件根拠を返す。 */
  @Tool(
      description =
          "現在の実行中Goal Runへ確認したReview/ArtifactのIDとSHAを添付します。proof={review:{id,sha256},artifacts:[{id,sha256}]}。定義を変更せずcommandも実行しません。現在の捕捉Run/root/session以外は拒否し、完了は独立gateがcurrent patch/report/artifact/predicateを再検証します。")
  dev.mikoto2000.rei.goal.GoalRepository.Goal attachGoalCompletionEvidence(
      String goalId, dev.mikoto2000.rei.goal.GoalCompletionGate.Proof proof) throws IOException {
    if (goalCompletionGate == null) throw new IllegalStateException("Goal gate unavailable");
    return goalCompletionGate.attach(diagnosedOwner(), goalId, proof, false);
  }

  /** 変更差分レビュー一覧。 */
  private SemanticPatchReviewService patchReviews;

  /** 共通の実行基盤で使用する変更差分レビュー一覧を設定する。 */
  @Autowired(required = false)
  void setPatchReviews(SemanticPatchReviewService service) {
    patchReviews = service;
  }

  /** 共通の実行基盤で用いるレビュー変更差分要件一覧を返す。 */
  @Tool(
      description =
          "明示testCommandを実行して要件・変更file・必須JUnit testcase・静的hygieneを検証し、任意の意味Reviewを共有Run予算内で行います。request={testCommand,timeoutSeconds,requirements:[{id,statement,files,tests}],allowedFiles,testReports,semantic}。意味Reviewは既定OFF、確率的評価で正しさの保証ではありません。追加変更・欠落test・skipped・不完全な証拠はFIX_REQUIRED。任意command実行の全能力Policyが必要です。保存receiptのid/sha256を保持してください。")
  SemanticPatchReviewService.Receipt reviewPatchRequirements(
      SemanticPatchReviewService.Request request,
      org.springframework.ai.chat.model.ToolContext context)
      throws IOException {
    var run =
        context == null
            ? null
            : (dev.mikoto2000.rei.core.stagnation.RunExecutionContext)
                context
                    .getContext()
                    .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
    if (run != null) run.checkActive();
    return patchReviews().review(run == null ? diagnosedOwner() : run.runContext(), request, run);
  }

  /** 共通の実行基盤で参照する変更差分要件レビューを返す。 */
  @Tool(
      description =
          "現在Project/root/sessionの要件Review receiptを正確なid/sha256で読みます。読み取りのみ。保存判定はその時点のpatchについての観測で、現在の変更や意味的正しさを保証しません。STARTED/UNKNOWNを自動再実行しません。")
  SemanticPatchReviewService.Receipt getPatchRequirementReview(String id, String sha256)
      throws IOException {
    return patchReviews().get(diagnosedOwner(), id, sha256);
  }

  /** 共通の実行基盤で用いる状態確認変更差分要件レビューを返す。 */
  @Tool(
      description =
          "現在Project/root/sessionのReview実行状態をidだけで検査します。失われたSTARTEDはUNKNOWN、再実行せず成功proofを生成しません。読み取りinspectionです。")
  SemanticPatchReviewService.Inspection inspectPatchRequirementReview(String id)
      throws IOException {
    return patchReviews().inspect(diagnosedOwner(), id);
  }

  /** 利用可能な対象を確認できるよう、変更差分要件レビュー一覧を一覧にする。 */
  @Tool(
      description =
          "現在Project/root/sessionの保存Review状態を最大128件一覧します。STARTED/UNKNOWNのinspection用でcommandを再実行しません。")
  java.util.List<SemanticPatchReviewService.Inspection> listPatchRequirementReviews()
      throws IOException {
    return patchReviews().list(diagnosedOwner());
  }

  /** 共通の実行基盤で用いる変更差分レビュー一覧を返す。 */
  private SemanticPatchReviewService patchReviews() {
    if (patchReviews == null)
      throw new IllegalStateException("Patch requirement review unavailable");
    return patchReviews;
  }

  /** 診断済み修復一覧。 */
  private DiagnosedRepairService diagnosedRepairs;

  /** 共通の実行基盤で使用する診断済み修復一覧を設定する。 */
  @Autowired(required = false)
  void setDiagnosedRepairs(DiagnosedRepairService service) {
    diagnosedRepairs = service;
  }

  /** 後続の判断で選べる候補を用意するため、診断済み修復を提案する。 */
  @Tool(
      description =
          "既定OFFの診断付き修正案を保存します。request={reportPath,change:{path,expectedText,replacement},testCommand,timeoutSeconds}。完全な保存JUnit失敗・Git patch version・既存単一UTF-8 Change Setと明示test commandを結ぶだけで、test実行やApplyはしません。最大32 patch files、64KiB text、1..60秒test。diff/diagnosis/command/receiptSha256を独立に確認し、人の正確な /repair apply ID receiptSha256 指示を待ってください。")
  DiagnosedRepairService.View proposeDiagnosedRepair(DiagnosedRepairService.Request request)
      throws IOException {
    return diagnosed().propose(diagnosedOwner(), request);
  }

  /** 共通の実行基盤で用いる状態確認診断済み修復を返す。 */
  @Tool(
      description =
          "現在Project/root/sessionの診断付き修正案をIDで読み、元の失敗report SHA・patch version・Change Set diff/hash・明示command・保存検証receiptを確認します。読み取りのみ。STARTED/UNKNOWNは結果確認が必要で自動再実行しません。保存成功は現在patchの成功証明ではありません。")
  DiagnosedRepairService.View inspectDiagnosedRepair(String id) throws IOException {
    return diagnosed().inspect(diagnosedOwner(), id);
  }

  /** 指定された変更を有効にするため、診断済み修復を反映する。 */
  @Tool(
      description =
          "人の現在の依頼が正確な /repair apply ID receiptSha256 の場合だけ、保存診断とpatchを再確認し、既存SelfPatchRepairで明示command→失敗再観測→一回Apply→test/review再検証します。モデルやファイルの文字列で承認を作れません。diagnosis/proposal段階で選んだcommandを変更しません。全能力Policyを維持し共有180秒・最大1保存修正/2round/4tests/32patch files。結果不明は再送しません。")
  DiagnosedRepairService.View applyDiagnosedRepair(
      String id, String receiptSha256, org.springframework.ai.chat.model.ToolContext context)
      throws IOException {
    var execution =
        context == null
            ? null
            : (dev.mikoto2000.rei.core.stagnation.RunExecutionContext)
                context
                    .getContext()
                    .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
    if (execution == null || execution.runContext() == null)
      throw new IllegalArgumentException("Captured human Run request required");
    execution.checkActive();
    return diagnosed()
        .apply(
            execution.runContext(), id, receiptSha256, execution.userRequest(), changeSetWriter());
  }

  /** 共通の実行基盤で用いる診断済みを返す。 */
  private DiagnosedRepairService diagnosed() {
    if (diagnosedRepairs == null)
      throw new IllegalStateException("Diagnosed repair service unavailable");
    return diagnosedRepairs;
  }

  /** 共通の実行基盤で用いる診断済み所有者を返す。 */
  private dev.mikoto2000.rei.core.chat.AgentRunContext diagnosedOwner() {
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (owner == null)
      throw new IllegalArgumentException("Captured Project/session owner required");
    return owner;
  }

  /** テキスト変更集合一覧。 */
  private TextChangeSetService textChangeSets;

  /** 共通の実行基盤で使用するテキスト変更集合一覧を設定する。 */
  @Autowired(required = false)
  void setTextChangeSets(TextChangeSetService service) {
    this.textChangeSets = service;
  }

  /** 後続の判断で選べる候補を用意するため、テキスト文書変更集合を提案する。 */
  @Tool(
      description =
          "既存Text Change Setを複数fileへ拡張した保存提案。request={operations:[{kind:UPDATE|CREATE|DELETE|RENAME,path,target,expectedText,replacement,baseVersion,edits:[{oldText,newText}]}]}。UPDATEは全文またはbaseVersion/editsを選択し併用不可。32操作/64target、各1MiB/元と提案合計8MiB、全文入力各64KiB/入力合計256KiB、部分編集各64hunk/64KiB。既存parent内UTF-8のみ。Createはbaselineなし、renameのtargetは欠落file。per-file SHAとwhole proposalSha256/diffを確認し明示Applyしてください。fileは変更しません。")
  TextDocumentChangeSetService.View proposeTextDocumentChangeSet(
      TextDocumentChangeSetService.Request request) throws IOException {
    return changeSets().proposeDocuments(diagnosedOwner(), request);
  }

  /** 共通の実行基盤で用いる状態確認テキスト文書変更集合を返す。 */
  @Tool(
      description =
          "捕捉Project/root/sessionの複数file案、保存状態、各元/提案/現在SHA、現在一致currentMatches、staging journalを読みます。READのみ。APPLIEDは歴史receipt、UNKNOWNは自動再送不可。別sessionを拒否します。")
  TextDocumentChangeSetService.View inspectTextDocumentChangeSet(String id) throws IOException {
    return changeSets().inspectDocuments(diagnosedOwner(), id);
  }

  @Tool(
      description =
          "保存した複数file提案の差分を予算付きで読みます。id/proposalSha256と返却nextOffsetをoffsetへそのまま指定。maxBytesは256..65536。本文は秘密情報をマスク済み。省略ページを確認してから既存Applyへ進みます。READのみ、現在fileは変更しません。")
  TextDiff.Page getTextDocumentChangeSetDiff(
      String id,
      String proposalSha256,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer offset,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxBytes)
      throws IOException {
    return changeSets()
        .documentDiffPage(
            diagnosedOwner(),
            id,
            proposalSha256,
            offset == null ? 0 : offset,
            maxBytes == null ? TextWriteLimits.diffBytes() : maxBytes);
  }

  @Tool(
      description =
          "保存した単一file提案の差分を予算付きで読みます。id/proposalSha256と返却nextOffsetをoffsetへそのまま指定。maxBytesは256..65536。省略情報を確認し必要な全ページを既存Apply前にレビューしてください。READのみ。")
  TextDiff.Page getTextChangeSetDiff(
      String id,
      String proposalSha256,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer offset,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxBytes)
      throws IOException {
    return changeSets()
        .diffPage(
            changeSetProject(),
            id,
            proposalSha256,
            offset == null ? 0 : offset,
            maxBytes == null ? TextWriteLimits.diffBytes() : maxBytes);
  }

  /** 共通の実行基盤で用いる破棄テキスト文書変更集合を返す。 */
  @Tool(description = "保存した未適用の複数file提案を、正確な全体SHAで破棄します。対象fileを変更せず、実行中やUNKNOWNの復旧記録は破棄できません。")
  TextDocumentChangeSetService.View discardTextDocumentChangeSet(String id, String proposalSha256)
      throws IOException {
    return changeSets().discardDocuments(diagnosedOwner(), id, proposalSha256);
  }

  /** 指定された変更を有効にするため、テキスト文書変更集合を反映する。 */
  @Tool(
      description =
          "保存複数file案の正確なid/proposalSha256と全baseline一致を確認し、一回だけlogical transactionでApplyします。全fileの再検証後だけAPPLIED。通常失敗はrollback、外部編集/結果不明はUNKNOWNを保持し自動再送しません。EXCLUSIVE/LOCAL_WRITE/DESTRUCTIVEが必要です。簡潔な結果のcurrentMatches、新SHA、changesの変更範囲は保存bytesの確認です。直後の全文再読は不要ですが構文/意味/testの検証は別途必要です。失敗時はerror.codeとstagingを確認してください。")
  TextDocumentChangeSetService.View applyTextDocumentChangeSet(String id, String proposalSha256)
      throws IOException {
    return changeSets()
        .applyDocuments(
            diagnosedOwner(),
            id,
            proposalSha256,
            changes -> {
              for (var change : changes) {
                var path = diagnosedOwner().projectRoot().resolve(change.path());
                if (change.after() == null) {
                  workingSet.remove(path);
                  recentChanges.record(path.toString(), RecentChanges.OP_DELETE, "deleted");
                  fileSummaryCache.invalidate(path.toString());
                  relatedFileGraph.removeRelationsFor(path.toString());
                  searchResultCache.clear();
                  publishFileDeleted(path);
                } else if (change.before() == null) {
                  workingSet.recordEdit(path);
                  recentChanges.record(path.toString(), RecentChanges.OP_CREATE, "created");
                  fileSummaryCache.invalidate(path.toString());
                  relatedFileGraph.removeRelationsFor(path.toString());
                  searchResultCache.clear();
                  publishFileCreated(path);
                } else recordTextEdit(path);
              }
            });
  }

  /** 共通の実行基盤で用いる取り消しテキスト文書変更集合を返す。 */
  @Tool(
      description =
          "人の正確な /document rollback ID proposalSha256 指示の場合だけ、保存UNKNOWNの元file群をbest effortで復元します。両保存版と違う外部編集は上書きせずUNKNOWN。最大3回の明示復旧、再送Applyなし。実行中processを勝手に復旧しません。")
  TextDocumentChangeSetService.View rollbackTextDocumentChangeSet(
      String id, String proposalSha256, org.springframework.ai.chat.model.ToolContext context)
      throws IOException {
    var run = documentRecoveryRun(context);
    return changeSets().rollbackDocuments(run.runContext(), id, proposalSha256, run.userRequest());
  }

  /** 共通の実行基盤で用いる変更なしテキスト文書反映前の準備領域を返す。 */
  @Tool(
      description =
          "人の正確な /document clean ID proposalSha256 指示で、terminal案の保存journalと一致する自分のstaging fileだけを削除します。変更されたstagingは保存し、target fileを変更しません。")
  TextDocumentChangeSetService.View cleanTextDocumentStaging(
      String id, String proposalSha256, org.springframework.ai.chat.model.ToolContext context)
      throws IOException {
    var run = documentRecoveryRun(context);
    return changeSets()
        .cleanDocumentStaging(run.runContext(), id, proposalSha256, run.userRequest());
  }

  /** 共通の実行基盤で用いる文書復旧実行を返す。 */
  private dev.mikoto2000.rei.core.stagnation.RunExecutionContext documentRecoveryRun(
      org.springframework.ai.chat.model.ToolContext context) {
    var run =
        context == null
            ? null
            : (dev.mikoto2000.rei.core.stagnation.RunExecutionContext)
                context
                    .getContext()
                    .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
    if (run == null || run.runContext() == null)
      throw new IllegalArgumentException("Captured human recovery request required");
    run.checkActive();
    return run;
  }

  /** 必要な内容を参照できるよう、テキスト変更集合基準を読み取る。 */
  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      description =
          "Change Set用に現在Project内の既存UTF-8ファイルを最大64KiB読み、BOM/CRLF/末尾改行を保持した正確なtextとSHA-256を返します。readMultiFileの行表示で失われる改行情報も保持します。返されたtextをproposeTextChangeSetのexpectedTextに使います。提案保存・ファイル変更は行いません。")
  TextChangeSetService.Baseline readTextChangeSetBase(String path) throws IOException {
    var project = changeSetProject();
    var baseline = changeSets().readBase(project, path);
    workingSet.recordRead(project.root().resolve(baseline.path()));
    return baseline;
  }

  /** 後続の判断で選べる候補を用意するため、テキスト変更集合を提案する。 */
  @Tool(
      description =
          "単一UTF-8ファイルのChange Setを提案・保存します。全文方式は{path,expectedText,replacement}。部分編集は{path,baseVersion,edits:[{oldText,newText}]}で最大64 hunk、編集入力合計64KiB、対象と生成後各1MiB、全oldTextを元版に一意に照合し重複範囲・古いSHAを拒否。baseVersionは読み取りのSHA-256。提案は対象fileを変更しません。diff/proposalSha256を確認し、既存の明示Apply/Discardフローを使います。")
  TextChangeSetService.View proposeTextChangeSet(TextChangeSetService.Request request)
      throws IOException {
    return changeSets().propose(changeSetProject(), request);
  }

  /** 共通の実行基盤で用いる状態確認テキスト変更集合を返す。 */
  @Tool(
      description =
          "現在Projectに保存したChange SetをIDで読み、差分・状態・元/提案/現在のSHA-256を確認します。ファイルを変更せず、APPLYING/FAILED_UNCERTAINは自動再実行しません。")
  TextChangeSetService.View inspectTextChangeSet(String id) throws IOException {
    return changeSets().inspect(changeSetProject(), id);
  }

  /** 現在の状態と保存された記録の整合を取るため、テキスト変更集合を照合する。 */
  @Tool(
      description =
          "人の正確な /document reconcile-single ID proposalSha256 指示でのみ、失われた単一file Applyの現在SHAが保存元/提案版に一致することを照合しRECONCILEDへ移します。file変更・Apply再送なし、APPLIEDやtest成功のproofではありません。不一致はUNKNOWNのまま。")
  TextChangeSetService.View reconcileTextChangeSet(
      String id, String proposalSha256, org.springframework.ai.chat.model.ToolContext context)
      throws IOException {
    var run = documentRecoveryRun(context);
    return changeSets().reconcile(run.runContext(), id, proposalSha256, run.userRequest());
  }

  /** 共通の実行基盤で用いる破棄テキスト変更集合を返す。 */
  @Tool(
      description =
          "保存Change Setの正確なID/proposalSha256を確認して、未claimの提案だけを破棄します。ファイルは変更せず、適用中や結果不明の操作を破棄・再実行しません。")
  TextChangeSetService.View discardTextChangeSet(String id, String proposalSha256)
      throws IOException {
    return changeSets().discard(changeSetProject(), id, proposalSha256);
  }

  /** 指定された変更を有効にするため、テキスト変更集合を反映する。 */
  @Tool(
      description =
          "確認したChange Set IDと正確なproposalSha256を指定し、元ファイルhashが一致する保存提案だけを一回適用します。古い内容はSTALE、結果不明は再送拒否。APPLIEDとcurrentMatchesと新SHAは保存bytesの検証です。保存確認だけの全文再読は不要。構文/意味/testは別途検証。歴史receiptと現在版は区別してください。LOCAL_WRITE権限を必要とします。")
  TextChangeSetService.View applyTextChangeSet(String id, String proposalSha256)
      throws IOException {
    return changeSets().apply(changeSetProject(), id, proposalSha256, changeSetWriter());
  }

  /** 共通の実行基盤で用いる変更集合書き込み先を返す。 */
  private TextChangeSetService.Writer changeSetWriter() {
    return (path, oldText, newText) -> {
      TextDocumentTransaction.writeSingle(path, oldText, newText);
      recordTextEdit(path);
    };
  }

  /** 共通の実行基盤で用いる変更集合一覧を返す。 */
  private TextChangeSetService changeSets() {
    if (textChangeSets == null) throw new IllegalStateException("Change Set service unavailable");
    return textChangeSets;
  }

  /** 共通の実行基盤で用いる変更集合プロジェクトを返す。 */
  private dev.mikoto2000.rei.core.project.ProjectContext changeSetProject() {
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (owner != null)
      return new dev.mikoto2000.rei.core.project.ProjectContext(
          owner.projectId(), "", owner.projectRoot());
    var project = projectService == null ? null : projectService.currentContext();
    if (project == null) throw new IllegalArgumentException("Select a Project before editing");
    return project;
  }

  /** 共通の実行基盤で用いるselfレビュー変更差分を返す。 */
  @Tool(
      description =
          "明示されたtestCommandを最大2回実行し、初回test→Git作業ツリーの静的diffチェック→最終testを検証します。request={testCommand,timeoutSeconds}、timeoutは各1〜60秒（既定30）、全体180秒予算。VERIFIED_CHECKSは同じpatchと成功exit・静的チェックの確認であり、意味的正しさの保証ではありません。FIX_REQUIREDなら既存Toolで修正して全サイクルを再実行してください。任意commandを実行するためrunCommandと同等の権限が必要です。")
  SelfPatchReviewService.Result selfReviewPatch(SelfPatchReviewService.Request request)
      throws IOException {
    return new SelfPatchReviewService(systemShellService)
        .verify(currentWorkingDirectory(), request);
  }

  /** 共通の実行基盤で用いるself修復変更差分を返す。 */
  @Tool(
      description =
          "test→静的diff review→保存済み修正案Apply→全サイクル再検証を、明示された1〜3件のChange Setで行います。request={testCommand,timeoutSeconds,repairs:[{id,proposalSha256}]}。各test1〜60秒/共有180秒、最大3修正・8回test。元の失敗・全round・receiptを保持し、patch変更/不完全/timeout/結果不明/進展なしは停止します。追加LLMなし。任意commandと編集を含むためselfReviewPatchと同等の全能力Policyを維持し、意味的正しさは保証しません。")
  SelfPatchRepairService.Result selfRepairPatch(SelfPatchRepairService.Request request)
      throws IOException {
    var project = changeSetProject();
    var saved = changeSets();
    var verification = new SelfPatchReviewService(systemShellService);
    var inspector =
        new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner());
    return new SelfPatchRepairService(
            verification::verify,
            inspector::capture,
            (root, repair, deadline) -> {
              SelfPatchReviewService.remaining(deadline, 1);
              if (!project.root().toRealPath().equals(root))
                throw new IllegalArgumentException("Repair Project root changed");
              var receipt =
                  saved.apply(project, repair.id(), repair.proposalSha256(), changeSetWriter());
              return new SelfPatchRepairService.Receipt(
                  receipt.id(),
                  receipt.status(),
                  receipt.proposedSha256(),
                  receipt.currentSha256());
            })
        .verify(project.root(), request);
  }

  /** 保存と検索の窓口対応表一覧。 */
  private RepositoryMapService repositoryMaps = new RepositoryMapService();

  /** 共通の実行基盤で用いる診断検証レポートを返す。 */
  @Tool(
      description =
          "Project相対pathの保存済みJUnit XMLを読み、実testcaseの失敗/error/skippedと報告count・SHA・更新時刻を返します。pathは単一XML、1MiB/1024case/24診断上限。test実行や現在Run/Goalの成功判定は行いません。partial/warningsとレポートの鮮度を確認してください。")
  TestReportDiagnosisService.Result diagnoseTestReport(String path) throws IOException {
    return new TestReportDiagnosisService().read(currentWorkingDirectory(), path);
  }

  /** 共通の実行基盤で用いる診断検証レポート一覧を返す。 */
  @Tool(
      description =
          "保存済みJUnit XMLをProject内で上限付き発見・複数診断するREAD Tool。directory省略時はMaven target/surefire-reports・failsafe-reportsとGradle build/test-resultsをmodule深さ6まで探します。明示directoryは直下のTEST*.xmlのみ。探索256folder/4096entry/32file、各1MiB/全体10秒、失敗証拠合計24件。各fileのSHA/時刻/報告count/観測countとpartialを返し、合計は解析済みfileのみ（重複実行を除去しません）。test実行や現在Run/Goal成功の判定は行いません。")
  TestReportCollectionDiagnosisService.Result diagnoseTestReports(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String directory)
      throws IOException {
    return new TestReportCollectionDiagnosisService().read(currentWorkingDirectory(), directory);
  }

  /** 共通の実行基盤で用いる変更検証影響を返す。 */
  @Tool(
      description =
          "変更pathからJavaの逆import、TS/JS/Rust/Go/Pythonのheuristic local importとテスト命名候補を探索します。changedFiles省略時はProjectのGitステージ済み・未ステージ・未追跡変更を読み取り取得します（HEAD必須、最大64path）。明示時はProject相対pathを1〜64件、limitは1〜100（既定20）。coverageReportsでJaCoCo/Cobertura XML・LCOVの保存reportを最大8件指定できます。changedLinesはpath/first/last（包括）で最大64range/256line。両変更指定省略時はGit HEAD-to-worktree hunks。coverageは独立したpartialな行観測で現source revisionやtest成功の証明ではありません。未計測/stale/証拠なしを区別し、広い回帰も実施してください。")
  ChangeTestImpactService.Result changeTestImpact(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) List<String> changedFiles,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit,
      @org.springframework.ai.tool.annotation.ToolParam(required = false)
          List<CoverageImpactService.Range> changedLines,
      @org.springframework.ai.tool.annotation.ToolParam(required = false)
          List<String> coverageReports)
      throws IOException {
    var service = new ChangeTestImpactService(repositoryMaps);
    int bounded = limit == null ? 20 : limit;
    if (coverageReports != null || changedLines != null) {
      var reports = coverageReports == null ? List.<String>of() : coverageReports;
      if (changedFiles == null && changedLines == null)
        return service.analyzeGit(currentWorkingDirectory(), bounded, reports);
      var ranges = changedLines == null ? List.<CoverageImpactService.Range>of() : changedLines;
      var paths =
          changedFiles == null
              ? ranges.stream().map(CoverageImpactService.Range::path).distinct().toList()
              : changedFiles;
      return service.analyze(currentWorkingDirectory(), paths, bounded, ranges, reports);
    }
    return changedFiles == null
        ? service.analyzeGit(currentWorkingDirectory(), bounded)
        : service.analyze(currentWorkingDirectory(), changedFiles, bounded);
  }

  /** 共通の実行基盤で用いる変更検証影響を返す。 */
  ChangeTestImpactService.Result changeTestImpact(List<String> changedFiles, Integer limit)
      throws IOException {
    return changeTestImpact(changedFiles, limit, null, null);
  }

  /** 共通の実行基盤で使用する保存と検索の窓口対応表一覧を設定する。 */
  @Autowired
  void setRepositoryMaps(RepositoryMapService service) {
    this.repositoryMaps = service;
  }

  /** 共通の実行基盤で用いる保存と検索の窓口対応表を返す。 */
  @Tool(
      description =
          "Git管理下の構造索引を取得します。Java ASTとTS/JS/Rust/Go/Pythonのbounded heuristic宣言・local import・テスト候補。sourceやbuildを実行しません。language/analysisModeで解析方式を区別し、heuristicは常にpartialでsemantic resolverではありません。summaryは絞り込み前の走査範囲です。queryで表示を絞り込み、limitは1〜100、既定20。partial/warningsを確認してください。")
  RepositoryMapService.View repositoryMap(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String query,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit)
      throws IOException {
    return repositoryMaps.map(currentWorkingDirectory(), query, limit == null ? 20 : limit);
  }

  /** 既定シェルタイムアウトの秒数。 */
  private static final int DEFAULT_SHELL_TIMEOUT_SECONDS = 30;

  /** 最大シェルタイムアウトの秒数。 */
  private static final int MAX_SHELL_TIMEOUT_SECONDS = 600;

  /** CP932に関する値。 */
  private static final Charset CP932 = Charset.forName("windows-31j");

  /** プロジェクトサービス。 */
  private final ProjectService projectService;

  /** システムシェルサービス。 */
  private final SystemShellService systemShellService;

  /** バックグラウンドプロセス管理。 */
  private final BackgroundProcessManager backgroundProcessManager;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 作業中集合。 */
  private final WorkingSet workingSet;

  /** 直近変更一覧。 */
  private final RecentChanges recentChanges;

  /** ファイル要約キャッシュ。 */
  private final FileSummaryCache fileSummaryCache;

  /** 検索結果キャッシュ。 */
  private final SearchResultCache searchResultCache;

  /** 関連ファイル関連図。 */
  private final RelatedFileGraph relatedFileGraph;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools() {
    this(null, new SystemShellService());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(ProjectService projectService) {
    this(projectService, new SystemShellService());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(ProjectService projectService, SystemShellService systemShellService) {
    this(
        projectService,
        systemShellService,
        new BackgroundProcessManager(systemShellService),
        Clock.systemDefaultZone());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager) {
    this(projectService, systemShellService, backgroundProcessManager, Clock.systemDefaultZone());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock) {
    this(projectService, systemShellService, backgroundProcessManager, clock, new WorkingSet());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        new RecentChanges(),
        new FileSummaryCache(),
        new SearchResultCache(),
        new RelatedFileGraph(),
        eventFactory,
        eventPublisher);
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        new RecentChanges());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      RecentChanges recentChanges) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        recentChanges,
        new FileSummaryCache());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      RecentChanges recentChanges,
      FileSummaryCache fileSummaryCache) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        recentChanges,
        fileSummaryCache,
        new SearchResultCache());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      RecentChanges recentChanges,
      FileSummaryCache fileSummaryCache,
      SearchResultCache searchResultCache) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        recentChanges,
        fileSummaryCache,
        searchResultCache,
        new RelatedFileGraph());
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      RecentChanges recentChanges,
      FileSummaryCache fileSummaryCache,
      SearchResultCache searchResultCache,
      RelatedFileGraph relatedFileGraph) {
    this(
        projectService,
        systemShellService,
        backgroundProcessManager,
        clock,
        workingSet,
        recentChanges,
        fileSummaryCache,
        searchResultCache,
        relatedFileGraph,
        null,
        null);
  }

  /** 指定された値や利用先を使って、Toolsを初期化する。 */
  public Tools(
      ProjectService projectService,
      SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager,
      Clock clock,
      WorkingSet workingSet,
      RecentChanges recentChanges,
      FileSummaryCache fileSummaryCache,
      SearchResultCache searchResultCache,
      RelatedFileGraph relatedFileGraph,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.projectService = projectService;
    this.systemShellService = systemShellService;
    this.backgroundProcessManager = backgroundProcessManager;
    this.clock = clock;
    this.workingSet = workingSet;
    this.recentChanges = recentChanges;
    this.fileSummaryCache = fileSummaryCache;
    this.searchResultCache = searchResultCache;
    this.relatedFileGraph = relatedFileGraph;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 外部プログラムを実行します。コマンドと引数を指定して実行します。
   *
   * @param command 実行するコマンド
   * @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
   * @return コマンドの標準出力の内容
   */
  @Tool(
      name = "executeExternalProgram",
      description =
          """
  外部プログラムを実行します。コマンドと引数を指定して実行します。
  @param command 実行するコマンド
  @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
  @return コマンドの標準出力の内容
  """)
  String executeExternalProgram(String command, List<String> args)
      throws IOException, InterruptedException {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command は空にできません");
    }
    if (command.isBlank() || command.contains(" ")) {
      throw new IllegalArgumentException(
          "外部コマンドの指定が不正です。command には curl のような実行ファイル名だけを指定し、引数は args に分けて指定してください。");
    }
    List<String> safeArgs = args == null ? List.of() : args;
    IO.println(String.format("%s コマンドを引数 %s で実行するよ", command, safeArgs));

    List<String> commandLine = new ArrayList<>();
    commandLine.add(command);
    commandLine.addAll(safeArgs);

    ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
    processBuilder.directory(currentWorkingDirectory().toFile());
    processBuilder.redirectErrorStream(true);
    Process process = startProcess(processBuilder);

    var output = readStreamAsync(process.getInputStream());
    try {
      int exitCode = process.waitFor();
      String text = awaitOutput(output);
      IO.println(String.format("%s コマンドは終了コード %d で終了したよ", command, exitCode));
      return text;
    } catch (InterruptedException stop) {
      Thread.currentThread().interrupt();
      throw stop;
    } finally {
      backgroundProcessManager.terminateOwned(process);
      output.cancel(true);
    }
  }

  /** 依頼された操作を進めるため、シェルコマンドを実行する。 */
  ShellCommandResult executeShellCommand(String command, Integer timeoutSeconds)
      throws IOException, InterruptedException {
    return executeShellCommand(command, timeoutSeconds, currentWorkingDirectory());
  }

  /** 依頼された操作を進めるため、シェルコマンドを実行する。 */
  ShellCommandResult executeShellCommand(
      String command, Integer timeoutSeconds, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command は空にできません");
    }

    int effectiveTimeoutSeconds = normalizeShellTimeout(timeoutSeconds);
    String shell = resolveShell(System.getenv(), System.getProperty("os.name"));
    List<String> commandLine = shellCommandLine(shell, command);
    IO.println(String.format("%s でシェルコマンドを実行するよ: %s", shell, command));

    ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
    processBuilder.directory(workingDirectory.toFile());
    Process process = startProcess(processBuilder);

    CompletableFuture<String> stdout = readStreamAsync(process.getInputStream());
    CompletableFuture<String> stderr = readStreamAsync(process.getErrorStream());

    try {
      boolean completed = process.waitFor(effectiveTimeoutSeconds, TimeUnit.SECONDS);
      if (!completed) backgroundProcessManager.terminateOwned(process);
      int exitCode = completed ? process.exitValue() : -1;
      return new ShellCommandResult(exitCode, awaitOutput(stdout), awaitOutput(stderr), !completed);
    } catch (InterruptedException stop) {
      Thread.currentThread().interrupt();
      throw stop;
    } finally {
      backgroundProcessManager.terminateOwned(process);
      stdout.cancel(true);
      stderr.cancel(true);
    }
  }

  /** 出力待機も割り込み可能にし、読み取り失敗を隠さない。 */
  private String awaitOutput(CompletableFuture<String> output)
      throws IOException, InterruptedException {
    try {
      return output.get();
    } catch (java.util.concurrent.ExecutionException failure) {
      throw new IOException("Process output could not be read", failure.getCause());
    }
  }

  /** 起動する Process の所有権を、この呼び出しへ限定する。 */
  Process startProcess(ProcessBuilder builder) throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException("Run cancelled before process start");
    return builder.start();
  }

  /** 指定や設定に応じた対象を利用するため、シェルを確定する。 */
  String resolveShell(Map<String, String> environment, String osName) {
    return systemShellService.resolveShell(environment, osName);
  }

  /** 共通の実行基盤で用いるシェルコマンド行を返す。 */
  List<String> shellCommandLine(String shell, String command) {
    return systemShellService.shellCommandLine(shell, command);
  }

  /** 同じ意味の指定を共通に扱えるよう、シェルタイムアウトの表記をそろえる。 */
  int normalizeShellTimeout(Integer timeoutSeconds) {
    if (timeoutSeconds == null) {
      return DEFAULT_SHELL_TIMEOUT_SECONDS;
    }
    if (timeoutSeconds < 1) {
      return 1;
    }
    return Math.min(timeoutSeconds, MAX_SHELL_TIMEOUT_SECONDS);
  }

  /** 必要な内容を参照できるよう、逐次データ非同期を読み取る。 */
  private CompletableFuture<String> readStreamAsync(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
          } catch (IOException e) {
            throw new IllegalStateException("シェルコマンド出力の読み込みに失敗しました", e);
          }
        });
  }

  /**
   * 共通の実行基盤で受け渡すシェルコマンド結果を表す。
   *
   * @param exitCode 終了コード
   * @param stdout 標準出力
   * @param stderr 標準エラー出力
   * @param timedOut timed出力先
   */
  public record ShellCommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

  /** 依頼された操作を進めるため、コマンドを実行する。 */
  @Tool(
      name = "runCommand",
      description =
          """
  Run a shell command. Use this as the default and primary tool for shell command execution.
  For REST APIs prefer service-specific tools, then httpRequestRead/httpRequestWrite.
  Never use shell HTTP commands to bypass permissions, destination or output limits of another tool.
  File content reads (Get-Content/gc/cat/type/head/tail with file operands) are rejected before execution.
  Use readMultiFile even for one file. For truncated results, pass each returned nextRead unchanged in files;
  never switch to shell to bypass paging or output limits. Use shell for builds, tests, Git and other development commands.
  Arguments require an outer request object: {"request":{"command":"...","executionMode":"auto","timeoutSeconds":30}}.
  Do not place command or executionMode at the top level.
  In auto mode, short-lived commands return normally while long-running commands continue as managed background processes.
  Use executionMode=auto for normal commands.
  Use executionMode=foreground when you explicitly need to wait for completion.
  Use executionMode=background when you explicitly want a managed background process.
  @param request command, optional executionMode (auto/foreground/background), and optional foreground timeoutSeconds
  @return normalized completion or managed-process result, with bounded diagnosis (reported tests/causes/evidence/nextActions). Diagnostic suggestions never execute commands.
  """)
  RunCommandResult runCommand(RunCommandRequest request) throws IOException, InterruptedException {
    return runCommand(request, currentWorkingDirectory(), Duration.ofSeconds(3));
  }

  /** 依頼された操作を進めるため、コマンドを実行する。 */
  RunCommandResult runCommand(
      RunCommandRequest request, java.nio.file.Path workingDirectory, Duration autoWait)
      throws IOException, InterruptedException {
    if (request == null) throw new IllegalArgumentException("request must not be null");
    if (dev.mikoto2000.rei.core.policy.ShellHttpUsage.recognizable(request.command()))
      io.micrometer.core.instrument.Metrics.counter("rei.http.api.shell.calls").increment();
    dev.mikoto2000.rei.core.policy.ShellFileReadArguments.validateCommand(request.command());
    return switch (request.mode()) {
      case FOREGROUND -> foregroundResult(request, workingDirectory);
      case BACKGROUND -> backgroundResult(request, workingDirectory);
      case AUTO -> autoResult(request, workingDirectory, autoWait);
    };
  }

  /** 共通の実行基盤で用いる前面結果を返す。 */
  private RunCommandResult foregroundResult(
      RunCommandRequest request, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    ShellCommandResult result =
        executeShellCommand(request.command(), request.timeoutSeconds(), workingDirectory);
    String status = result.timedOut() || result.exitCode() != 0 ? "failed" : "completed";
    return new RunCommandResult(
        status,
        request.mode().name().toLowerCase(),
        "foreground",
        result.exitCode(),
        result.stdout(),
        result.stderr(),
        null,
        null,
        result.timedOut(),
        result.timedOut() ? "command timed out" : null);
  }

  /** 共通の実行基盤で用いるバックグラウンド結果を返す。 */
  private RunCommandResult backgroundResult(
      RunCommandRequest request, java.nio.file.Path workingDirectory) {
    BackgroundProcessSnapshot snapshot =
        backgroundProcessManager.spawnShell(request.command(), workingDirectory);
    if (snapshot.status() == BackgroundProcessStatus.FAILED) {
      return new RunCommandResult(
          "failed",
          request.mode().name().toLowerCase(),
          "background",
          snapshot.exitCode(),
          joinLines(snapshot.stdout()),
          joinLines(snapshot.stderr()),
          null,
          null,
          false,
          snapshot.message());
    }
    return new RunCommandResult(
        "running",
        request.mode().name().toLowerCase(),
        "background",
        null,
        joinLines(snapshot.stdout()),
        joinLines(snapshot.stderr()),
        snapshot.processId(),
        snapshot.pid(),
        false,
        null);
  }

  /** 共通の実行基盤で用いる自動結果を返す。 */
  private RunCommandResult autoResult(
      RunCommandRequest request, java.nio.file.Path workingDirectory, Duration autoWait)
      throws InterruptedException {
    BackgroundProcessSnapshot started =
        backgroundProcessManager.spawnShell(request.command(), workingDirectory);
    if (started.status() == BackgroundProcessStatus.FAILED) {
      return new RunCommandResult(
          "failed",
          "auto",
          null,
          started.exitCode(),
          joinLines(started.stdout()),
          joinLines(started.stderr()),
          null,
          null,
          false,
          started.message());
    }
    BackgroundProcessSnapshot observed;
    try {
      observed = backgroundProcessManager.await(started.processId(), autoWait);
    } catch (InterruptedException stop) {
      Thread.currentThread().interrupt();
      backgroundProcessManager.cancelOwned(started.processId());
      throw stop;
    }
    if (observed.status() == BackgroundProcessStatus.RUNNING) {
      return new RunCommandResult(
          "running",
          "auto",
          "background",
          null,
          joinLines(observed.stdout()),
          joinLines(observed.stderr()),
          observed.processId(),
          observed.pid(),
          false,
          null);
    }
    backgroundProcessManager.forgetCompleted(started.processId());
    int exitCode = observed.exitCode() == null ? -1 : observed.exitCode();
    return new RunCommandResult(
        exitCode == 0 ? "completed" : "failed",
        "auto",
        "foreground",
        exitCode,
        joinLines(observed.stdout()),
        joinLines(observed.stderr()),
        null,
        null,
        false,
        exitCode == 0 ? null : "command exited with code " + exitCode);
  }

  /** 共通の実行基盤で用いる統合行一覧を返す。 */
  private String joinLines(List<String> lines) {
    return lines == null ? "" : String.join(System.lineSeparator(), lines);
  }

  /** 共通の実行基盤で用いるspawnシェルコマンドを返す。 */
  BackgroundProcessSnapshot spawnShellCommand(String command) {
    return backgroundProcessManager.spawnShell(command, currentWorkingDirectory());
  }

  /** 共通の実行基盤で参照するシェルプロセス状態を返す。 */
  @Tool(
      name = "getShellProcessStatus",
      description =
          """
  Get the status and recent logs of a managed shell process created by runCommand.
  @param processId runCommand が返した logical processId
  @param tailLines 返すログ末尾行数。null の場合は既定値です。
  @return 状態、終了コード、直近の標準出力/標準エラーと根拠付きdiagnosis。実行中の診断は暫定です。
  """)
  BackgroundProcessSnapshot getShellProcessStatus(String processId, Integer tailLines) {
    return backgroundProcessManager.status(processId, tailLines);
  }

  /** 共通の実行基盤で用いる強制終了シェルプロセスを返す。 */
  @Tool(
      name = "killShellProcess",
      description =
          """
  Kill a managed shell process created by runCommand.
  processId に一致する管理対象プロセスと、その子プロセスツリーを graceful に停止し、残った場合は強制終了します。
  @param processId runCommand が返した logical processId
  @return 終了後の状態、終了コード、直近の標準出力/標準エラー
  """)
  BackgroundProcessSnapshot killShellProcess(String processId) {
    return backgroundProcessManager.kill(processId);
  }

  /** 共通の実行基盤で用いるrollDiceを返す。 */
  int rollDice(int x) {
    IO.println(String.format("%d 面サイコロをひとつ振るよ", x));
    return (int) (Math.random() * x) + 1;
  }

  /** 共通の実行基盤で用いる今日を返す。 */
  @Tool(name = "today", description = "今日の日付を yyyy-MM-dd 形式で取得します")
  String today() {
    IO.println("今日の日付を取得するよ");
    return LocalDate.now(clock).toString();
  }

  /** 共通の実行基盤で用いる現在日時を返す。 */
  @Tool(name = "now", description = "現在時刻を ISO-8601 形式で取得します")
  String now() {
    IO.println("現在時刻を取得するよ");
    return OffsetDateTime.now(clock).toString();
  }

  /** 対象を特定できるよう、ファイルを探す。 */
  @Tool(
      name = "findFile",
      description =
          """
      Find files whose file names partially match any supplied keyword, case-insensitively.
      Results matching more keywords rank first. maxResults is an optional integer from 1 to 500;
      omitted or null defaults to 50. .gitignore is respected.
      """)
  List<String> findFile(FindFileRequest request) throws IOException, InterruptedException {
    return findFile(request, currentWorkingDirectory());
  }

  /** 対象を特定できるよう、ファイルを探す。 */
  List<String> findFile(FindFileRequest request, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    List<String> keywords =
        request.keywords().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
            .distinct()
            .toList();
    int maxResults = request.maxResults() == null ? 50 : request.maxResults();
    List<String> candidates = gitLsFiles(List.of(), workingDirectory);
    if (candidates == null) {
      try (var paths =
          Files.find(workingDirectory, 20, (path, attributes) -> attributes.isRegularFile())) {
        candidates =
            paths.map(workingDirectory::relativize).map(java.nio.file.Path::toString).toList();
      }
    }
    return candidates.stream()
        .map(path -> new FileNameMatch(path, matchingKeywordCount(path, keywords)))
        .filter(match -> match.matchCount() > 0)
        .sorted(
            java.util.Comparator.comparingInt(FileNameMatch::matchCount)
                .reversed()
                .thenComparingInt(match -> match.path().length())
                .thenComparing(FileNameMatch::path, String.CASE_INSENSITIVE_ORDER))
        .limit(maxResults)
        .map(FileNameMatch::path)
        .toList();
  }

  /** 対象を特定できるよう、ファイルを探す。 */
  List<String> findFile(String fileName) throws IOException, InterruptedException {
    return findFile(fileName, currentWorkingDirectory());
  }

  /** 対象を特定できるよう、ファイルを探す。 */
  List<String> findFile(String fileName, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    IO.println(String.format("%s のファイルを探すよ（.gitignore を尊重）", fileName));

    List<String> gitListedFiles = gitLsFiles(List.of(), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      return Files.find(
              workingDirectory,
              20,
              (path, basicFileAttribute) -> path.toFile().getAbsolutePath().endsWith(fileName))
          .map(p -> p.toFile().getAbsolutePath())
          .toList();
    }

    return gitListedFiles.stream().filter(s -> s.endsWith(fileName)).collect(Collectors.toList());
  }

  /** 共通の実行基盤で用いるmatching検索語句件数を返す。 */
  private int matchingKeywordCount(String path, List<String> keywords) {
    String fileName =
        java.nio.file.Path.of(path).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return (int) keywords.stream().filter(fileName::contains).count();
  }

  /**
   * 共通の実行基盤で受け渡すファイル名一致を表す。
   *
   * @param path パス
   * @param matchCount 一致件数
   */
  private record FileNameMatch(String path, int matchCount) {}

  /** 利用可能な対象を確認できるよう、ファイルを一覧にする。 */
  @Tool(name = "listFile", description = "ファイル一覧を取得します（.gitignore を尊重）")
  List<String> listFile(String baseDir) throws IOException, InterruptedException {
    return listFile(baseDir, currentWorkingDirectory());
  }

  /** 利用可能な対象を確認できるよう、ファイルを一覧にする。 */
  List<String> listFile(String baseDir, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    var validatedBase =
        FileSnapshots.resolveInventoryPath(workingDirectory, java.nio.file.Path.of(baseDir));
    IO.println(String.format("%s 以下のファイルを一覧にするよ（.gitignore を尊重）", baseDir));

    List<String> gitListedFiles = gitLsFiles(List.of(baseDir), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      java.nio.file.Path resolvedBaseDir = validatedBase;
      if (!Files.exists(resolvedBaseDir)) {
        IO.println(String.format("%s は存在しません", resolvedBaseDir));
        return List.of();
      }
      try (var paths = Files.walk(resolvedBaseDir, 20)) {
        return paths
            .filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            .limit(FileSnapshots.MAX_FILES + 1)
            .map(
                p ->
                    workingDirectory
                        .toAbsolutePath()
                        .normalize()
                        .relativize(p.toAbsolutePath().normalize())
                        .toString()
                        .replace('\\', '/'))
            .toList();
      }
    }

    // git ls-files already applies baseDir as a pathspec. Filtering the returned
    // repository-relative paths again breaks root pathspecs such as ".", because
    // entries like "src/main/App.java" do not start with ".".
    return gitListedFiles;
  }

  /** 共通の実行基盤で用いる内容検索を返す。 */
  List<String> grep(
      String pattern,
      String baseDir,
      Boolean ignoreCase,
      Boolean fixedString,
      Boolean invertMatch,
      Boolean fileNamesOnly,
      Integer beforeContext,
      Integer afterContext,
      Integer maxMatches,
      Boolean includeLineNumber,
      String includeGlob,
      String excludeGlob)
      throws IOException, InterruptedException {
    return grep(
        pattern,
        baseDir,
        ignoreCase,
        fixedString,
        invertMatch,
        fileNamesOnly,
        beforeContext,
        afterContext,
        maxMatches,
        includeLineNumber,
        includeGlob,
        excludeGlob,
        currentWorkingDirectory());
  }

  /** 共通の実行基盤で用いる内容検索を返す。 */
  List<String> grep(String pattern, String baseDir) throws IOException, InterruptedException {
    return grep(pattern, baseDir, currentWorkingDirectory());
  }

  /** 共通の実行基盤で用いる内容検索を返す。 */
  List<String> grep(String pattern, String baseDir, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    return grep(
        pattern,
        baseDir,
        false,
        false,
        false,
        false,
        0,
        0,
        1000,
        true,
        null,
        null,
        workingDirectory);
  }

  /** 共通の実行基盤で用いる内容検索を返す。 */
  List<String> grep(
      String pattern,
      String baseDir,
      Boolean ignoreCase,
      Boolean fixedString,
      Boolean invertMatch,
      Boolean fileNamesOnly,
      Integer beforeContext,
      Integer afterContext,
      Integer maxMatches,
      Boolean includeLineNumber,
      java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    return grep(
        pattern,
        baseDir,
        ignoreCase,
        fixedString,
        invertMatch,
        fileNamesOnly,
        beforeContext,
        afterContext,
        maxMatches,
        includeLineNumber,
        null,
        null,
        workingDirectory);
  }

  /** 共通の実行基盤で用いる内容検索を返す。 */
  List<String> grep(
      String pattern,
      String baseDir,
      Boolean ignoreCase,
      Boolean fixedString,
      Boolean invertMatch,
      Boolean fileNamesOnly,
      Integer beforeContext,
      Integer afterContext,
      Integer maxMatches,
      Boolean includeLineNumber,
      String includeGlob,
      String excludeGlob,
      java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    GrepQuery query =
        new GrepQuery(
            pattern,
            baseDir,
            ignoreCase,
            fixedString,
            invertMatch,
            fileNamesOnly,
            beforeContext,
            afterContext,
            maxMatches,
            includeLineNumber,
            includeGlob,
            excludeGlob);
    List<GrepMatch> matches = grepMatches(query, workingDirectory);
    if (Boolean.TRUE.equals(fileNamesOnly)) {
      return matches.stream().map(GrepMatch::path).distinct().collect(Collectors.toList());
    }
    boolean effectiveIncludeLineNumber = !Boolean.FALSE.equals(includeLineNumber);
    return matches.stream()
        .map(
            m ->
                formatGrepLine(
                    m.path(),
                    m.line(),
                    m.content(),
                    m.matched() ? ":" : "-",
                    effectiveIncludeLineNumber))
        .collect(Collectors.toList());
  }

  /** 1 リクエストあたりの最大 query 数。 */
  static final int MAX_GREP_QUERIES = 20;

  /** 1 query あたりの最大 match 数。 */
  static final int MAX_GREP_MATCHES_PER_QUERY = 1000;

  /** 全 query 合計の最大 match 数。 */
  static final int MAX_GREP_TOTAL_MATCHES = 5000;

  /**
   * 複数の独立した検索条件を 1 回のツール呼び出しで実行する。
   *
   * <p>検索条件が 1 件だけの場合もこのツールを使用する。
   */
  @Tool(
      name = "grepMultiQuery",
      description =
          """
      Search one or more patterns and return structured match locations without reading whole file sections.
      Use this primitive when match locations are sufficient. When locating and inspecting code, prefer searchAndRead.
      @param queries 検索条件のリスト。
      Each query may specify charset; otherwise BOM, strict UTF-8, then CP932 are tried.
      includeGlob/excludeGlob are relative to baseDir. Without a slash, match file names at any depth.
      Use / as separator (backslash is also accepted); **/ includes zero directories.
      Examples: *.java or **/*.java includes direct children and descendants; foo/*.java selects foo's direct children.
      Use **/*.{java,xml} for multiple extensions. Matching is case-sensitive on every platform.
      @return query ごとの結果。queryIndex で入力順と対応し、読めないファイルは fileErrors に含む。

      Example: {"queries":[{"pattern":"class A","baseDir":"src","fixedString":true},{"pattern":"class B","baseDir":"src","fixedString":true}]}
      """
              + dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.BATCHING)
  List<GrepQueryResult> grepMultiQuery(List<GrepQuery> queries)
      throws IOException, InterruptedException {
    return grepMultiQuery(queries, currentWorkingDirectory());
  }

  /** 共通の実行基盤で用いる内容検索複数検索条件を返す。 */
  List<GrepQueryResult> grepMultiQuery(List<GrepQuery> queries, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    try (var measurement = ReadingMetrics.operation("search", readingStateStore)) {
      return boundedFileResult(grepMultiQuery(queries, workingDirectory, newSnapshots()));
    }
  }

  /** 共通の実行基盤で用いる内容検索複数検索条件を返す。 */
  private List<GrepQueryResult> grepMultiQuery(
      List<GrepQuery> queries, java.nio.file.Path workingDirectory, FileSnapshots snapshots)
      throws IOException, InterruptedException {
    return grepMultiQuery(queries, workingDirectory, snapshots, false);
  }

  /** 共通の実行基盤で用いる内容検索複数検索条件を返す。 */
  private List<GrepQueryResult> grepMultiQuery(
      List<GrepQuery> queries,
      java.nio.file.Path workingDirectory,
      FileSnapshots snapshots,
      boolean forSearch)
      throws IOException, InterruptedException {
    if (queries == null || queries.isEmpty()) {
      throw new IllegalArgumentException("queries must not be empty");
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (queries.size() > MAX_GREP_QUERIES) {
      throw new IllegalArgumentException(
          "too many queries: " + queries.size() + " (max " + MAX_GREP_QUERIES + ")");
    }
    for (var query : queries) {
      if (query == null) throw new IllegalArgumentException("query required");
      for (String value :
          new String[] {
            query.pattern(),
            query.baseDir(),
            query.includeGlob(),
            query.excludeGlob(),
            query.charset()
          })
        if (value != null && value.length() > 1024)
          throw new IllegalArgumentException("Search argument exceeds 1024 characters");
    }
    List<GrepQueryResult> results = new ArrayList<>();
    TextReadBudget resultBudget = new TextReadBudget();
    int returnedErrors = 0;
    int totalMatches = 0;
    var scans = SearchScanBatch.scan(this, queries, workingDirectory, snapshots, forSearch);
    for (int i = 0; i < queries.size(); i++) {
      GrepQuery query = queries.get(i);
      try {
        if (scans.get(i).error() != null) throw new IOException(scans.get(i).error());
        GrepScanResult scanResult = scans.get(i).scan();
        if (forSearch) {
          int count = Math.min(32 - returnedErrors, scanResult.fileErrors().size());
          returnedErrors += count;
          results.add(
              new GrepQueryResult(
                  i,
                  query.pattern(),
                  scanResult.matches(),
                  null,
                  scanResult.fileErrors().subList(0, count),
                  scanResult.truncated(),
                  null,
                  scanResult.fileErrors().size() - count));
          continue;
        }
        List<GrepMatch> matches = scanResult.matches();
        int remaining = MAX_GREP_TOTAL_MATCHES - totalMatches;
        if (matches.size() > remaining) {
          matches = matches.subList(0, Math.max(0, remaining));
        }
        var bounded = new ArrayList<GrepMatch>();
        boolean truncated = scanResult.truncated() || matches.size() < scanResult.matches().size();
        ReadFileRequest nextRead = null;
        for (var match : matches) {
          if (!resultBudget.reserve(
              160 + match.path().getBytes(StandardCharsets.UTF_8).length * 6)) {
            truncated = true;
            nextRead =
                new ReadFileRequest(
                    match.path(), match.line(), null, match.version(), 0, query.charset());
            break;
          }
          var slice = resultBudget.take(List.of(match.content()), 0, 1, 0, 1);
          if (!slice.lines().isEmpty())
            bounded.add(
                new GrepMatch(
                    match.path(),
                    match.line(),
                    slice.lines().getFirst(),
                    match.matched(),
                    match.version()));
          if (slice.truncated()) {
            truncated = true;
            nextRead =
                new ReadFileRequest(
                    match.path(),
                    match.line(),
                    null,
                    match.version(),
                    slice.nextOffset(),
                    query.charset());
            break;
          }
        }
        totalMatches += bounded.size();
        if (truncated && nextRead == null && !bounded.isEmpty()) {
          var last = bounded.getLast();
          nextRead =
              new ReadFileRequest(
                  last.path(), last.line() + 1, null, last.version(), 0, query.charset());
        }
        int errorsToReturn = Math.min(32 - returnedErrors, scanResult.fileErrors().size());
        int omittedErrors = scanResult.fileErrors().size() - errorsToReturn;
        returnedErrors += errorsToReturn;
        results.add(
            new GrepQueryResult(
                i,
                query.pattern(),
                bounded,
                null,
                scanResult.fileErrors().subList(0, errorsToReturn),
                truncated || omittedErrors > 0,
                nextRead,
                omittedErrors));
      } catch (IllegalArgumentException | IOException e) {
        results.add(new GrepQueryResult(i, query.pattern(), List.of(), e.getMessage(), List.of()));
      }
    }
    discoverRelationsFromSearch(queries, results, workingDirectory);
    return results;
  }

  /**
   * 検索結果から、明確に確認できるファイル間の関係を Related File Graph に登録する。
   *
   * <p>検索パターンがファイル名（例: UserService）に一致し、ヒットファイルがその名前を含む場合、 ヒットファイルが対象ファイルを参照していると判断できる材料があるとみなす。
   * ファイル名の類似だけでは登録しない。
   */
  private void discoverRelationsFromSearch(
      List<GrepQuery> queries, List<GrepQueryResult> results, java.nio.file.Path workingDirectory) {
    for (int i = 0; i < queries.size(); i++) {
      GrepQuery query = queries.get(i);
      GrepQueryResult result = results.get(i);
      if (result.error() != null) {
        continue;
      }
      String pattern = query.pattern();
      String targetName = pattern.replaceAll("^.*[/\\\\]", "").replaceAll("\\.[^.]+$", "");
      if (targetName.isBlank() || targetName.length() < 2) {
        continue;
      }
      for (GrepMatch match : result.matches()) {
        if (!match.matched()) {
          continue;
        }
        String sourcePath = match.path();
        String sourceName = sourcePath.replaceAll("^.*[/\\\\]", "").replaceAll("\\.[^.]+$", "");
        if (sourceName.equals(targetName)) {
          continue;
        }
        // ヒットファイルが検索対象の名前を含む場合、参照関係とみなす
        relatedFileGraph.addRelation(
            sourcePath,
            pattern,
            RelatedFileGraph.TYPE_REFERENCES,
            RelatedFileGraph.EVIDENCE_SEARCH);
      }
    }
  }

  /** 1 query の検索を実行し、構造化された match のリストを返す。 */
  private List<GrepMatch> grepMatches(GrepQuery query, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    return scanGrepMatches(query, workingDirectory).matches();
  }

  /** 共通の実行基盤で用いる調査内容検索一致結果を返す。 */
  private GrepScanResult scanGrepMatches(GrepQuery query, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    return scanGrepMatches(query, workingDirectory, new FileSnapshots());
  }

  /** 共通の実行基盤で用いる調査内容検索一致結果を返す。 */
  private GrepScanResult scanGrepMatches(
      GrepQuery query, java.nio.file.Path workingDirectory, FileSnapshots snapshots)
      throws IOException, InterruptedException {
    return scanGrepMatches(query, workingDirectory, snapshots, false);
  }

  /** 共通の実行基盤で用いる調査内容検索一致結果を返す。 */
  private GrepScanResult scanGrepMatches(
      GrepQuery query,
      java.nio.file.Path workingDirectory,
      FileSnapshots snapshots,
      boolean forSearch)
      throws IOException, InterruptedException {
    if (query.pattern() == null || query.pattern().isBlank()) {
      throw new IllegalArgumentException("pattern must not be blank");
    }
    if (query.baseDir() == null || query.baseDir().isBlank()) {
      throw new IllegalArgumentException("baseDir must not be blank");
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (query.pattern().length() > 1024 || query.baseDir().length() > 1024)
      throw new IllegalArgumentException("pattern/baseDir limit is 1024 characters");
    boolean effectiveIgnoreCase = Boolean.TRUE.equals(query.ignoreCase());
    boolean effectiveFixedString = Boolean.TRUE.equals(query.fixedString());
    boolean effectiveInvertMatch = Boolean.TRUE.equals(query.invertMatch());
    boolean effectiveFileNamesOnly = Boolean.TRUE.equals(query.fileNamesOnly());
    int effectiveBeforeContext =
        Math.min(1000, Math.max(0, query.beforeContext() == null ? 0 : query.beforeContext()));
    int effectiveAfterContext =
        Math.min(1000, Math.max(0, query.afterContext() == null ? 0 : query.afterContext()));
    int effectiveMaxMatches =
        query.maxMatches() == null || query.maxMatches() <= 0
            ? MAX_GREP_MATCHES_PER_QUERY
            : Math.min(MAX_GREP_MATCHES_PER_QUERY, query.maxMatches());
    Pattern compiled =
        compileGrepPattern(query.pattern(), effectiveIgnoreCase, effectiveFixedString);
    SearchGlob includeMatcher = SearchGlob.compile(query.includeGlob());
    SearchGlob excludeMatcher = SearchGlob.compile(query.excludeGlob());
    java.nio.file.Path globBase =
        workingDirectory.resolve(query.baseDir().replace('\\', '/')).toAbsolutePath().normalize();
    // A concrete file is also an accepted search base; match its name in that case.
    if (Files.isRegularFile(globBase)) globBase = globBase.getParent();
    final java.nio.file.Path globBaseDirectory = globBase;

    List<String> candidates =
        snapshots.inventory(
            List.of(
                query.baseDir(),
                Objects.toString(query.includeGlob(), ""),
                Objects.toString(query.excludeGlob(), "")),
            () ->
                listFile(query.baseDir(), workingDirectory).stream()
                    .filter(
                        path ->
                            matchesGlob(
                                path,
                                includeMatcher,
                                excludeMatcher,
                                workingDirectory,
                                globBaseDirectory))
                    .toList());
    List<GrepMatch> matches = new ArrayList<>();
    List<GrepFileError> fileErrors = new ArrayList<>();
    boolean searchTruncated = false;
    for (String relativePath : candidates) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      if (!matchesGlob(
          relativePath, includeMatcher, excludeMatcher, workingDirectory, globBaseDirectory)) {
        continue;
      }
      java.nio.file.Path filePath = workingDirectory.resolve(relativePath);
      List<String> lines;
      String version;
      try {
        ReadingMetrics.add("scannedFiles", 1);
        var snapshot = snapshots.get(workingDirectory, filePath);
        version = snapshot.version();
        lines =
            snapshots.lines(
                snapshot, query.charset(), () -> snapshotLines(snapshot, query.charset()));
      } catch (IOException ex) {
        String message = String.valueOf(ex.getMessage());
        fileErrors.add(
            new GrepFileError(
                relativePath,
                "Unable to read file: " + message.substring(0, Math.min(512, message.length()))));
        continue;
      }
      Set<Integer> contextLineIndexes = new LinkedHashSet<>();
      int fileMatches = 0;
      for (int i = 0; i < lines.size(); i++) {
        if ((i & 31) == 0) dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
        String line = lines.get(i);
        boolean matched = BoundedRegex.find(compiled, line);
        if (effectiveInvertMatch) {
          matched = !matched;
        }
        if (matched) {
          if (forSearch) {
            matches.add(new GrepMatch(relativePath, i + 1, line, true, version));
            fileMatches++;
            if (effectiveFileNamesOnly) break;
            if (fileMatches >= Math.min(4, effectiveMaxMatches)) {
              searchTruncated |= i + 1 < lines.size();
              break;
            }
            continue;
          }
          if (effectiveFileNamesOnly) {
            matches.add(new GrepMatch(relativePath, i + 1, line, true, version));
            break;
          }
          int from = Math.max(0, i - effectiveBeforeContext);
          int to = Math.min(lines.size() - 1, i + effectiveAfterContext);
          for (int contextIndex = from; contextIndex <= to; contextIndex++) {
            contextLineIndexes.add(contextIndex);
            if (contextLineIndexes.size() >= effectiveMaxMatches) break;
          }
          if (contextLineIndexes.size() >= effectiveMaxMatches) break;
        }
      }

      if (!effectiveFileNamesOnly && !forSearch) {
        for (Integer lineIndex : contextLineIndexes) {
          matches.add(
              new GrepMatch(
                  relativePath,
                  lineIndex + 1,
                  lines.get(lineIndex),
                  isMatchedLine(compiled, lines.get(lineIndex), effectiveInvertMatch),
                  version));
          if (matches.size() >= effectiveMaxMatches) {
            return new GrepScanResult(matches, fileErrors, true);
          }
        }
      }
      if (!forSearch && matches.size() >= effectiveMaxMatches) {
        return new GrepScanResult(matches, fileErrors, true);
      }
    }
    return new GrepScanResult(matches, fileErrors, searchTruncated);
  }

  /**
   * 1 つの grep 検索条件。grep ツールのパラメータと 1:1 対応する。
   *
   * @param pattern 照合規則
   * @param baseDir 基準Dir
   * @param ignoreCase 無視する対象事例
   * @param fixedString 固定文字列表現
   * @param invertMatch invert一致
   * @param fileNamesOnly ファイル名前一覧限定
   * @param beforeContext 変更前文脈
   * @param afterContext 変更後文脈
   * @param maxMatches 最大一致結果
   * @param includeLineNumber 含める対象行数値
   * @param includeGlob 含める対象パスの照合規則
   * @param excludeGlob 除外する対象パスの照合規則
   * @param charset 文字コード
   */
  public record GrepQuery(
      String pattern,
      String baseDir,
      Boolean ignoreCase,
      Boolean fixedString,
      Boolean invertMatch,
      Boolean fileNamesOnly,
      Integer beforeContext,
      Integer afterContext,
      Integer maxMatches,
      Boolean includeLineNumber,
      String includeGlob,
      String excludeGlob,
      String charset) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public GrepQuery(
        String pattern,
        String baseDir,
        Boolean ignoreCase,
        Boolean fixedString,
        Boolean invertMatch,
        Boolean fileNamesOnly,
        Integer beforeContext,
        Integer afterContext,
        Integer maxMatches,
        Boolean includeLineNumber,
        String includeGlob,
        String excludeGlob) {
      this(
          pattern,
          baseDir,
          ignoreCase,
          fixedString,
          invertMatch,
          fileNamesOnly,
          beforeContext,
          afterContext,
          maxMatches,
          includeLineNumber,
          includeGlob,
          excludeGlob,
          null);
    }
  }

  /**
   * 1 行の grep 検索結果。
   *
   * @param path パス
   * @param line 行
   * @param content 内容
   * @param matched 一致した
   * @param version 形式のバージョン
   */
  public record GrepMatch(String path, int line, String content, boolean matched, String version) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public GrepMatch(String path, int line, String content, boolean matched) {
      this(path, line, content, matched, null);
    }
  }

  /**
   * 1 query の検索結果。queryIndex は入力順と対応する。
   *
   * @param path パス
   * @param message メッセージ
   */
  public record GrepFileError(String path, String message) {}

  /**
   * 共通の実行基盤で受け渡す内容検索調査結果を表す。
   *
   * @param matches 一致結果
   * @param fileErrors ファイルエラー一覧
   * @param truncated 省略の有無
   */
  record GrepScanResult(
      List<GrepMatch> matches, List<GrepFileError> fileErrors, boolean truncated) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    GrepScanResult(List<GrepMatch> matches, List<GrepFileError> fileErrors) {
      this(matches, fileErrors, false);
    }
  }

  /**
   * 共通の実行基盤で受け渡す内容検索検索条件結果を表す。
   *
   * @param queryIndex 検索条件位置
   * @param pattern 照合規則
   * @param matches 一致結果
   * @param error エラー
   * @param fileErrors ファイルエラー一覧
   * @param truncated 省略の有無
   * @param nextRead 次読み取り
   * @param omittedErrors omittedエラー一覧
   */
  public record GrepQueryResult(
      int queryIndex,
      String pattern,
      List<GrepMatch> matches,
      String error,
      List<GrepFileError> fileErrors,
      boolean truncated,
      ReadFileRequest nextRead,
      int omittedErrors) {
    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public GrepQueryResult(
        int queryIndex,
        String pattern,
        List<GrepMatch> matches,
        String error,
        List<GrepFileError> fileErrors) {
      this(queryIndex, pattern, matches, error, fileErrors, false, null, 0);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public GrepQueryResult(int queryIndex, String pattern, List<GrepMatch> matches, String error) {
      this(queryIndex, pattern, matches, error, List.of());
    }
  }

  /** 1 リクエストあたりの最大 query 数。 */
  static final int MAX_SEARCH_AND_READ_QUERIES = 20;

  /** 1 リクエストあたりの最大読み込みファイル数。 */
  static final int MAX_SEARCH_AND_READ_FILES = 8;

  /** 1 ファイルあたりの最大読み込み行数。 */
  static final int MAX_SEARCH_AND_READ_LINES_PER_FILE = 1000;

  /** リクエスト全体の最大読み込み行数。 */
  static final int MAX_SEARCH_AND_READ_TOTAL_LINES = 5000;

  /** デフォルトの前後コンテキスト行数。 */
  static final int DEFAULT_SEARCH_AND_READ_CONTEXT_LINES = 25;

  /**
   * 検索と周辺コードの読み込みを 1 回のツール呼び出しで完了する。
   *
   * <p>検索結果から対象ファイルを選び、ヒット行の前後を読み込んでまとめて返す。
   */
  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      name = "searchAndRead",
      resultConverter = SearchReadResultConverter.class,
      description =
          """
      Search for one or more code patterns and immediately read the relevant surrounding source code in a single tool call.
      Use this as the default tool for locating and inspecting code or text when exact file locations are not known.
      Use grepMultiQuery when only match locations are needed, or readMultiFile when file paths are already known.
      @param queries 検索条件のリスト。
      includeGlob/excludeGlob are relative to baseDir; patterns without a slash match file names at any depth.
      **/ includes zero directories. Use / as separator (backslash accepted), e.g. **/*.{java,xml}.
      Glob matching is case-sensitive on every platform.
      @param contextLines ヒット行の前後何行を取得するか。デフォルト 25。
      @param maxFiles 読み込む最大ファイル数。デフォルト 8。
      @return ファイルごとの検索結果と読み込んだコンテキスト。
      """)
  List<SearchAndReadResult> searchAndRead(SearchAndReadRequest request)
      throws IOException, InterruptedException {
    return searchAndRead(request, currentWorkingDirectory());
  }

  /** 依頼に関連する情報を得るため、および読み取りを検索する。 */
  List<SearchAndReadResult> searchAndRead(
      SearchAndReadRequest request, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    try (var measurement = ReadingMetrics.operation("search", readingStateStore)) {
      if (request == null) {
        throw new IllegalArgumentException("request must not be null");
      }
      if (request.queries() == null || request.queries().isEmpty()) {
        throw new IllegalArgumentException("queries must not be empty");
      }
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (request.queries().size() > MAX_GREP_QUERIES) {
        throw new IllegalArgumentException(
            "too many queries: " + request.queries().size() + " (max " + MAX_GREP_QUERIES + ")");
      }
      int byteLimit = request.maxBytes() == null ? TextReadBudget.MAX_BYTES : request.maxBytes();
      int tokenLimit = request.maxTokens() == null ? 8000 : request.maxTokens();
      int lineLimit =
          request.maxLines() == null ? MAX_SEARCH_AND_READ_TOTAL_LINES : request.maxLines();
      if (byteLimit < 256
          || byteLimit > TextReadBudget.MAX_BYTES
          || tokenLimit < 256
          || tokenLimit > 32000
          || lineLimit < 1
          || lineLimit > MAX_SEARCH_AND_READ_TOTAL_LINES)
        throw new IllegalArgumentException(
            "Search budget: bytes 256..65536, estimated tokens 256..32000, lines 1..5000");
      if (dev.mikoto2000.rei.core.chat.AgentRunScope.current() != null)
        tokenLimit = Math.min(tokenLimit, deliveryProperties.getPageTargetTokens());
      List<String> preferred = new ArrayList<>();
      if (request.preferredPaths() != null) {
        if (request.preferredPaths().size() > 20)
          throw new IllegalArgumentException("At most 20 preferred paths");
        var root = workingDirectory.toAbsolutePath().normalize();
        for (String value : request.preferredPaths()) {
          if (value == null || value.length() > 1024)
            throw new IllegalArgumentException("Preferred path limit exceeded");
          var path = root.resolve(value).normalize();
          if (!path.startsWith(root) || RepositoryMapService.sensitive(root.relativize(path)))
            throw new IllegalArgumentException("Outside/excluded preferred path");
          preferred.add(root.relativize(path).toString().replace('\\', '/'));
        }
      }
      int effectiveContextLines =
          request.contextLines() == null || request.contextLines() < 0
              ? DEFAULT_SEARCH_AND_READ_CONTEXT_LINES
              : Math.min(1000, request.contextLines());
      int effectiveMaxFiles =
          request.maxFiles() == null || request.maxFiles() <= 0
              ? MAX_SEARCH_AND_READ_FILES
              : Math.min(MAX_SEARCH_AND_READ_FILES, request.maxFiles());
      effectiveMaxFiles =
          Math.min(effectiveMaxFiles, Math.min(lineLimit, Math.max(1, tokenLimit / 256)));

      String observableQuery =
          request.queries().stream().map(GrepQuery::pattern).collect(Collectors.joining(" | "));
      WorkingSet.SearchObservation searchObservation =
          workingSet.beginSearch(observableQuery, "searchAndRead");

      // 1. 検索を実行する（grepMultiQuery と同じ共通ロジックを再利用）
      try {
        FileSnapshots snapshots = newSnapshots();
        List<GrepQueryResult> queryResults =
            grepMultiQuery(request.queries(), workingDirectory, snapshots, true);

        // 2. ファイルごとにヒットをまとめる（重複排除、queryIndex と line を保持）
        LinkedHashMap<String, List<SearchMatch>> fileMatches = new LinkedHashMap<>();
        LinkedHashMap<String, String> fileCharsets = new LinkedHashMap<>();
        LinkedHashMap<String, String> fileErrors = new LinkedHashMap<>();
        for (GrepQueryResult queryResult : queryResults) {
          if (queryResult.error() != null) {
            fileErrors.put("(query " + queryResult.queryIndex() + ")", queryResult.error());
            continue;
          }
          if (queryResult.omittedErrors() > 0)
            fileErrors.put(
                "(query " + queryResult.queryIndex() + " omitted errors)",
                queryResult.omittedErrors() + " file errors omitted; narrow the search");
          for (GrepFileError fileError : queryResult.fileErrors()) {
            fileErrors.putIfAbsent(fileError.path(), fileError.message());
          }
          for (GrepMatch match : queryResult.matches()) {
            if (!match.matched()) {
              continue;
            }
            fileMatches
                .computeIfAbsent(match.path(), k -> new ArrayList<>())
                .add(new SearchMatch(queryResult.queryIndex(), match.line(), match.content()));
            fileCharsets.putIfAbsent(
                match.path(), request.queries().get(queryResult.queryIndex()).charset());
          }
        }
        var ranked =
            SearchRanking.rank(
                fileMatches,
                request.queries(),
                preferred,
                workingDirectory,
                snapshots,
                repositoryMaps,
                workingSet);
        Map<String, SearchRanking.Ranked> ranking = new LinkedHashMap<>();
        fileMatches.clear();
        for (var item : ranked) {
          fileMatches.put(item.path(), item.matches());
          ranking.put(item.path(), item);
        }
        int selectedCount = Math.min(fileMatches.size(), effectiveMaxFiles);
        int byteShare = (byteLimit - 16) / Math.max(1, selectedCount),
            tokenShare = (tokenLimit - 16) / Math.max(1, selectedCount),
            lineShare = lineLimit / Math.max(1, selectedCount);
        int alreadyPresentCount =
            fileMatches.keySet().stream()
                .limit(selectedCount)
                .map(path -> resolveProjectPath(path, workingDirectory))
                .mapToInt(path -> workingSet.contains(path) ? 1 : 0)
                .sum();

        // 3. ファイルを選び、ヒット行の前後を読み込む
        List<SearchAndReadResult> fileResults = new ArrayList<>();
        int totalLines = 0;
        boolean filesTruncated = queryResults.stream().anyMatch(GrepQueryResult::truncated);
        for (var entry : fileMatches.entrySet()) {
          if (fileResults.size() >= effectiveMaxFiles) {
            filesTruncated = true;
            break;
          }
          try {
            SearchAndReadResult fileResult =
                readFileSections(
                    entry.getKey(),
                    entry.getValue(),
                    effectiveContextLines,
                    fileCharsets.get(entry.getKey()),
                    workingDirectory,
                    snapshots,
                    Math.min(lineShare, Math.max(0, lineLimit - totalLines)),
                    new TextReadBudget(byteShare));
            var rank = ranking.get(entry.getKey());
            fileResult = fileResult.withRanking(rank.score(), rank.matchedBy());
            fileResult =
                SearchResultBudget.fit(
                    fileResult, byteShare, tokenShare, fileCharsets.get(entry.getKey()));
            int remaining = MAX_SEARCH_AND_READ_TOTAL_LINES - totalLines;
            if (fileResult.totalLines() > remaining) {
              fileResult = fileResult.withTruncated(true);
            }
            totalLines += fileResult.totalLines();
            fileResults.add(fileResult);
          } catch (IllegalArgumentException | IOException e) {
            fileResults.add(
                new SearchAndReadResult(
                    entry.getKey(), List.of(), List.of(), e.getMessage(), true, filesTruncated));
          }
        }
        if (filesTruncated) {
          for (int i = 0; i < fileResults.size(); i++) {
            SearchAndReadResult r = fileResults.get(i);
            fileResults.set(
                i,
                new SearchAndReadResult(
                    r.path(),
                    r.matches(),
                    r.sections(),
                    r.error(),
                    r.truncated(),
                    true,
                    r.version(),
                    r.nextRead(),
                    r.score(),
                    r.matchedBy(),
                    r.omittedMatches()));
          }
        }
        for (var entry : fileErrors.entrySet()) {
          if (!fileMatches.containsKey(entry.getKey())) {
            fileResults.add(
                new SearchAndReadResult(
                    entry.getKey(), List.of(), List.of(), entry.getValue(), false, filesTruncated));
          }
        }
        int hitCount =
            queryResults.stream()
                .filter(result -> result.error() == null)
                .mapToInt(
                    result -> (int) result.matches().stream().filter(GrepMatch::matched).count())
                .sum();
        FileResultBudget.require(fileResults, byteLimit, tokenLimit);
        workingSet.completeSearch(
            searchObservation, hitCount, fileMatches.size(), selectedCount, alreadyPresentCount);
        return boundedFileResult(
            Boolean.TRUE.equals(request.compactOutput())
                ? fileResults.stream().map(SearchAndReadResult::withCompact).toList()
                : fileResults);
      } catch (IOException | InterruptedException | RuntimeException e) {
        workingSet.abandonSearch(searchObservation);
        throw e;
      }
    }
  }

  private ReadingStateStore readingStateStore;
  private ReadingDelivery readingDelivery;

  @Autowired
  void setReadingDelivery(ReadingDelivery delivery) {
    readingDelivery = delivery;
  }

  @Autowired
  void setReadingStateStore(ReadingStateStore store) {
    readingStateStore = store;
  }

  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      name = "searchFilePage",
      description =
          "Search with persistent RESULT and SCAN cursors. Start with queries, then pass the returned resultCursor or scanCursor unchanged as cursor. Drains exact hit neighborhoods without intervening source. Run-scoped, expires after 24 hours. File changes invalidate the cursor. maxTokens 256..8000, maxBytes 1024..65536, scanLimit 1..1024, maxLines 1..5000. Queries in one search must use the same charset. Legacy searchAndRead remains available for ranked one-shot searches.")
  PagedFileSearch.Page searchFilePage(PagedFileSearch.Request request) {
    if (readingStateStore == null)
      throw new IllegalStateException("Reading state store unavailable");
    return new PagedFileSearch(this, readingStateStore).read(request, currentWorkingDirectory());
  }

  /** 1 ファイルのヒットから、マージした範囲を読み込む。 */
  private SearchAndReadResult readFileSections(
      String relativePath,
      List<SearchMatch> matches,
      int contextLines,
      String charset,
      java.nio.file.Path workingDirectory,
      FileSnapshots snapshots,
      int lineBudget,
      TextReadBudget budget)
      throws IOException {
    java.nio.file.Path filePath = resolveProjectPath(relativePath, workingDirectory);
    var snapshot = snapshots.get(workingDirectory, filePath);
    List<String> lines = snapshots.lines(snapshot, charset, () -> snapshotLines(snapshot, charset));
    workingSet.recordRead(filePath);

    // ヒット行から前後コンテキストを計算し、overlapping / adjacent をマージする
    List<int[]> ranges = new ArrayList<>();
    for (SearchMatch match : matches) {
      int from = Math.max(1, match.line() - contextLines);
      int to = Math.min(lines.size(), match.line() + contextLines);
      ranges.add(new int[] {from, to});
    }
    ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
    List<int[]> merged = new ArrayList<>();
    for (int[] range : ranges) {
      if (merged.isEmpty() || range[0] > merged.get(merged.size() - 1)[1] + 1) {
        merged.add(new int[] {range[0], range[1]});
      } else {
        int[] last = merged.get(merged.size() - 1);
        last[1] = Math.max(last[1], range[1]);
      }
    }

    List<ReadSection> sections = new ArrayList<>();
    int totalLines = 0;
    boolean truncated = false;
    ReadFileRequest nextRead = null;
    for (int[] range : merged) {
      int fromIndex = range[0] - 1;
      int toIndex = Math.min(range[1], lines.size());
      int remaining = Math.min(MAX_SEARCH_AND_READ_LINES_PER_FILE, lineBudget) - totalLines;
      var slice = budget.take(lines, fromIndex, toIndex, 0, Math.max(0, remaining));
      List<String> content = slice.lines();
      truncated |= slice.truncated();
      totalLines += content.size();
      if (!content.isEmpty())
        sections.add(
            new ReadSection(range[0], range[0] + content.size() - 1, content, slice.truncated()));
      if (slice.truncated()) {
        nextRead =
            new ReadFileRequest(
                relativePath,
                slice.nextLine(),
                merged.getLast()[1],
                snapshot.version(),
                slice.nextOffset(),
                charset);
        break;
      }
    }
    var boundedMatches = new ArrayList<SearchMatch>();
    for (var match : matches) {
      var slice = budget.take(List.of(match.content()), 0, 1, 0, 1);
      if (!slice.lines().isEmpty())
        boundedMatches.add(
            new SearchMatch(match.queryIndex(), match.line(), slice.lines().getFirst()));
      if (slice.truncated()) truncated = true;
    }
    return new SearchAndReadResult(
        relativePath,
        boundedMatches,
        sections,
        null,
        truncated,
        false,
        snapshot.version(),
        nextRead);
  }

  /** 新たな対象を利用できるよう、状態の記録一覧を作成する。 */
  FileSnapshots newSnapshots() {
    return new FileSnapshots();
  }

  /**
   * 共通の実行基盤で受け渡すレビュー状態の記録を表す。
   *
   * <p>Exact decoded source for coverage reviews; uses the existing Project/MCP path boundary.
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param text テキスト
   */
  public record ReviewSnapshot(String path, String version, String text) {}

  /** 共通の実行基盤で用いるレビュー状態の記録を返す。 */
  public ReviewSnapshot reviewSnapshot(String requested) throws IOException {
    var root = currentWorkingDirectory();
    var snapshot = newSnapshots().get(root, resolveProjectPath(requested, root));
    byte[] bytes = snapshot.bytes();
    String text;
    if (snapshot
        .path()
        .getFileName()
        .toString()
        .toLowerCase(java.util.Locale.ROOT)
        .endsWith(".pdf")) {
      try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
        var parts = new java.util.ArrayList<String>();
        var stripper = new org.apache.pdfbox.text.PDFTextStripper();
        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          var part = stripper.getText(pdf);
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (part.length() > 8 * 1024 * 1024
              || parts.stream().mapToLong(String::length).sum() + part.length() > 8 * 1024 * 1024)
            throw new IOException("PDF extracted text capacity exceeded");
          parts.add(part);
        }
        text = String.join("\f", parts);
      }
    } else {
      java.nio.charset.Charset charset = StandardCharsets.UTF_8;
      if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254)
        charset = StandardCharsets.UTF_16LE;
      else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)
        charset = StandardCharsets.UTF_16BE;
      try {
        text = decodeStrict(bytes, charset);
      } catch (java.nio.charset.CharacterCodingException invalid) {
        if (!charset.equals(StandardCharsets.UTF_8)) throw invalid;
        text = decodeStrict(bytes, CP932);
      }
      if (text.indexOf(0) >= 0) throw new IOException("Binary file is unsupported");
    }
    var canonical = FileSnapshots.resolve(root, snapshot.path()).toRealPath();
    if (!canonical.startsWith(root.toRealPath())) throw new IOException("Outside Project path");
    return new ReviewSnapshot(
        root.toRealPath().relativize(canonical).toString().replace('\\', '/'),
        snapshot.version(),
        text);
  }

  /** 共通の実行基盤で用いる状態の記録行一覧を返す。 */
  List<String> snapshotLines(FileSnapshots.Snapshot snapshot, String charset) throws IOException {
    ReadingMetrics.add("decodes", 1);
    byte[] bytes = snapshot.bytes();
    ResolvedTextFile decoded;
    if (charset != null && !charset.isBlank())
      decoded =
          new ResolvedTextFile(
              stripBom(decodeStrict(bytes, resolveCharset(charset))), resolveCharset(charset));
    else {
      decoded = decodeBom(bytes);
      if (decoded == null) {
        try {
          decoded =
              new ResolvedTextFile(
                  decodeStrict(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (CharacterCodingException error) {
          decoded = new ResolvedTextFile(decodeStrict(bytes, CP932), CP932);
        }
      }
    }
    if (decoded.content().indexOf(0) >= 0) throw new IOException("Binary file is unsupported");
    ReadingMetrics.add("lineSplits", 1);
    return decoded.content().lines().toList();
  }

  /**
   * 1 リクエストの検索と読み込み要求。
   *
   * @param queries 検索条件一覧
   * @param contextLines 文脈行一覧
   * @param maxFiles 最大ファイル一覧
   * @param maxBytes バイト数の上限
   * @param maxTokens トークン数の上限
   * @param maxLines 最大行一覧
   * @param preferredPaths preferredパス一覧
   */
  public record SearchAndReadRequest(
      List<GrepQuery> queries,
      Integer contextLines,
      Integer maxFiles,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxBytes,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxTokens,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxLines,
      @org.springframework.ai.tool.annotation.ToolParam(required = false)
          List<String> preferredPaths,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Boolean compactOutput) {
    public SearchAndReadRequest {
      queries =
          queries == null ? null : java.util.Collections.unmodifiableList(new ArrayList<>(queries));
      preferredPaths =
          preferredPaths == null
              ? null
              : java.util.Collections.unmodifiableList(new ArrayList<>(preferredPaths));
    }

    public SearchAndReadRequest(
        List<GrepQuery> queries,
        Integer contextLines,
        Integer maxFiles,
        Integer maxBytes,
        Integer maxTokens,
        Integer maxLines,
        List<String> preferredPaths) {
      this(queries, contextLines, maxFiles, maxBytes, maxTokens, maxLines, preferredPaths, null);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public SearchAndReadRequest(List<GrepQuery> queries, Integer contextLines, Integer maxFiles) {
      this(queries, contextLines, maxFiles, null, null, null, null);
    }
  }

  /**
   * 1 ファイルの検索ヒット。
   *
   * @param queryIndex 検索条件位置
   * @param line 行
   * @param content 内容
   */
  public record SearchMatch(int queryIndex, int line, String content) {}

  /**
   * 読み込んだ 1 セクション。
   *
   * @param startLine 開始行
   * @param endLine 終了行
   * @param content 内容
   * @param truncated 省略の有無
   */
  public record ReadSection(int startLine, int endLine, List<String> content, boolean truncated) {}

  /**
   * 1 ファイルの検索と読み込み結果。
   *
   * @param path パス
   * @param matches 一致結果
   * @param sections 節一覧
   * @param error エラー
   * @param truncated 省略の有無
   * @param filesTruncated ファイル一覧省略の有無
   * @param version 形式のバージョン
   * @param nextRead 次読み取り
   * @param score 評価値
   * @param matchedBy 一致した別
   * @param omittedMatches omitted一致結果
   */
  public record SearchAndReadResult(
      String path,
      List<SearchMatch> matches,
      List<ReadSection> sections,
      String error,
      boolean truncated,
      boolean filesTruncated,
      String version,
      ReadFileRequest nextRead,
      int score,
      List<String> matchedBy,
      int omittedMatches,
      @com.fasterxml.jackson.annotation.JsonIgnore boolean compact) {
    public SearchAndReadResult {
      matches = matches == null ? null : List.copyOf(matches);
      sections = sections == null ? null : List.copyOf(sections);
      matchedBy = matchedBy == null ? null : List.copyOf(matchedBy);
    }

    public SearchAndReadResult(
        String path,
        List<SearchMatch> matches,
        List<ReadSection> sections,
        String error,
        boolean truncated,
        boolean filesTruncated,
        String version,
        ReadFileRequest nextRead,
        int score,
        List<String> matchedBy,
        int omittedMatches) {
      this(
          path,
          matches,
          sections,
          error,
          truncated,
          filesTruncated,
          version,
          nextRead,
          score,
          matchedBy,
          omittedMatches,
          false);
    }

    SearchAndReadResult withCompact() {
      return new SearchAndReadResult(
          path,
          matches,
          sections,
          error,
          truncated,
          filesTruncated,
          version,
          nextRead,
          score,
          matchedBy,
          omittedMatches,
          true);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public SearchAndReadResult(
        String path,
        List<SearchMatch> matches,
        List<ReadSection> sections,
        String error,
        boolean truncated,
        boolean filesTruncated) {
      this(path, matches, sections, error, truncated, filesTruncated, null, null, 0, List.of(), 0);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public SearchAndReadResult(
        String path,
        List<SearchMatch> matches,
        List<ReadSection> sections,
        String error,
        boolean truncated,
        boolean filesTruncated,
        String version,
        ReadFileRequest nextRead) {
      this(
          path,
          matches,
          sections,
          error,
          truncated,
          filesTruncated,
          version,
          nextRead,
          0,
          List.of(),
          0);
    }

    /** 共通の実行基盤で用いる合計行一覧を返す。 */
    int totalLines() {
      return sections == null ? 0 : sections.stream().mapToInt(s -> s.content().size()).sum();
    }

    /** 指定された省略の有無を反映した情報を作成する。 */
    SearchAndReadResult withTruncated(boolean value) {
      return new SearchAndReadResult(
          path,
          matches,
          sections,
          error,
          value,
          filesTruncated,
          version,
          nextRead,
          score,
          matchedBy,
          omittedMatches);
    }

    /** 指定された順位付けを反映した情報を作成する。 */
    SearchAndReadResult withRanking(int value, List<String> reasons) {
      return new SearchAndReadResult(
          path,
          matches,
          sections,
          error,
          truncated,
          filesTruncated,
          version,
          nextRead,
          value,
          reasons,
          omittedMatches);
    }
  }

  /** 1 リクエストあたりの最大ファイル数。 */
  static final int MAX_READ_FILES = 20;

  /** 1 ファイルあたりの最大行数。 */
  static final int MAX_READ_LINES_PER_FILE = 1000;

  /** リクエスト全体の最大行数。 */
  static final int MAX_READ_TOTAL_LINES = 5000;

  /**
   * 複数のファイルまたは行範囲を 1 回のツール呼び出しで読み込む。
   *
   * <p>ファイルが 1 件だけの場合もこのツールを使用する。
   */
  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      name = "readMultiFile",
      description =
          """
      Read one or more known file paths or line ranges in a single call.
      For Markdown, use section, sectionPath (array), sectionId or headingLevel. includeChildren defaults true.
      Duplicate headings return selection.candidates; choose a sectionId and expectedVersion. contextLines applies.
      unreadOnly:true suppresses only confirmed complete explicit ranges; uncertain or compressed source is returned.
      For Java declarations, pass symbol (e.g. p.C#work(String[])), optionally path to narrow candidates.
      Overloaded names return candidates; select an exact symbolId. includeBody defaults true, includeJavadoc true,
      contextLines defaults 0 (max 25), includeOwnerOverview defaults false. Use returned nextRead unchanged. unreadOnly:true suppresses only complete explicit line ranges confirmed in the current model prompt; full-file requests always return source.
      A batch shares source byte/line and result token budgets fairly across files. A truncated result is a
      contiguous prefix, never head/tail excerpts. Empty content with truncated=true and nextRead is a budget
      continuation, not an empty file or a read failure; pass each returned nextRead unchanged in the next batch.
      truncationReason identifies SOURCE_BYTE_BUDGET, SOURCE_LINE_BUDGET, or RESULT_TOKEN_BUDGET.
      Use this when target paths are already known. If relevant files must first be located, prefer searchAndRead.
      @param files 読み込むファイルのリスト。各要素は path と任意の startLine / endLine を持つ。
      @return ファイルごとの読み込み結果。path で識別できる。

      Example: {"files":[{"path":"src/A.java","startLine":1,"endLine":40},{"path":"src/B.java","startLine":1,"endLine":40}]}
      """
              + dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.BATCHING)
  List<ReadFileResult> readMultiFile(List<ReadFileRequest> files) throws IOException {
    return readMultiFile(files, currentWorkingDirectory());
  }

  /** スキル保存と検索の窓口。 */
  private dev.mikoto2000.rei.skills.AgentSkillRepository skillRepository;

  /** スキル設定。 */
  private dev.mikoto2000.rei.skills.AgentSkillsProperties skillProperties;

  /** 共通の実行基盤で使用するスキル補助資料一覧を設定する。 */
  @Autowired
  void setSkillResources(
      dev.mikoto2000.rei.skills.AgentSkillRepository repository,
      dev.mikoto2000.rei.skills.AgentSkillsProperties properties) {
    skillRepository = repository;
    skillProperties = properties;
  }

  /** 必要な内容を参照できるよう、スキル補助資料を読み取る。 */
  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      description =
          """
      Read one or more reference files inside an enabled Agent Skill, independent of the current Project directory.
      Specify skillName and files[].path relative to that skill's directory (e.g. references/learning-design.md).
      Absolute paths, paths outside the skill directory, sensitive files and linked paths are rejected.
      Each file supports line ranges, charset, expectedVersion and offset; Java symbol requests are unsupported.
      Batch known references from the same skill in one call, including single-file reads as a one-item list.
      A batch accepts 1 to 20 files and shares source byte/line and result token budgets across files.
      If truncated, call again with the same skillName and each returned nextRead unchanged in files.
      Reads only; does not execute scripts or grant permission to modify files.
      """)
  List<ReadFileResult> readSkillResource(String skillName, List<ReadFileRequest> files)
      throws IOException {
    if (skillRepository == null || skillProperties == null || !skillProperties.isEnabled()) {
      throw new IllegalStateException("Agent Skills are unavailable or disabled");
    }
    var skill =
        skillRepository
            .findByName(skillName)
            .filter(dev.mikoto2000.rei.skills.AgentSkill::enabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled skill"));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (files == null || files.isEmpty() || files.size() > MAX_READ_FILES) {
      throw new IllegalArgumentException("files must contain 1 to " + MAX_READ_FILES + " entries");
    }
    for (var file : files) {
      if (file == null
          || file.path() == null
          || file.path().isBlank()
          || file.symbol() != null
          || Paths.get(file.path()).isAbsolute()) {
        throw new IllegalArgumentException(
            "A relative skill resource path is required; symbols are unsupported");
      }
    }
    return readMultiFile(files, skill.directory());
  }

  /** 対象の規模を判断するため、ファイル行一覧を数える。 */
  @Tool(
      name = "countFileLines",
      description =
          "複数ファイルの行数を本文を返さず確認します。pathsに具体的なファイルパスを1〜64件指定。globは先にfindFileで展開してください。wc -lと同じLFの数で、末尾に改行がない行は数えません。UTF-8等のLFを使うファイル向け。各fileのpath/lineCount/errorと成功分のtotalLinesを返します。読み取り専用、shell実行なし。各file最大64MiB。")
  FileLineCounts countFileLines(List<String> paths) {
    return countFileLines(paths, currentWorkingDirectory());
  }

  /** 対象の規模を判断するため、ファイル行一覧を数える。 */
  FileLineCounts countFileLines(List<String> paths, java.nio.file.Path workingDirectory) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (paths == null || paths.isEmpty() || paths.size() > 64)
      throw new IllegalArgumentException("paths must contain 1 to 64 files");
    for (String path : paths)
      if (path == null || path.isBlank() || path.length() > 1024)
        throw new IllegalArgumentException(
            "File path must be nonblank and at most 1024 characters");
    List<FileLineCount> results = new ArrayList<>();
    long total = 0;
    for (String requested : paths) {
      try {
        var path = resolveProjectPath(requested, workingDirectory);
        if (!Files.isRegularFile(path)) throw new IOException("Not a regular file");
        long count = 0, bytes = 0;
        try (var input = Files.newInputStream(path)) {
          byte[] buffer = new byte[8192];
          int length;
          while ((length = input.read(buffer)) != -1) {
            bytes += length;
            if (bytes > 64L * 1024 * 1024) throw new IOException("File exceeds 64MiB limit");
            for (int i = 0; i < length; i++) if (buffer[i] == '\n') count++;
          }
        }
        total += count;
        results.add(new FileLineCount(requested, count, null));
      } catch (IOException | IllegalArgumentException e) {
        results.add(new FileLineCount(requested, null, e.getMessage()));
      }
    }
    return new FileLineCounts(List.copyOf(results), total);
  }

  /**
   * 共通の実行基盤で受け渡すファイル行件数を表す。
   *
   * @param path パス
   * @param lineCount 行件数
   * @param error エラー
   */
  public record FileLineCount(String path, Long lineCount, String error) {}

  /**
   * 共通の実行基盤で受け渡すファイル行件数を表す。
   *
   * @param files ファイル一覧
   * @param totalLines 合計行一覧
   */
  public record FileLineCounts(List<FileLineCount> files, long totalLines) {}

  /** 必要な内容を参照できるよう、複数ファイルを読み取る。 */
  List<ReadFileResult> readMultiFile(
      List<ReadFileRequest> files, java.nio.file.Path workingDirectory) throws IOException {
    try (var measurement = ReadingMetrics.operation("read", readingStateStore)) {
      if (files == null || files.isEmpty()) {
        throw new IllegalArgumentException("files must not be empty");
      }
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (files.size() > MAX_READ_FILES) {
        throw new IllegalArgumentException(
            "too many files: " + files.size() + " (max " + MAX_READ_FILES + ")");
      }
      for (var request : files)
        if (request == null || request.path() != null && request.path().length() > 1024)
          throw new IllegalArgumentException("File request/path limit exceeded");
      List<ReadFileResult> results = new ArrayList<>();
      FileSnapshots snapshots = newSnapshots();
      TextReadBudget budget = new TextReadBudget();
      int totalLines = 0;
      for (int index = 0; index < files.size(); index++) {
        ReadFileRequest request = files.get(index);
        if (request.expectedVersion() != null) ReadingMetrics.add("continuations", 1);
        int remainingRequests = files.size() - index;
        int byteBudget = budget.remaining() / remainingRequests;
        TextReadBudget fileBudget = new TextReadBudget(byteBudget);
        int lineBudget = (MAX_READ_TOTAL_LINES - totalLines) / remainingRequests;
        try {
          ReadFileResult result =
              MarkdownSectionReads.selected(request)
                  ? MarkdownSectionReads.read(
                      this, request, workingDirectory, snapshots, fileBudget, lineBudget)
                  : request.symbol() != null
                      ? JavaSymbolReads.read(
                          request,
                          workingDirectory,
                          snapshots,
                          repositoryMaps,
                          fileBudget,
                          lineBudget,
                          (dev.mikoto2000.rei.core.chat.AgentRunScope.current() == null
                                      ? 8000
                                      : deliveryProperties.getPageTargetTokens())
                                  / files.size()
                              - 16)
                      : readSingleFile(
                          request, workingDirectory, snapshots, fileBudget, lineBudget);
          if (request.symbol() != null && result.error() == null)
            workingSet.recordRead(workingDirectory.resolve(result.path()));
          if (readingDelivery != null) result = applyReadingDelivery(request, result);
          int remaining = MAX_READ_TOTAL_LINES - totalLines;
          if (result.content().size() > remaining) {
            result =
                new ReadFileResult(
                    result.path(),
                    result.startLine(),
                    result.endLine(),
                    result.content().subList(0, Math.max(0, remaining)),
                    true,
                    null);
          }
          totalLines += result.content().size();
          results.add(result);
        } catch (IllegalArgumentException | IOException e) {
          results.add(
              new ReadFileResult(
                  request.path(),
                  null,
                  null,
                  List.of(),
                  false,
                  ReadingProblem.from(e).code() + ": " + e.getMessage()));
        } finally {
          budget.reserve(byteBudget - fileBudget.remaining());
        }
      }
      if (files.stream().anyMatch(request -> request.symbol() != null))
        FileResultBudget.require(results, 65536, 8000);
      return boundedFileResult(
          dev.mikoto2000.rei.core.chat.AgentRunScope.current() == null
              ? results
              : new FileResultTokenPager(deliveryEstimator)
                  .page(results, files, deliveryProperties.getPageMaxTokens()));
    }
  }

  /** 1 ファイルの読み込みを実行する。 */
  private static <T> List<T> boundedFileResult(List<T> result) throws IOException {
    // Final envelope guard also covers escaping, error metadata and paths.
    if (FILE_RESULT_JSON.writeValueAsBytes(result).length > 1024 * 1024)
      throw new IOException("Serialized file result exceeds 1MiB; narrow the request");
    ReadingMetrics.generated(result);
    if (result.isEmpty() || !(result.getFirst() instanceof SearchAndReadResult))
      ReadingMetrics.wire(result);
    return result;
  }

  /** 必要な内容を参照できるよう、単一ファイルを読み取る。 */
  ReadFileResult readSingleFile(
      ReadFileRequest request,
      java.nio.file.Path workingDirectory,
      FileSnapshots snapshots,
      TextReadBudget budget,
      int lineBudget)
      throws IOException {
    if (request.path() == null || request.path().isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    Integer startLine = request.startLine();
    Integer endLine = request.endLine();
    if (startLine != null && startLine < 1) {
      throw new IllegalArgumentException("startLine は 1 以上である必要があります");
    }
    if (endLine != null && startLine != null && endLine < startLine) {
      throw new IllegalArgumentException("endLine は startLine 以上である必要があります");
    }
    java.nio.file.Path path = resolveProjectPath(request.path(), workingDirectory);
    var snapshot = snapshots.get(workingDirectory, path);
    if (request.expectedVersion() != null && !request.expectedVersion().equals(snapshot.version()))
      throw new IOException("STALE_VERSION: File version mismatch; search/read again");
    List<String> lines =
        snapshots.lines(
            snapshot, request.charset(), () -> snapshotLines(snapshot, request.charset()));
    workingSet.recordRead(path);
    int fromIndex = startLine == null ? 0 : startLine - 1;
    if (fromIndex >= lines.size()) {
      return new ReadFileResult(
          request.path(), startLine, endLine, List.of(), false, null, snapshot.version(), null, 0);
    }
    int toIndex = endLine == null ? lines.size() : Math.min(endLine, lines.size());
    if (toIndex < fromIndex)
      throw new IllegalArgumentException("endLine must not precede startLine");
    int maxLines = Math.min(MAX_READ_LINES_PER_FILE, Math.max(0, lineBudget));
    var slice =
        budget.take(
            lines, fromIndex, toIndex, request.offset() == null ? 0 : request.offset(), maxLines);
    var next =
        slice.truncated()
            ? new ReadFileRequest(
                request.path(),
                slice.nextLine(),
                endLine,
                snapshot.version(),
                slice.nextOffset(),
                request.charset())
            : null;
    String reason =
        slice.truncated()
            ? slice.lines().size() >= maxLines && slice.nextOffset() == 0
                ? "SOURCE_LINE_BUDGET"
                : "SOURCE_BYTE_BUDGET"
            : null;
    return new ReadFileResult(
        request.path(),
        fromIndex + 1,
        slice.lines().isEmpty() ? null : fromIndex + slice.lines().size(),
        slice.lines(),
        slice.truncated(),
        null,
        snapshot.version(),
        next,
        slice.bytes(),
        null,
        List.of(),
        List.of(),
        reason);
  }

  /**
   * 1 ファイルの読み込み要求。startLine / endLine は任意。
   *
   * @param path パス
   * @param startLine 開始行
   * @param endLine 終了行
   * @param expectedVersion 期待する内容形式のバージョン
   * @param offset 開始位置
   * @param charset 文字コード
   * @param symbol シンボル
   * @param includeBody 含める対象本文
   * @param includeJavadoc 含める対象APIの説明
   * @param contextLines 文脈行一覧
   * @param includeOwnerOverview 含める対象所有者概要
   * @param symbolOffset シンボル開始位置
   */
  public record ReadFileRequest(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String path,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer startLine,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer endLine,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String expectedVersion,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer offset,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String charset,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String symbol,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Boolean includeBody,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Boolean includeJavadoc,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer contextLines,
      @org.springframework.ai.tool.annotation.ToolParam(required = false)
          Boolean includeOwnerOverview,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer symbolOffset,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          Boolean unreadOnly,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          List<String> sectionPath,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          String section,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          String sectionId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          Integer headingLevel,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @org.springframework.ai.tool.annotation.ToolParam(required = false)
          Boolean includeChildren) {
    public ReadFileRequest {
      sectionPath = sectionPath == null ? null : List.copyOf(sectionPath);
    }

    public ReadFileRequest(
        String path,
        Integer startLine,
        Integer endLine,
        String expectedVersion,
        Integer offset,
        String charset,
        String symbol,
        Boolean includeBody,
        Boolean includeJavadoc,
        Integer contextLines,
        Boolean includeOwnerOverview,
        Integer symbolOffset,
        Boolean unreadOnly) {
      this(
          path,
          startLine,
          endLine,
          expectedVersion,
          offset,
          charset,
          symbol,
          includeBody,
          includeJavadoc,
          contextLines,
          includeOwnerOverview,
          symbolOffset,
          unreadOnly,
          null,
          null,
          null,
          null,
          null);
    }

    public ReadFileRequest(
        String path,
        Integer startLine,
        Integer endLine,
        String expectedVersion,
        Integer offset,
        String charset,
        String symbol,
        Boolean includeBody,
        Boolean includeJavadoc,
        Integer contextLines,
        Boolean includeOwnerOverview,
        Integer symbolOffset) {
      this(
          path,
          startLine,
          endLine,
          expectedVersion,
          offset,
          charset,
          symbol,
          includeBody,
          includeJavadoc,
          contextLines,
          includeOwnerOverview,
          symbolOffset,
          null);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ReadFileRequest(
        String path,
        Integer startLine,
        Integer endLine,
        String expectedVersion,
        Integer offset,
        String charset) {
      this(
          path,
          startLine,
          endLine,
          expectedVersion,
          offset,
          charset,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ReadFileRequest(String path, Integer startLine, Integer endLine) {
      this(path, startLine, endLine, null, null, null);
    }
  }

  /**
   * 1 ファイルの読み込み結果。
   *
   * @param path パス
   * @param startLine 開始行
   * @param endLine 終了行
   * @param content 内容
   * @param truncated 省略の有無
   * @param error エラー
   * @param version 形式のバージョン
   * @param nextRead 次読み取り
   * @param returnedBytes returnedバイト数
   * @param symbol シンボル
   * @param candidates 候補一覧
   * @param ownerOverview 所有者概要
   * @param truncationReason truncation理由
   */
  public record ReadFileResult(
      String path,
      Integer startLine,
      Integer endLine,
      List<String> content,
      boolean truncated,
      String error,
      String version,
      ReadFileRequest nextRead,
      int returnedBytes,
      RepositoryMapService.Symbol symbol,
      List<JavaSymbolReads.Candidate> candidates,
      List<String> ownerOverview,
      String truncationReason,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ReadingDelivery.Notice delivery,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          MarkdownSectionReads.Selection selection) {
    public ReadFileResult {
      content = content == null ? null : List.copyOf(content);
      candidates = candidates == null ? null : List.copyOf(candidates);
      ownerOverview = ownerOverview == null ? null : List.copyOf(ownerOverview);
    }

    public ReadFileResult(
        String path,
        Integer startLine,
        Integer endLine,
        List<String> content,
        boolean truncated,
        String error,
        String version,
        ReadFileRequest nextRead,
        int returnedBytes,
        RepositoryMapService.Symbol symbol,
        List<JavaSymbolReads.Candidate> candidates,
        List<String> ownerOverview,
        String truncationReason,
        ReadingDelivery.Notice delivery) {
      this(
          path,
          startLine,
          endLine,
          content,
          truncated,
          error,
          version,
          nextRead,
          returnedBytes,
          symbol,
          candidates,
          ownerOverview,
          truncationReason,
          delivery,
          null);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public dev.mikoto2000.rei.tooldelivery.SourceEvidence evidence() {
      if (version == null || startLine == null || endLine == null || error != null) return null;
      String selector =
          symbol != null
              ? symbol.symbolId()
              : selection != null && selection.selected() != null
                  ? String.join("/", selection.selected().path())
                  : null;
      return new dev.mikoto2000.rei.tooldelivery.SourceEvidence(
          path, version, startLine, endLine, selector, "readMultiFile", null);
    }

    ReadFileResult withSelection(MarkdownSectionReads.Selection value) {
      return new ReadFileResult(
          path,
          startLine,
          endLine,
          content,
          truncated,
          error,
          version,
          nextRead,
          returnedBytes,
          symbol,
          candidates,
          ownerOverview,
          truncationReason,
          delivery,
          value);
    }

    @com.fasterxml.jackson.annotation.JsonProperty(
        access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public ReadingProblem problem() {
      if (error == null) return null;
      return ReadingProblem.fromMessage(error);
    }

    public ReadFileResult(
        String path,
        Integer startLine,
        Integer endLine,
        List<String> content,
        boolean truncated,
        String error,
        String version,
        ReadFileRequest nextRead,
        int returnedBytes,
        RepositoryMapService.Symbol symbol,
        List<JavaSymbolReads.Candidate> candidates,
        List<String> ownerOverview,
        String truncationReason) {
      this(
          path,
          startLine,
          endLine,
          content,
          truncated,
          error,
          version,
          nextRead,
          returnedBytes,
          symbol,
          candidates,
          ownerOverview,
          truncationReason,
          null);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ReadFileResult(
        String path,
        Integer startLine,
        Integer endLine,
        List<String> content,
        boolean truncated,
        String error,
        String version,
        ReadFileRequest nextRead,
        int returnedBytes,
        RepositoryMapService.Symbol symbol,
        List<JavaSymbolReads.Candidate> candidates,
        List<String> ownerOverview) {
      this(
          path,
          startLine,
          endLine,
          content,
          truncated,
          error,
          version,
          nextRead,
          returnedBytes,
          symbol,
          candidates,
          ownerOverview,
          null);
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ReadFileResult(
        String path,
        Integer startLine,
        Integer endLine,
        List<String> content,
        boolean truncated,
        String error,
        String version,
        ReadFileRequest nextRead,
        int returnedBytes) {
      this(
          path,
          startLine,
          endLine,
          content,
          truncated,
          error,
          version,
          nextRead,
          returnedBytes,
          null,
          List.of(),
          List.of());
    }

    /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ReadFileResult(
        String path,
        Integer startLine,
        Integer endLine,
        List<String> content,
        boolean truncated,
        String error) {
      this(path, startLine, endLine, content, truncated, error, null, null, 0);
    }

    /** 共通の実行基盤で用いる継続入力指示を返す。 */
    @com.fasterxml.jackson.annotation.JsonProperty(
        value = "continuationInstruction",
        access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String continuationInstruction() {
      return truncated && nextRead != null
          ? "Use nextRead unchanged. Empty content with nextRead is a budget continuation, not an empty file."
          : null;
    }
  }

  /** 1 リクエストあたりの最大ファイル数。 */
  static final int MAX_WRITE_FILES = 20;

  /** 1 ファイルあたりの最大 content サイズ（文字数）。 */
  static final int MAX_WRITE_CHARS_PER_FILE = 100_000;

  /** リクエスト全体の最大 content サイズ（文字数）。 */
  static final int MAX_WRITE_TOTAL_CHARS = 500_000;

  /**
   * 複数の既知ファイルを 1 回のツール呼び出しで書き込む。
   *
   * <p>ファイルが 1 件だけの場合もこのツールを使用する。
   */
  @Tool(
      name = "writeMultiFile",
      description =
          """
      Write one or more known files in a single call: replace complete contents or append to each file.
      Always pass a non-empty files array, even for one file. Put path, content, append, and charset inside each
      files element, not at the top level. append: true appends; append: false or omitted replaces complete contents.
      Use this for direct full-file writes or appending chunks. Prefer proposeTextChangeSet or proposeTextDocumentChangeSet for reviewed UTF-8 edits. Use applyTextDiff for legacy non-UTF-8 text edits.
      @param files 書き込むファイルの空でないリスト。各要素に必須の path と content、任意の append と charset を指定する。
      @return ファイルごとの書き込み結果。path で識別できる。

      Example: {"files":[{"path":"a.txt","content":"Alpha","append":false,"charset":"UTF-8"},{"path":"b.txt","content":"Beta","append":false,"charset":"UTF-8"}]}
      """
              + dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.BATCHING)
  List<WriteFileResult> writeMultiFile(List<WriteFileRequest> files) throws IOException {
    return writeMultiFile(files, currentWorkingDirectory());
  }

  /** 指定された内容を保存先へ反映するため、複数ファイルを書き込む。 */
  List<WriteFileResult> writeMultiFile(
      List<WriteFileRequest> files, java.nio.file.Path workingDirectory) throws IOException {
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("files must not be empty");
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (files.size() > MAX_WRITE_FILES) {
      throw new IllegalArgumentException(
          "too many files: " + files.size() + " (max " + MAX_WRITE_FILES + ")");
    }
    // 書き込み開始前に全件 validation を実施する（入力段階で検出可能なエラーを先に検出）
    List<WriteFileRequest> validated = new ArrayList<>();
    Set<String> seenPaths = new LinkedHashSet<>();
    int totalChars = 0;
    for (WriteFileRequest request : files) {
      String validationError = validateWriteRequest(request, workingDirectory);
      if (validationError != null) {
        throw new IllegalArgumentException(validationError);
      }
      String normalized = resolveProjectPath(request.path(), workingDirectory).toString();
      if (!seenPaths.add(normalized)) {
        throw new IllegalArgumentException("duplicate path: " + request.path());
      }
      totalChars += request.content() == null ? 0 : request.content().length();
      if (totalChars > MAX_WRITE_TOTAL_CHARS) {
        throw new IllegalArgumentException("total content size exceeds limit");
      }
      validated.add(request);
    }
    List<WriteFileResult> results = new ArrayList<>();
    for (WriteFileRequest request : validated) {
      try {
        writeSingleFile(request, workingDirectory);
        results.add(new WriteFileResult(request.path(), true, null));
      } catch (IOException e) {
        results.add(new WriteFileResult(request.path(), false, e.getMessage()));
      }
    }
    return results;
  }

  /** 書き込み要求を検証する。問題があればエラーメッセージを返す。 */
  private String validateWriteRequest(
      WriteFileRequest request, java.nio.file.Path workingDirectory) {
    if (request.path() == null || request.path().isBlank()) {
      return "path must not be blank";
    }
    if (request.content() == null) {
      return "content must not be null";
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (request.content().length() > MAX_WRITE_CHARS_PER_FILE) {
      return "content size exceeds per-file limit";
    }
    return null;
  }

  /** 1 ファイルの書き込みを実行する。 */
  private void writeSingleFile(WriteFileRequest request, java.nio.file.Path workingDirectory)
      throws IOException {
    Charset resolvedCharset = resolveCharset(request.charset());
    OpenOption[] options =
        Boolean.TRUE.equals(request.append())
            ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
            : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
    java.nio.file.Path path = resolveProjectPath(request.path(), workingDirectory);
    java.nio.file.Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    boolean existed = Files.exists(path);
    Files.writeString(path, request.content(), resolvedCharset, options);
    if (Boolean.TRUE.equals(request.append())) {
      workingSet.recordEdit(path);
      recentChanges.record(path.toString(), RecentChanges.OP_EDIT, "edited");
      fileSummaryCache.invalidate(path.toString());
      publishFileModified(path, null, null);
    } else {
      workingSet.recordWrite(path);
      recentChanges.record(
          path.toString(),
          existed ? RecentChanges.OP_EDIT : RecentChanges.OP_CREATE,
          existed ? "edited" : "created");
      fileSummaryCache.invalidate(path.toString());
      publishFileCreatedOrModified(path, existed);
    }
    relatedFileGraph.removeRelationsFor(path.toString());
    searchResultCache.clear();
  }

  /**
   * 1 ファイルの書き込み要求。
   *
   * @param path パス
   * @param content 内容
   * @param append 追記
   * @param charset 文字コード
   */
  public record WriteFileRequest(
      String path,
      String content,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Boolean append,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String charset) {}

  /**
   * 1 ファイルの書き込み結果。
   *
   * @param path パス
   * @param success 成功
   * @param error エラー
   */
  public record WriteFileResult(String path, boolean success, String error) {}

  /** 条件に合う対象を選ぶため、パスの照合規則との一致を判定する。 */
  boolean matchesGlob(
      String relativePath,
      SearchGlob includeMatcher,
      SearchGlob excludeMatcher,
      java.nio.file.Path workingDirectory,
      java.nio.file.Path baseDirectory) {
    java.nio.file.Path candidate =
        workingDirectory.resolve(relativePath.replace('\\', '/')).toAbsolutePath().normalize();
    String path = baseDirectory.relativize(candidate).toString().replace('\\', '/');
    if (includeMatcher != null && !includeMatcher.matches(path)) {
      return false;
    }
    return excludeMatcher == null || !excludeMatcher.matches(path);
  }

  /** 共通の実行基盤で用いるコンパイル内容検索照合規則を返す。 */
  Pattern compileGrepPattern(String pattern, boolean ignoreCase, boolean fixedString) {
    int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
    String effectivePattern = pattern;
    if (fixedString) flags |= Pattern.LITERAL;
    try {
      return Pattern.compile(effectivePattern, flags);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid grep pattern: " + pattern, e);
    }
  }

  /** 共通の実行基盤において、一致した行かどうかを判定する。 */
  private boolean isMatchedLine(Pattern pattern, String line, boolean invertMatch) {
    boolean matched = BoundedRegex.find(pattern, line);
    return invertMatch ? !matched : matched;
  }

  /** 内容を表示・参照しやすくするため、内容検索行を整形する。 */
  private String formatGrepLine(
      String path, int lineNumber, String line, String separator, boolean includeLineNumber) {
    if (includeLineNumber) {
      return path + separator + lineNumber + separator + line;
    }
    return path + separator + line;
  }

  /** 共通の実行基盤で用いるGitLsファイル一覧を返す。 */
  List<String> gitLsFiles(List<String> pathSpecs, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    List<String> commandLine =
        new ArrayList<>(
            List.of(
                "git",
                "ls-files",
                "-z",
                "--cached",
                "--others",
                "--exclude-standard",
                "--full-name"));
    if (pathSpecs != null && !pathSpecs.isEmpty()) {
      commandLine.add("--");
      commandLine.addAll(pathSpecs);
    }

    ProcessBuilder pb = new ProcessBuilder(commandLine);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    Process process = pb.start();
    IO.println("git ls-files コマンドを実行したよ");

    String output;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
    }
    int exitCode = process.waitFor();

    if (exitCode != 0) {
      return null;
    }

    return Arrays.stream(output.split("\0")).filter(s -> !s.isEmpty()).toList();
  }

  /** 必要な内容を参照できるよう、テキストファイルを読み取る。 */
  List<String> readTextFile(String pathStr) throws IOException {
    IO.println(String.format("%s のテキストファイルを読むよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    List<String> lines = readTextFileLines(path, pathStr, "");
    workingSet.recordRead(path);
    return lines;
  }

  /** 必要な内容を参照できるよう、テキストファイル対象範囲を読み取る。 */
  List<String> readTextFileRange(String pathStr, int startLine, int endLine) throws IOException {
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine は 1 以上である必要があります");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine は startLine 以上である必要があります");
    }

    IO.println(String.format("%s の %d 行目から %d 行目を読むよ", pathStr, startLine, endLine));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    List<String> lines = readTextFileLines(path, pathStr, "");
    workingSet.recordRead(path);
    int fromIndex = startLine - 1;
    if (fromIndex >= lines.size()) {
      return List.of();
    }
    int toIndex = Math.min(endLine, lines.size());
    return lines.subList(fromIndex, toIndex);
  }

  /**
   * 共通の実行基盤で受け渡すPDFページを表す。
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param page ページ
   * @param totalPages 合計ページ一覧
   * @param offset 開始位置
   * @param nextOffset 次開始位置
   * @param nextPage 次ページ
   * @param truncated 省略の有無
   * @param content 内容
   */
  public record PdfPage(
      String path,
      String version,
      int page,
      int totalPages,
      int offset,
      int nextOffset,
      int nextPage,
      boolean truncated,
      String content) {}

  /** 必要な内容を参照できるよう、PDFページを読み取る。 */
  @dev.mikoto2000.rei.core.contextbudget.ResultDelivery(
      dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      name = "readPdfFile",
      description =
          "Read a lossless PDF text page with original page number and source version. Optional page defaults to 1, offset to 0, tokenBudget to 2048. Continue with nextPage/nextOffset while truncated; expectedVersion must match across pages. Scanned PDFs require OCR and cannot prove visual coverage.")
  public PdfPage readPdfPage(
      String pathStr,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer page,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer offset,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String expectedVersion,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer tokenBudget)
      throws IOException {
    int number = page == null ? 1 : page,
        start = offset == null ? 0 : offset,
        budget = tokenBudget == null ? deliveryProperties.getPageTargetTokens() : tokenBudget;
    if (budget < deliveryProperties.getPageMinTokens()
        || budget > deliveryProperties.getPageMaxTokens())
      throw new IOException("CONTEXT_BUDGET_INSUFFICIENT");
    var root = currentWorkingDirectory();
    var snapshot = newSnapshots().get(root, resolveProjectPath(pathStr, root));
    if (expectedVersion != null
        && !expectedVersion.isBlank()
        && !snapshot.version().equals(expectedVersion)) throw new IOException("STALE_VERSION");
    String relative = root.toRealPath().relativize(snapshot.path()).toString().replace('\\', '/');
    try (var document = org.apache.pdfbox.Loader.loadPDF(snapshot.bytes())) {
      int count = document.getNumberOfPages();
      if (number < 1 || number > count || start < 0) throw new IOException("INVALID_CURSOR");
      var stripper = new org.apache.pdfbox.text.PDFTextStripper();
      stripper.setStartPage(number);
      stripper.setEndPage(number);
      String text = stripper.getText(document);
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (text.length() > 8 * 1024 * 1024)
        throw new IOException("PDF extracted text capacity exceeded");
      if (start > text.length()
          || start > 0
              && start < text.length()
              && Character.isHighSurrogate(text.charAt(start - 1))
              && Character.isLowSurrogate(text.charAt(start)))
        throw new IOException("INVALID_CURSOR");
      int low = 0, high = text.codePointCount(start, text.length());
      while (low < high) {
        int mid = low + (high - low + 1) / 2;
        var candidate =
            pdfPage(
                relative,
                snapshot.version(),
                number,
                count,
                start,
                text.offsetByCodePoints(start, mid),
                text);
        if (deliveryEstimator.text(FILE_RESULT_JSON.writeValueAsString(candidate)) <= budget)
          low = mid;
        else high = mid - 1;
      }
      var result =
          pdfPage(
              relative,
              snapshot.version(),
              number,
              count,
              start,
              text.offsetByCodePoints(start, low),
              text);
      if (deliveryEstimator.text(FILE_RESULT_JSON.writeValueAsString(result)) > budget
          || low == 0 && start < text.length())
        throw new IOException("CONTEXT_BUDGET_INSUFFICIENT");
      workingSet.recordRead(snapshot.path());
      return result;
    }
  }

  /** 共通の実行基盤で用いるPDFページを返す。 */
  private PdfPage pdfPage(
      String path, String version, int page, int count, int start, int end, String text) {
    boolean more = end < text.length();
    return new PdfPage(
        path,
        version,
        page,
        count,
        start,
        more ? end : 0,
        more ? page : page < count ? page + 1 : 0,
        more || page < count,
        text.substring(start, end));
  }

  /** 必要な内容を参照できるよう、PDFファイルを読み取る。 */
  String readPdfFile(String pathStr) throws IOException {
    IO.println(String.format("%s の PDF ファイルを読むよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(path));
    String extracted =
        documentReader.get().stream()
            .map(Document::getText)
            .filter(text -> text != null && !text.isBlank())
            .collect(Collectors.joining(System.lineSeparator()));
    workingSet.recordRead(path);
    return extracted;
  }

  /**
   * テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
   *
   * @param pathStr ファイルのパス
   * @param contents 書き込む内容
   * @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
   */
  void writeTextFile(String pathStr, String contents, boolean append, String charset)
      throws IOException {
    Charset resolvedCharset = resolveCharset(charset);
    IO.println(
        String.format("%s のテキストファイルに %s を %s で書き込むよ", pathStr, contents, resolvedCharset.name()));

    OpenOption[] options = null;
    if (append) {
      options = new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND};
    } else {
      options = new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
    }

    java.nio.file.Path path = resolveProjectPath(pathStr);
    java.nio.file.Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    boolean existed = Files.exists(path);
    Files.writeString(path, contents, resolvedCharset, options);
    if (append) {
      workingSet.recordEdit(path);
      recentChanges.record(path.toString(), RecentChanges.OP_EDIT, "edited");
      fileSummaryCache.invalidate(path.toString());
      publishFileModified(path, null, null);
    } else {
      workingSet.recordWrite(path);
      recentChanges.record(
          path.toString(),
          existed ? RecentChanges.OP_EDIT : RecentChanges.OP_CREATE,
          existed ? "edited" : "created");
      fileSummaryCache.invalidate(path.toString());
      publishFileCreatedOrModified(path, existed);
    }
    relatedFileGraph.removeRelationsFor(path.toString());
    searchResultCache.clear();
  }

  /** 指定された変更を有効にするため、テキスト差分を反映する。 */
  @Tool(
      name = "applyTextDiff",
      description =
          """
  Apply a targeted textual replacement to an existing file.
  Use this for legacy non-UTF-8 small, localized edits that leave the surrounding file unchanged.
  For UTF-8 edits prefer proposeTextChangeSet or proposeTextDocumentChangeSet.
  oldText がファイル内に一意に存在する場合だけ newText に置換します。
  @param pathStr 対象ファイルのパス
  @param oldText 置換前の期待文字列
  @param newText 置換後の文字列
  @param charset 読み書き文字コード。null または空の場合は UTF-8、UTF-8 で読めない場合は CP932
  @return 適用結果
  """)
  TextDiffApplyResult applyTextDiff(
      String pathStr,
      String oldText,
      String newText,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String charset)
      throws IOException {
    IO.println(String.format("applyTextDiff を実行するよ: %s", pathStr));
    if (oldText == null || oldText.isEmpty()) {
      throw new IllegalArgumentException("oldText は空にできません");
    }
    if (newText == null) {
      throw new IllegalArgumentException("newText は null にできません");
    }

    java.nio.file.Path path = resolveProjectPath(pathStr);
    byte[] baseline = Files.readAllBytes(path);
    ResolvedTextFile original = decodeTextFileContent(baseline, charset);
    String originalContent = original.content();
    String updatedContent;
    try {
      updatedContent =
          TextEditEngine.edit(
                  originalContent, List.of(new TextChangeSetService.Edit(oldText, newText)))
              .text();
    } catch (IllegalArgumentException invalid) {
      if (invalid instanceof dev.mikoto2000.rei.toolcontract.StructuredToolFailure failure) {
        String message =
            switch (failure.code()) {
              case "TEXT_NOT_FOUND" -> "oldText が見つかりません";
              case "AMBIGUOUS_MATCH" -> "oldText が複数箇所に一致しました";
              default -> failure.code();
            };
        return new TextDiffApplyResult(
            false,
            false,
            message,
            TextEditEngine.detail(
                failure.code(), pathStr, 0, List.of(), FileSnapshots.hash(baseline)));
      }
      return new TextDiffApplyResult(false, false, invalid.getMessage());
    }
    if (updatedContent.equals(originalContent)) {
      return new TextDiffApplyResult(true, false, "変更はありません");
    }

    String encodedText =
        decodeStrict(baseline, original.charset()).startsWith("\ufeff")
            ? "\ufeff" + updatedContent
            : updatedContent;
    var encoded = original.charset().newEncoder().encode(java.nio.CharBuffer.wrap(encodedText));
    byte[] replacement = new byte[encoded.remaining()];
    encoded.get(replacement);
    TextDocumentTransaction.writeBytes(
        path, baseline, replacement, TextDocumentTransaction::replace);
    recordTextEdit(path);
    return new TextDiffApplyResult(true, true, "差分を適用しました");
  }

  /** 後から経緯や使用量を確認できるよう、テキスト編集を記録する。 */
  private void recordTextEdit(java.nio.file.Path path) {
    workingSet.recordEdit(path);
    recentChanges.record(path.toString(), RecentChanges.OP_EDIT, "edited");
    fileSummaryCache.invalidate(path.toString());
    publishFileModified(path, null, null);
    relatedFileGraph.removeRelationsFor(path.toString());
    searchResultCache.clear();
  }

  /**
   * 共通の実行基盤で受け渡すテキスト差分反映結果を表す。
   *
   * @param success 成功
   * @param changed 変更済み
   * @param message メッセージ
   */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record TextDiffApplyResult(
      boolean success, boolean changed, String message, TextEditEngine.Failure error) {
    public TextDiffApplyResult(boolean success, boolean changed, String message) {
      this(success, changed, message, null);
    }
  }

  /** 必要な内容を参照できるよう、バイナリファイルを読み取る。 */
  @Tool(
      name = "readBinaryFile",
      description = "バイナリファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  byte[] readBinaryFile(String pathStr) throws IOException {
    IO.println(String.format("%s のバイナリファイルを読むよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    byte[] bytes = Files.readAllBytes(path);
    workingSet.recordRead(path);
    return bytes;
  }

  /** 新たな対象を利用できるよう、ディレクトリ一覧を作成する。 */
  @Tool(name = "createDirectories", description = "指定したパスまでのディレクトリをすべて作成します。既に存在するディレクトリは成功扱いです。")
  String createDirectories(String pathStr) throws IOException {
    if (pathStr == null || pathStr.isBlank()) {
      throw new IllegalArgumentException("pathStr は空にできません");
    }
    IO.println(String.format("%s までのディレクトリを作成するよ", pathStr));
    java.nio.file.Path createdPath = Files.createDirectories(resolveProjectPath(pathStr));
    return createdPath.toAbsolutePath().normalize().toString();
  }

  /** 指定された内容を保存先へ反映するため、バイナリファイルを書き込む。 */
  @Tool(name = "writeBinaryFile", description = "バイナリファイルに書き込みます。ファイルが存在しない場合は作成します。")
  void writeBinaryFile(String pathStr, byte[] contents, boolean append) throws IOException {
    IO.println(String.format("%s のバイナリファイルに %s を書き込むよ", pathStr, contents));

    OpenOption[] options = null;
    if (append) {
      options = new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND};
    } else {
      options = new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
    }

    java.nio.file.Path path = resolveProjectPath(pathStr);
    boolean existed = Files.exists(path);
    Files.write(path, contents, options);
    if (append) {
      workingSet.recordEdit(path);
      recentChanges.record(path.toString(), RecentChanges.OP_EDIT, "edited");
      fileSummaryCache.invalidate(path.toString());
      publishFileModified(path, null, null);
    } else {
      workingSet.recordWrite(path);
      recentChanges.record(
          path.toString(),
          existed ? RecentChanges.OP_EDIT : RecentChanges.OP_CREATE,
          existed ? "edited" : "created");
      fileSummaryCache.invalidate(path.toString());
      publishFileCreatedOrModified(path, existed);
    }
    relatedFileGraph.removeRelationsFor(path.toString());
    searchResultCache.clear();
  }

  /** 対象を管理から外すため、ファイルを削除する。 */
  @Tool(name = "deleteFile", description = "ファイルを削除します。ファイルが存在しない場合はエラーになります。")
  void deleteFile(String pathStr) throws IOException {
    IO.println(String.format("%s を削除するよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    Files.delete(path);
    workingSet.remove(path);
    recentChanges.record(path.toString(), RecentChanges.OP_DELETE, "deleted");
    fileSummaryCache.invalidate(path.toString());
    relatedFileGraph.removeRelationsFor(path.toString());
    searchResultCache.clear();
    publishFileDeleted(path);
  }

  /** 共通の実行基盤に関する複製ファイルを処理する。 */
  @Tool(name = "copyFile", description = "ファイルをコピーします。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void copyFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s にコピーするよ。上書き：%s", sourcePath, destPath, overwrite));

    java.nio.file.Path resolvedSourcePath = resolveProjectPath(sourcePath);
    java.nio.file.Path resolvedDestPath = resolveProjectPath(destPath);
    boolean exists = Files.exists(resolvedDestPath);
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.copy(
        resolvedSourcePath,
        resolvedDestPath,
        overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.COPY_ATTRIBUTES);
    workingSet.recordCreate(resolvedDestPath);
    recentChanges.record(
        resolvedDestPath.toString(),
        exists ? RecentChanges.OP_EDIT : RecentChanges.OP_CREATE,
        exists ? "edited" : "created");
    fileSummaryCache.invalidate(resolvedDestPath.toString());
    relatedFileGraph.removeRelationsFor(resolvedDestPath.toString());
    searchResultCache.clear();
    publishFileCreatedOrModified(resolvedDestPath, exists);
  }

  /** 共通の実行基盤に関するmoveファイルを処理する。 */
  @Tool(name = "moveFile", description = "ファイルを移動します。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void moveFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s に移動するよ。上書き：%s", sourcePath, destPath, overwrite));

    java.nio.file.Path resolvedSourcePath = resolveProjectPath(sourcePath);
    java.nio.file.Path resolvedDestPath = resolveProjectPath(destPath);
    boolean exists = Files.exists(resolvedDestPath);
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.move(
        resolvedSourcePath,
        resolvedDestPath,
        overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.ATOMIC_MOVE);
    workingSet.recordCreate(resolvedDestPath);
    recentChanges.record(
        resolvedDestPath.toString(),
        exists ? RecentChanges.OP_EDIT : RecentChanges.OP_CREATE,
        exists ? "edited" : "created");
    fileSummaryCache.invalidate(resolvedDestPath.toString());
    relatedFileGraph.removeRelationsFor(resolvedDestPath.toString());
    searchResultCache.clear();
    publishFileDeleted(resolvedSourcePath);
    publishFileCreatedOrModified(resolvedDestPath, exists);
  }

  /** 共通の実行基盤で用いる作業中集合を返す。 */
  WorkingSet workingSet() {
    return workingSet;
  }

  /** 共通の実行基盤で用いる直近変更一覧を返す。 */
  RecentChanges recentChanges() {
    return recentChanges;
  }

  /** 共通の実行基盤で用いるファイル要約キャッシュを返す。 */
  FileSummaryCache fileSummaryCache() {
    return fileSummaryCache;
  }

  /** 依頼に関連する情報を得るため、結果キャッシュを検索する。 */
  SearchResultCache searchResultCache() {
    return searchResultCache;
  }

  /** 共通の実行基盤で用いる関連ファイル関連図を返す。 */
  RelatedFileGraph relatedFileGraph() {
    return relatedFileGraph;
  }

  /** 共通の実行基盤で用いる現在作業ディレクトリを返す。 */
  private java.nio.file.Path currentWorkingDirectory() {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (run != null) return run.projectRoot();
    if (projectService == null) {
      return Paths.get(".").toAbsolutePath().normalize();
    }
    return projectService.currentProject();
  }

  /** 指定や設定に応じた対象を利用するため、プロジェクトパスを確定する。 */
  private java.nio.file.Path resolveProjectPath(String pathStr) {
    return resolveProjectPath(pathStr, currentWorkingDirectory());
  }

  /** 指定や設定に応じた対象を利用するため、プロジェクトパスを確定する。 */
  private java.nio.file.Path resolveProjectPath(
      String pathStr, java.nio.file.Path workingDirectory) {
    java.nio.file.Path path = Paths.get(pathStr);
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return workingDirectory.resolve(path).normalize();
  }

  /** 指定や設定に応じた対象を利用するため、文字コードを確定する。 */
  private Charset resolveCharset(String charset) {
    if (charset == null || charset.isBlank()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(charset);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported charset: " + charset, e);
    }
  }

  /** 必要な内容を参照できるよう、テキストファイル内容を読み取る。 */
  private ResolvedTextFile readTextFileContent(java.nio.file.Path path, String charset)
      throws IOException {
    return decodeTextFileContent(Files.readAllBytes(path), charset);
  }

  private ResolvedTextFile decodeTextFileContent(byte[] bytes, String charset) throws IOException {
    if (charset != null && !charset.isBlank()) {
      Charset resolvedCharset = resolveCharset(charset);
      return new ResolvedTextFile(stripBom(decodeStrict(bytes, resolvedCharset)), resolvedCharset);
    }
    ResolvedTextFile bomDecoded = decodeBom(bytes);
    if (bomDecoded != null) {
      return bomDecoded;
    }
    try {
      return new ResolvedTextFile(
          decodeStrict(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      return new ResolvedTextFile(decodeStrict(bytes, CP932), CP932);
    }
  }

  /** 必要な内容を参照できるよう、テキストファイル行一覧を読み取る。 */
  private List<String> readTextFileLines(
      java.nio.file.Path path, String displayPath, String charset) throws IOException {
    ResolvedTextFile resolved = readTextFileContent(path, charset);
    if ((charset == null || charset.isBlank()) && resolved.charset().equals(CP932)) {
      IO.println(String.format("%s は UTF-8 として読めなかったため CP932 で読み直したよ", displayPath));
    }
    return resolved.content().lines().toList();
  }

  /** 受信した内容を利用できるよう、厳格なを復元する。 */
  private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
    return charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString();
  }

  /** 受信した内容を利用できるよう、文字コードの識別情報を復元する。 */
  private ResolvedTextFile decodeBom(byte[] bytes) throws CharacterCodingException {
    if (startsWith(bytes, 0x00, 0x00, 0xfe, 0xff)) {
      return decodeAfterBom(bytes, 4, Charset.forName("UTF-32BE"));
    }
    if (startsWith(bytes, 0xff, 0xfe, 0x00, 0x00)) {
      return decodeAfterBom(bytes, 4, Charset.forName("UTF-32LE"));
    }
    if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
      return decodeAfterBom(bytes, 3, StandardCharsets.UTF_8);
    }
    if (startsWith(bytes, 0xfe, 0xff)) {
      return decodeAfterBom(bytes, 2, StandardCharsets.UTF_16BE);
    }
    if (startsWith(bytes, 0xff, 0xfe)) {
      return decodeAfterBom(bytes, 2, StandardCharsets.UTF_16LE);
    }
    return null;
  }

  /** 受信した内容を利用できるよう、変更後文字コードの識別情報を復元する。 */
  private ResolvedTextFile decodeAfterBom(byte[] bytes, int bomLength, Charset charset)
      throws CharacterCodingException {
    byte[] content = Arrays.copyOfRange(bytes, bomLength, bytes.length);
    return new ResolvedTextFile(decodeStrict(content, charset), charset);
  }

  /** 共通の実行基盤で判断に用いる開始付きの条件を確認する。 */
  private boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((bytes[i] & 0xff) != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /** 共通の実行基盤で用いるstrip文字コードの識別情報を返す。 */
  private String stripBom(String content) {
    return content.startsWith("\ufeff") ? content.substring(1) : content;
  }

  /** 実行状況を利用先で把握できるよう、ファイル作成または更新を通知する。 */
  private void publishFileCreatedOrModified(java.nio.file.Path path, boolean existed) {
    if (existed) {
      publishFileModified(path, null, null);
    } else {
      publishFileCreated(path);
    }
  }

  /** 実行状況を利用先で把握できるよう、ファイル作成を通知する。 */
  private void publishFileCreated(java.nio.file.Path path) {
    if (eventFactory != null && eventPublisher != null) {
      eventPublisher.publish(
          eventFactory.fileCreated(path.toAbsolutePath().normalize().toString()));
    }
  }

  /** 実行状況を利用先で把握できるよう、ファイル更新を通知する。 */
  private void publishFileModified(
      java.nio.file.Path path, Integer addedLines, Integer removedLines) {
    if (eventFactory != null && eventPublisher != null) {
      eventPublisher.publish(
          eventFactory.fileModified(
              path.toAbsolutePath().normalize().toString(), addedLines, removedLines));
    }
  }

  /** 実行状況を利用先で把握できるよう、ファイル削除済みを通知する。 */
  private void publishFileDeleted(java.nio.file.Path path) {
    if (eventFactory != null && eventPublisher != null) {
      eventPublisher.publish(
          eventFactory.fileDeleted(path.toAbsolutePath().normalize().toString()));
    }
  }

  /**
   * 共通の実行基盤で受け渡す確定したテキストファイルを表す。
   *
   * @param content 内容
   * @param charset 文字コード
   */
  private record ResolvedTextFile(String content, Charset charset) {}
}
