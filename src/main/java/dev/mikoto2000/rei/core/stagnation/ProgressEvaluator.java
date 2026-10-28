package dev.mikoto2000.rei.core.stagnation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.actionplan.ActionPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 進捗の結果を基準に照らして評価する。
 *
 * <p>Run-local evidence ledger. Unknown tools are conservative: success alone proves nothing.
 */
public class ProgressEvaluator {
  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 読み取りツール一覧。 */
  private static final Set<String> READ_TOOLS =
      Set.of(
          "readMultiFile",
          "readSkillResource",
          "readFile",
          "readTextFile",
          "readTextFileRange",
          "readPdfFile",
          "readBinaryFile",
          "findFile",
          "listFile",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "search",
          "webSearch",
          "webSearchAndRead",
          "fetchUrlContent",
          "searchKnowledge");

  /** 書き込みツール一覧。 */
  private static final Set<String> WRITE_TOOLS =
      Set.of(
          "writeMultiFile",
          "writeTextFile",
          "writeBinaryFile",
          "applyTextDiff",
          "deleteFile",
          "copyFile",
          "moveFile",
          "createDirectories");

  /** Webツール一覧。 */
  private static final Set<String> WEB_TOOLS =
      Set.of("webSearch", "webSearchAndRead", "fetchUrlContent", "searchKnowledge");

  /** 記録台帳上限。 */
  private static final int LEDGER_LIMIT = 1024;

  /** 確認の窓口。 */
  private final dev.mikoto2000.rei.goal.FileGoalVerifier verifier =
      new dev.mikoto2000.rei.goal.FileGoalVerifier();

  /** 作業ルート。 */
  private final Path root;

  /** 計画。 */
  private final ActionPlan plan;

  /** 情報。 */
  private final InformationLedger information = new InformationLedger();

  /** Restoration is bounded across the entire persisted Run lineage. */
  private final InformationLedger restoredRanges = new InformationLedger();

  private InformationLedger.State restorationTargets;
  private int restorationReads;

  /** Metadata may be needed again in a new Run; only its total allowance is lineage-wide. */
  private final Set<String> restoredCheckpoints = new HashSet<>();

  private final Set<String> startedSteps = new HashSet<>();

  /** Bounded diagnostics for the latest tool, separate from durable evidence. */
  private final List<Map<String, Object>> readObservations = new ArrayList<>();

  private boolean readContentObserved;

  /** Current tool content observation, including duplicate ranges; never grants progress. */
  public synchronized boolean readContentObserved() {
    return readContentObserved;
  }

  /** Existing acquisition tools share the same temporary recovery choice boundary. */
  static boolean acquisitionTool(String name) {
    return READ_TOOLS.contains(name)
        || name.equals("readReviewFile")
        || name.equals("readRawToolResult");
  }

  public synchronized String readFeedback() {
    return JSON.valueToTree(readObservations).toString();
  }

  /** Persist only bounded evidence, alongside the existing information checkpoint. */
  public record State(
      InformationLedger.State restoredRanges,
      int restorationReads,
      Set<String> revisions,
      Set<String> completedSteps,
      Set<String> startedSteps) {
    public State {
      revisions = Set.copyOf(revisions);
      completedSteps = Set.copyOf(completedSteps);
      startedSteps = Set.copyOf(startedSteps);
      if (restorationReads < 0
          || restorationReads > 8
          || revisions.size() > LEDGER_LIMIT
          || completedSteps.size() > LEDGER_LIMIT
          || startedSteps.size() > LEDGER_LIMIT)
        throw new IllegalArgumentException("Invalid evaluator state");
    }
  }

  public synchronized State snapshot() {
    return new State(
        restoredRanges.snapshot(), restorationReads, revisions, completedSteps, startedSteps);
  }

  public synchronized void restore(State state) {
    if (state == null) return;
    restoredRanges.restore(state.restoredRanges());
    restorationReads = state.restorationReads();
    revisions.clear();
    revisions.addAll(state.revisions());
    completedSteps.clear();
    completedSteps.addAll(state.completedSteps());
    doneSteps().forEach(step -> remember(completedSteps, step));
    startedSteps.clear();
    startedSteps.addAll(state.startedSteps());
    if (plan != null) plan.currentStep().ifPresent(step -> remember(startedSteps, step.id()));
  }

  public synchronized boolean completeSubgoal(String goal) {
    return remember(completedSteps, "planned-subgoal:" + goal);
  }

  /** Called only after a real checkpoint resume, never for compression or ordinary iterations. */
  public synchronized void beginContextRestoration() {
    restorationTargets = restorationReads < 8 ? information.snapshot() : null;
  }

  public synchronized void finishContextRestoration() {
    restorationTargets = null;
  }

  public synchronized boolean restoringContext() {
    return restorationTargets != null;
  }

  /** A reread is exempt only when its exact version and delivered interval were checkpointed. */
  private void restoreRange(
      String path, String version, int start, int end, List<ProgressEvidence> evidence) {
    if (restorationTargets == null || restorationReads >= 8) return;
    boolean target =
        restorationTargets.files().stream()
            .anyMatch(
                f ->
                    f.normalizedPath().equals(path)
                        && f.revision().equals(version)
                        && f.ranges().stream()
                            .anyMatch(r -> r.startInclusive() <= start && r.endExclusive() >= end));
    if (target
        && restoredRanges.observeRange(path, version, start, end).status()
            == InformationLedger.Status.NEW) {
      restorationReads++;
      evidence.add(
          new ProgressEvidence(
              ProgressEvent.CONTEXT_RESTORED,
              "Checkpointed range fetched for context restoration",
              path,
              version));
      if (restorationReads == 8) finishContextRestoration();
    }
  }

  /** Resume metadata shares the persisted restoration budget; revision churn never grants grace. */
  private void restoreCheckpoint(
      String name, String arguments, JsonNode output, List<ProgressEvidence> evidence) {
    if (restorationTargets == null || restorationReads >= 8 || output == null || failed(output))
      return;
    var input = parse(arguments);
    String task = input == null ? "" : input.path("taskId").asText();
    if (task.isBlank()) return;
    boolean valid =
        name.equals("checkpointShow")
            ? task.equals(output.path("taskId").asText())
                && output.path("schemaVersion").asInt() == 1
                && output.path("plan").isArray()
            : Set.of("CONTINUE", "CONFIRMATION_REQUIRED", "BLOCKED")
                    .contains(output.path("decision").asText())
                && output.path("usable").isArray()
                && output.path("blockers").isArray();
    if (valid && restoredCheckpoints.add(digest("checkpoint:" + name + ":" + task))) {
      restorationReads++;
      evidence.add(
          new ProgressEvidence(
              ProgressEvent.CONTEXT_RESTORED,
              "Saved task inspected for context restoration",
              name));
      if (restorationReads == 8) finishContextRestoration();
    }
  }

  /** 実行の停滞への対処で用いる情報状態の記録を返す。 */
  public InformationLedger.State informationSnapshot() {
    return information.snapshot();
  }

  /** 保存済みの状態を引き継ぐため、情報を復元する。 */
  public void restoreInformation(InformationLedger.State state) {
    information.restore(state);
  }

  /**
   * 後から状況を確認できるよう、計画比較の基準を記録する。
   *
   * <p>Called after the Run's plan has been initialized/restored, before any tool executes.
   */
  public synchronized void capturePlanBaseline() {
    initializePlanBaseline();
  }

  private void initializePlanBaseline() {
    completedSteps.clear();
    startedSteps.clear();
    doneSteps().forEach(step -> remember(completedSteps, step));
    if (plan != null)
      plan.steps().stream()
          .filter(s -> ActionPlan.STATUS_IN_PROGRESS.equals(s.status()))
          .forEach(s -> remember(startedSteps, s.id()));
  }

  /** 改訂一覧。 */
  private final Set<String> revisions = new HashSet<>();

  /** 失敗一覧。 */
  private final Set<String> failures = new HashSet<>();

  /** 完了手順一覧。 */
  private final Set<String> completedSteps = new HashSet<>();

  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProgressEvaluator(Path root) {
    this(root, null);
  }

  /** 指定された値や利用先を使って、ProgressEvaluatorを初期化する。 */
  public ProgressEvaluator(Path root, ActionPlan plan) {
    this.root = root.toAbsolutePath().normalize();
    this.plan = plan == null ? null : plan.bind().get();
    initializePlanBaseline();
  }

  /**
   * 実行の停滞への対処で受け渡す状態の記録を表す。
   *
   * @param files ファイル一覧
   * @param completedSteps 完了手順一覧
   */
  public record Snapshot(Map<Path, String> files, Set<String> completedSteps) {
    /** 受け取った情報からSnapshotを作成する。 */
    public Snapshot {
      files = Map.copyOf(files);
      completedSteps = Set.copyOf(completedSteps);
    }
  }

  /** 本処理に必要な文脈や条件を整えるため、ツールを準備する。 */
  public Snapshot beforeTool(String name, String arguments) {
    Map<Path, String> files = new LinkedHashMap<>();
    if (WRITE_TOOLS.contains(name)) collectPaths(parse(arguments), files);
    return new Snapshot(files, doneSteps());
  }

  /** 実行結果を後続の処理へ渡すため、ツールを反映する。 */
  public synchronized List<ProgressEvidence> afterTool(
      String name, String arguments, String result, Snapshot before) {
    readObservations.clear();
    readContentObserved = false;
    List<ProgressEvidence> evidence = new ArrayList<>();
    before
        .files()
        .forEach(
            (path, original) -> {
              String current = fingerprint(path);
              if (!original.equals("unavailable")
                  && !current.equals("unavailable")
                  && !original.equals(current)
                  && rememberProgress(revisions, path + ":" + current, path.toString(), evidence)) {
                evidence.add(
                    new ProgressEvidence(
                        ProgressEvent.STATE_CHANGED,
                        "File content/existence changed",
                        path.toString(),
                        current));
              }
            });
    for (String step : doneSteps()) {
      if (!before.completedSteps().contains(step)
          && rememberProgress(completedSteps, step, step, evidence)) {
        evidence.add(
            new ProgressEvidence(
                ProgressEvent.SUBGOAL_COMPLETED, "Plan step transitioned to DONE", step));
      }
    }
    if (plan != null)
      plan.steps().stream()
          .filter(s -> ActionPlan.STATUS_IN_PROGRESS.equals(s.status()))
          .filter(s -> rememberProgress(startedSteps, s.id(), s.id(), evidence))
          .forEach(
              s ->
                  evidence.add(
                      new ProgressEvidence(
                          ProgressEvent.STEP_CHANGED,
                          "Plan step started (completion still requires verification)",
                          s.id())));
    JsonNode output = parse(result);
    if (name.equals("checkpointShow") || name.equals("checkpointInspect")) {
      restoreCheckpoint(name, arguments, output, evidence);
      return List.copyOf(evidence);
    }
    if (name.equals("attachGoalStageEvidence")
        && output != null
        && !failed(output)
        && !output.path("stageId").asText().isBlank()
        && output.path("planVersion").asLong() > 0
        && output.path("observation").path("result").asText().equals("VERIFIED")
        && !output.path("observation").path("artifactVersions").isEmpty()
        && rememberProgress(
            revisions,
            "stage:" + digest(canonical(output)),
            output.path("stageId").asText(),
            evidence))
      evidence.add(
          new ProgressEvidence(
              ProgressEvent.STATE_CHANGED,
              "Stage conditions independently verified; host retains completion authority",
              output.path("stageId").asText(),
              digest(canonical(output.path("observation")))));
    if (name.equals("readReviewFile") && output != null && !failed(output) && output.has("range")) {
      var range = output.path("range");
      var span = range.path("span");
      readContentObserved =
          !output.path("content").asText().isBlank()
              && span.path("start").asInt(-1) >= 0
              && span.path("end").asInt(-1) > span.path("start").asInt(-1);
      var observed =
          information.observeRange(
              "review-chars:" + range.path("path").asText(),
              range.path("version").asText(),
              span.path("start").asInt() + 1,
              span.path("end").asInt() + 1);
      if (observed.status() == InformationLedger.Status.NEW)
        evidence.add(
            ProgressEvidence.acquiredRange(
                "New review characters fetched",
                range.path("path").asText(),
                range.path("version").asText()));
      else if (observed.status() == InformationLedger.Status.DUPLICATE)
        restoreRange(
            "review-chars:" + range.path("path").asText(),
            range.path("version").asText(),
            span.path("start").asInt() + 1,
            span.path("end").asInt() + 1,
            evidence);
      else if (observed.status() == InformationLedger.Status.UNKNOWN)
        unknown(name, "LEDGER_CAPACITY_OR_HISTORY", evidence);
      return List.copyOf(evidence);
    }
    if (name.equals("submitReviewResult") && output != null && output.hasNonNull("id")) {
      for (var entry : output.path("ranges"))
        if (entry.path("reviewed").asBoolean()
            && entry.path("delivered").asBoolean()
            && !entry.path("stale").asBoolean()) {
          var range = entry.path("range");
          var span = range.path("span");
          var observed =
              information.observeRange(
                  "reviewed:" + output.path("id").asText() + ":" + range.path("path").asText(),
                  range.path("version").asText(),
                  span.path("start").asInt() + 1,
                  span.path("end").asInt() + 1);
          if (observed.status() == InformationLedger.Status.NEW)
            evidence.add(
                new ProgressEvidence(
                    ProgressEvent.STATE_CHANGED,
                    "New review range verified",
                    range.path("path").asText(),
                    range.path("version").asText()));
          else if (observed.status() == InformationLedger.Status.UNKNOWN)
            unknown(name, "WORK_HISTORY_CAPACITY", evidence);
        }
      for (var finding : output.path("findings")) {
        var status = information.observe(digest(canonical(finding)));
        if (status == InformationLedger.Status.NEW)
          evidence.add(
              new ProgressEvidence(
                  ProgressEvent.STATE_CHANGED,
                  "Verified review finding added",
                  finding.path("path").asText(),
                  finding.path("version").asText()));
        else if (status == InformationLedger.Status.UNKNOWN)
          unknown(name, "WORK_HISTORY_CAPACITY", evidence);
      }
      return List.copyOf(evidence);
    }
    if (name.equals("readRawToolResult")) {
      try {
        var page = dev.mikoto2000.rei.core.contextbudget.RawResultPage.parse(result);
        if (page.content().isEmpty()) return List.copyOf(evidence);
        readContentObserved = !page.content().isBlank();
        var observed =
            information.observeRange(
                "raw:" + page.rawResultRef(),
                page.rawResultRef(),
                page.offset() + 1,
                page.nextOffset() + 1);
        if (observed.status() == InformationLedger.Status.NEW)
          evidence.add(
              ProgressEvidence.acquiredRange(
                  "New raw result characters", page.rawResultRef(), page.rawResultRef()));
        else if (observed.status() == InformationLedger.Status.DUPLICATE)
          restoreRange(
              "raw:" + page.rawResultRef(),
              page.rawResultRef(),
              page.offset() + 1,
              page.nextOffset() + 1,
              evidence);
        else if (observed.status() == InformationLedger.Status.UNKNOWN)
          unknown(name, "LEDGER_CAPACITY_OR_HISTORY", evidence);
      } catch (RuntimeException invalid) {
        unknown(name, "RAW_RANGE_UNAVAILABLE", evidence);
      }
      return List.copyOf(evidence);
    }
    if (name.equals("countFileLines") && output != null) {
      for (var file : output.path("files")) {
        if (failed(file)
            || !file.path("lineCount").isIntegralNumber()
            || file.path("lineCount").asLong() < 0
            || file.path("path").asText().isBlank()) continue;
        try {
          String source = normalizeSource(file.path("path").asText());
          addInformation(
              "line-count:" + source + ":" + file.path("lineCount").asLong(), source, evidence);
        } catch (RuntimeException invalid) {
          // Invalid paths are not observable file facts.
        }
      }
      return List.copyOf(evidence);
    }
    if (name.equals("getShellProcessStatus")
        && output != null
        && !output.path("processId").asText().isBlank()
        && output.path("found").isBoolean()
        && Set.of("STARTING", "RUNNING", "EXITED", "FAILED", "KILLED")
            .contains(output.path("status").asText())) {
      var facts = JSON.createObjectNode();
      for (String field : List.of("processId", "found", "status", "exitCode", "stdout", "stderr"))
        if (output.has(field)) facts.set(field, output.get(field));
      addInformation(
          "process-observation:" + canonical(facts), output.path("processId").asText(), evidence);
      return List.copyOf(evidence);
    }
    if (name.equals("applyTextChangeSet")) observeChangeSet(output, evidence);
    var webInformation =
        WEB_TOOLS.contains(name) ? webInformation(name, output, result) : List.<Information>of();
    String action = actionKey(name, arguments);
    if (failed(output)) {
      remember(failures, action);
    } else if ((!WEB_TOOLS.contains(name) || !webInformation.isEmpty())
        && (successful(output)
            || (READ_TOOLS.contains(name)
                && result != null
                && !result.isBlank()
                && !result.equals("null")))
        && failures.remove(digest(action))) {
      evidence.add(
          new ProgressEvidence(
              ProgressEvent.ERROR_RESOLVED, "Previously failing action succeeded", name));
    }
    if (WEB_TOOLS.contains(name)) {
      for (var item : webInformation)
        addInformation(item.key(), item.source(), item.revision(), evidence);
    } else if (name.equals("readSkillResource")) {
      collectSkillInformation(output, evidence);
    } else if (READ_TOOLS.contains(name)) {
      // Key by returned information, not arbitrary argument spelling or changing batch order.
      collectInformation(output, result, name, arguments, evidence);
    } else if (name.equals("runCommand") && successful(output)) {
      JsonNode stdout = output.get("stdout");
      if (stdout != null && !stdout.asText().isBlank()) {
        addInformation("command-output:" + canonical(stdout), name, evidence);
      }
    }
    return List.copyOf(evidence);
  }

  /** 後から経緯や使用量を確認できるよう、失敗情報を記録する。 */
  public synchronized void recordFailure(String name, String arguments) {
    remember(failures, actionKey(name, arguments));
  }

  /** 実行の停滞への対処において、失敗結果に該当するか判定する。 */
  public static boolean failedResult(String result) {
    return failed(parse(result));
  }

  /** 実行の停滞への対処で用いる操作キーを返す。 */
  public static String actionKey(String name, String arguments) {
    JsonNode node = parse(arguments);
    return name + ":" + digest(node == null ? Objects.toString(arguments, "") : canonical(node));
  }

  /** Instruction text is information, never evidence that project documents were reviewed. */
  private void collectSkillInformation(JsonNode output, List<ProgressEvidence> evidence) {
    if (output == null || !output.isArray()) {
      unknown("readSkillResource", "MALFORMED_RESULT", evidence);
      return;
    }
    for (var file : output) {
      if (failed(file)
          || file.path("path").asText().isBlank()
          || !file.path("content").isArray()
          || file.path("startLine").asInt() < 1) continue;
      String source;
      try {
        var relative = Path.of(file.path("path").asText()).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) continue;
        source = "skill-resource:" + relative.toString().replace('\\', '/');
        if (root.getFileSystem().getSeparator().equals("\\"))
          source = source.toLowerCase(Locale.ROOT);
      } catch (RuntimeException invalid) {
        continue;
      }
      int line = file.path("startLine").asInt();
      for (var text : file.path("content")) {
        if (text.isTextual() && !text.asText().isBlank())
          addInformation(source + ":" + line + ":" + text.asText(), source, evidence);
        line++;
      }
    }
  }

  /** 実行の停滞への対処に関する収集情報を処理する。 */
  private void collectInformation(
      JsonNode node, String raw, String name, String args, List<ProgressEvidence> evidence) {
    if (name.equals("searchFilePage") && node != null && node.path("files").isArray()) {
      collectInformation(
          node.path("files"), node.path("files").toString(), "searchAndRead", args, evidence);
      return;
    }
    if (node != null && node.isArray()) {
      JsonNode input = parse(args);
      JsonNode requests = input == null ? null : input.get("files");
      for (int index = 0; index < node.size(); index++) {
        JsonNode item = node.get(index);
        String itemArguments = args;
        if (name.equals("readMultiFile")
            && requests != null
            && requests.isArray()
            && requests.size() == node.size()) {
          JsonNode request = requests.get(index);
          try {
            // The built-in reader returns one result per request, in request order.
            if (request.hasNonNull("path")
                && item.hasNonNull("path")
                && normalizeSource(request.path("path").asText())
                    .equals(normalizeSource(item.path("path").asText()))
                && request.path("startLine").asInt(1) == item.path("startLine").asInt(0))
              itemArguments = request.toString();
          } catch (RuntimeException invalid) {
            /* Unknown association is handled conservatively below. */
          }
        }
        collectInformation(item, item.toString(), name, itemArguments, evidence);
      }
    } else if (node != null
        && !failed(node)
        && name.equals("grepMultiQuery")
        && node.has("matches")) {
      for (JsonNode match : node.path("matches")) {
        if (!match.hasNonNull("path") || !match.hasNonNull("content")) continue;
        try {
          String source = normalizeSource(match.path("path").asText());
          if (!match.path("content").asText().isBlank()) readContentObserved = true;
          addInformation(
              "grep:"
                  + source
                  + ":"
                  + match.path("line").asInt()
                  + ":"
                  + match.path("content").asText(),
              source,
              evidence);
        } catch (RuntimeException invalid) {
          /* Tool validates paths. */
        }
      }
    } else if (node == null
        && Set.of("readMultiFile", "searchAndRead", "grepMultiQuery").contains(name)) {
      unknown(name, "MALFORMED_RESULT", evidence);
    } else if (!failed(node)
        && raw != null
        && !raw.isBlank()
        && !raw.equals("null")
        && (node == null || !node.isContainerNode() || !node.isEmpty())) {
      JsonNode input = parse(args);
      String source =
          node != null && node.hasNonNull("path")
              ? node.get("path").asText()
              : input != null && input.hasNonNull("path")
                  ? input.get("path").asText()
                  : input != null && input.hasNonNull("pathStr")
                      ? input.get("pathStr").asText()
                      : name;
      boolean fileSource =
          node != null && node.hasNonNull("path")
              || input != null && (input.hasNonNull("path") || input.hasNonNull("pathStr"));
      if (fileSource) {
        try {
          source = normalizeSource(source);
        } catch (RuntimeException invalid) {
          return;
        }
      }
      if (fileSource && node != null && node.isObject() && node.hasNonNull("version")) {
        if (node.hasNonNull("symbol")) {
          // Symbol displays may omit lines: never infer a contiguous file range.
          addInformation(
              source + ":" + node.path("version").asText() + ":" + canonical(node.path("content")),
              source,
              evidence);
        } else if (node.has("sections")) {
          for (JsonNode section : node.path("sections"))
            observeRange(source, node.path("version").asText(), section, node, input, evidence);
        } else if (node.path("content").isArray()) {
          observeRange(source, node.path("version").asText(), node, node, input, evidence);
        } else unknown(source, "FILE_RANGE_UNAVAILABLE", evidence);
        return;
      }
      if (fileSource && node != null && hasReadContent(node.path("content")))
        readContentObserved = true;
      // Share line identities across file readers; metadata and argument spelling do not add facts.
      int line =
          Math.max(
              1,
              node != null
                  ? node.path("startLine").asInt(1)
                  : input == null ? 1 : input.path("startLine").asInt(1));
      if (node != null && node.has("content") && node.get("content").isArray()) {
        for (JsonNode content : node.get("content"))
          addInformation(source + ":" + line++ + ":" + canonical(content), source, evidence);
      } else if (fileSource
          && (node == null
              || node.isValueNode()
              || node.has("content") && node.get("content").isTextual())) {
        String text =
            node == null ? raw : node.isValueNode() ? node.asText() : node.get("content").asText();
        for (String content : text.lines().toList())
          addInformation(
              source + ":" + line++ + ":" + canonical(JSON.valueToTree(content)), source, evidence);
      } else {
        addInformation(source + ":" + (node == null ? raw : canonical(node)), source, evidence);
      }
    }
  }

  private static boolean hasReadContent(JsonNode content) {
    if (content.isTextual()) return !content.asText().isBlank();
    if (!content.isArray()) return false;
    boolean nonblank = false;
    for (var line : content) {
      if (!line.isTextual()) return false;
      nonblank |= !line.asText().isBlank();
    }
    return nonblank;
  }

  /** 状況を判断する根拠を得るため、対象範囲を観測する。 */
  private void observeRange(
      String source,
      String version,
      JsonNode section,
      JsonNode output,
      JsonNode input,
      List<ProgressEvidence> evidence) {
    int start = section.path("startLine").asInt(0);
    int originalStart = start;
    int count = section.path("content").size();
    if (count == 0) return;
    if (start < 1 || (long) start + count > Integer.MAX_VALUE) {
      unknown(source, "FILE_RANGE_UNAVAILABLE", evidence);
      return;
    }
    if (hasReadContent(section.path("content"))) readContentObserved = true;
    boolean firstFragment = false;
    boolean ambiguousOffset = false;
    if (input != null) {
      firstFragment = input.path("offset").asInt(0) > 0;
      Set<Integer> offsets = new HashSet<>();
      for (JsonNode request : input.path("files")) {
        if (request.hasNonNull("path") && request.path("startLine").asInt(1) == originalStart) {
          try {
            if (normalizeSource(request.path("path").asText()).equals(source)) {
              int offset = Math.max(0, request.path("offset").asInt(0));
              offsets.add(offset);
              if (offset > 0) firstFragment = true;
            }
          } catch (RuntimeException ignored) {
            /* Invalid paths remain unknown to the tool. */
          }
        }
      }
      ambiguousOffset = offsets.size() > 1;
    }
    int originalCount = count;
    // A byte-limited result can end in a fragment of its last line. Retain only complete lines.
    JsonNode next = output.path("nextRead");
    if (next.path("offset").asInt(0) > 0 && next.path("startLine").asInt(0) == start + count - 1)
      count--;
    else if (section.path("truncated").asBoolean() && !next.isObject()) count--;
    if (firstFragment && count > 0) {
      start++;
      count--;
    }
    if (count > 0) {
      var observed = information.observeRange(source, version, start, start + count);
      if (readObservations.size() < 4)
        readObservations.add(
            Map.of(
                "path",
                source.substring(0, Math.min(source.length(), 256)),
                "startLine",
                start,
                "endLine",
                start + count - 1,
                "classification",
                observed.status().name()));

      if (observed.status() == InformationLedger.Status.NEW) {
        evidence.add(
            ProgressEvidence.acquiredRange(
                "type=FILE_RANGE newLines=" + observed.newLines(), source, version));
      } else if (observed.status() == InformationLedger.Status.DUPLICATE) {
        if (output.path("delivery").path("state").asText().equals("RESTORABLE")
            && restorationReads < 8
            && output.path("delivery").path("evidence").isArray()
            && !output.path("delivery").path("evidence").isEmpty()
            && restoredRanges.observeRange(source, version, start, start + count).status()
                == InformationLedger.Status.NEW) {
          restorationReads++;
          evidence.add(
              new ProgressEvidence(
                  ProgressEvent.CONTEXT_RESTORED,
                  "Previously acquired source restored after prompt compression",
                  source,
                  version));
        } else restoreRange(source, version, start, start + count, evidence);
      } else if (observed.status() == InformationLedger.Status.UNKNOWN)
        unknown(source, "LEDGER_CAPACITY_OR_HISTORY", evidence);
    }
    if (count < originalCount
        && !information.covers(source, version, originalStart, originalStart + originalCount)) {
      // Deduplicate observed fragments, without claiming that their whole line was read.
      if (ambiguousOffset) unknown(source, "FILE_RANGE_UNAVAILABLE", evidence);
      // Identify the delivered fragment by its returned endpoint, not caller-controlled cursor
      // spelling.
      // Repeated characters at successive endpoints are new; changed arguments with the same page
      // are not.
      else
        addInformation(
            source
                + ":"
                + version
                + ":fragment:"
                + originalStart
                + ":end:"
                + next.path("startLine").asInt(-1)
                + ":"
                + next.path("offset").asInt(-1)
                + ":"
                + canonical(section.path("content")),
            source,
            version,
            true,
            evidence);
    }
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private static void unknown(String source, String reason, List<ProgressEvidence> evidence) {
    if (evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.INFORMATION_UNKNOWN))
      evidence.add(new ProgressEvidence(ProgressEvent.INFORMATION_UNKNOWN, reason, source));
  }

  /** 同じ意味の指定を共通に扱えるよう、取得元の表記をそろえる。 */
  private String normalizeSource(String source) {
    String path = root.resolve(source).normalize().toString();
    return root.getFileSystem().getSeparator().equals("\\") ? path.toLowerCase(Locale.ROOT) : path;
  }

  /** 新たな対象を管理できるよう、情報を追加する。 */
  private void addInformation(String key, String source, List<ProgressEvidence> evidence) {
    addInformation(key, source, digest(key), evidence);
  }

  /** 新たな対象を管理できるよう、情報を追加する。 */
  private void addInformation(
      String key, String source, String revision, List<ProgressEvidence> evidence) {
    addInformation(key, source, revision, false, evidence);
  }

  /** 新たな対象を管理できるよう、情報を追加する。 */
  private void addInformation(
      String key,
      String source,
      String revision,
      boolean acquisitionProgress,
      List<ProgressEvidence> evidence) {
    var status = information.observe(digest(key));
    if (status == InformationLedger.Status.NEW
        && evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.NEW_INFORMATION)) {
      evidence.add(
          new ProgressEvidence(
              ProgressEvent.NEW_INFORMATION,
              "Previously unseen tool information",
              source,
              revision,
              acquisitionProgress));
    } else if (status == InformationLedger.Status.UNKNOWN)
      unknown(source, "LEDGER_CAPACITY_OR_HISTORY", evidence);
  }

  /** 実行の停滞への対処で用いる完了手順一覧を返す。 */
  private Set<String> doneSteps() {
    if (plan == null) return Set.of();
    Set<String> done = new HashSet<>();
    plan.steps().stream()
        .filter(s -> ActionPlan.STATUS_DONE.equals(s.status()))
        .forEach(s -> done.add(s.id() + ":" + s.description()));
    return done;
  }

  /** Safe compact identity for diagnostics; document text is not logged. */
  public String currentStep() {
    return plan == null
        ? "unstructured"
        : plan.currentStep().map(s -> s.id()).orElse("unstructured");
  }

  public List<String> remainingSteps() {
    return plan == null
        ? List.of()
        : plan.steps().stream()
            .filter(
                s ->
                    !ActionPlan.STATUS_DONE.equals(s.status())
                        && !ActionPlan.STATUS_SKIPPED.equals(s.status()))
            .limit(100)
            .map(s -> s.id())
            .toList();
  }

  /** 実行の停滞への対処に関する収集パス一覧を処理する。 */
  private void collectPaths(JsonNode node, Map<Path, String> files) {
    if (node == null) return;
    if (node.isArray()) {
      node.forEach(item -> collectPaths(item, files));
      return;
    }
    if (!node.isObject()) return;
    node.properties()
        .forEach(
            entry -> {
              if (Set.of("path", "pathStr", "sourcePath", "destPath").contains(entry.getKey())
                  && entry.getValue().isTextual()) {
                try {
                  Path path = root.resolve(entry.getValue().asText()).normalize();
                  files.put(path, fingerprint(path));
                } catch (RuntimeException ignored) {
                  /* Tool validates invalid paths. */
                }
              } else if (entry.getValue().isContainerNode()) collectPaths(entry.getValue(), files);
            });
  }

  /** 実行の停滞への対処で用いる要求内容の照合値を返す。 */
  private String fingerprint(Path path) {
    if (!path.startsWith(root)) return "unavailable";
    String relative = root.relativize(path).toString();
    var observed = verifier.fingerprint(root, relative);
    if (observed.available()) return observed.sha256();
    if (observed.reason().equals("file_missing_or_not_regular")) {
      if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "absent";
      if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "directory";
    }
    return "unavailable";
  }

  /** 実行の停滞への対処において、rememberに該当するか判定する。 */
  private static boolean remember(Set<String> ledger, String key) {
    // Never evict old evidence: eviction could make an old read appear new again.
    return ledger.size() < LEDGER_LIMIT && ledger.add(digest(key));
  }

  /** Capacity exhaustion is inconclusive, never evidence that an observed change did not happen. */
  private static boolean rememberProgress(
      Set<String> ledger, String key, String source, List<ProgressEvidence> evidence) {
    if (remember(ledger, key)) return true;
    if (ledger.size() >= LEDGER_LIMIT && !ledger.contains(digest(key)))
      unknown(source, "WORK_HISTORY_CAPACITY", evidence);
    return false;
  }

  /** 状況を判断する根拠を得るため、変更集合を観測する。 */
  private void observeChangeSet(JsonNode node, List<ProgressEvidence> evidence) {
    if (node == null || !node.path("status").asText().equals("APPLIED")) return;
    String proposed = node.path("proposedSha256").asText();
    if (!proposed.matches("[a-f0-9]{64}")
        || proposed.equals(node.path("baselineSha256").asText())
        || !proposed.equals(node.path("currentSha256").asText())) return;
    try {
      Path path = root.resolve(node.path("path").asText()).normalize();
      if (proposed.equals(fingerprint(path))
          && rememberProgress(revisions, path + ":" + proposed, path.toString(), evidence))
        evidence.add(
            new ProgressEvidence(
                ProgressEvent.STATE_CHANGED,
                "Applied Change Set revision observed",
                path.toString(),
                proposed));
    } catch (RuntimeException invalid) {
      /* Unknown revision does not prove progress. */
    }
  }

  /**
   * 実行の停滞への対処で受け渡す情報を表す。
   *
   * @param key 照合キー
   * @param source 取得元
   * @param revision 改訂番号
   */
  private record Information(String key, String source, String revision) {}

  /** 実行の停滞への対処で用いるWeb情報を返す。 */
  private static List<Information> webInformation(String name, JsonNode output, String raw) {
    var items = new ArrayList<Information>();
    if (name.equals("webSearch")) {
      collectDiscoveries(output, items);
      return items;
    }
    if (!name.equals("searchKnowledge")) {
      collectWeb(output, items);
      return items;
    }
    String result = output != null && output.isTextual() ? output.asText() : raw;
    if (result == null) return items;
    for (String line : result.split("\\R")) {
      if (line.startsWith("Web external_untrusted sourceType=")) {
        int json = line.indexOf(": {");
        if (json >= 0) collectWeb(parse(line.substring(json + 2)), items);
      } else if (line.startsWith("Vector: ")) {
        int snippet = line.indexOf(" | snippet=");
        if (snippet >= 0 && !line.substring(snippet + 11).isBlank()) {
          String content = line.substring(snippet + 11);
          items.add(new Information("vector-body:" + content, "indexed-content", digest(content)));
        }
      }
    }
    return items;
  }

  /** 実行の停滞への対処に関する収集Discoveriesを処理する。 */
  private static void collectDiscoveries(JsonNode node, List<Information> items) {
    if (node == null) return;
    if (node.isArray()) {
      node.forEach(item -> collectDiscoveries(item, items));
      return;
    }
    if (!node.isObject() || failed(node)) return;
    String url = node.path("url").asText();
    if (!url.isBlank()) {
      String key = "web-discovery:" + digest(url) + ":" + node.path("snippet").asText();
      items.add(new Information(key, "web-source:" + digest(url), digest(key)));
    }
    collectDiscoveries(node.get("results"), items);
  }

  /** 実行の停滞への対処に関する収集Webを処理する。 */
  private static void collectWeb(JsonNode node, List<Information> items) {
    if (node == null) return;
    if (node.isArray()) {
      node.forEach(item -> collectWeb(item, items));
      return;
    }
    if (!node.isObject() || failed(node) || node.hasNonNull("errorType")) return;
    JsonNode content = node.get("content");
    if (content != null && content.isTextual() && !content.asText().isBlank()) {
      String url = node.path("url").asText(node.path("finalUrl").asText("unknown"));
      items.add(
          new Information(
              "web-body:" + content.asText(),
              "web-source:" + digest(url),
              digest(content.asText())));
    }
    for (String field : List.of("results", "primaryResults", "secondaryResults", "webContext"))
      collectWeb(node.get(field), items);
  }

  /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
  private static boolean failed(JsonNode node) {
    if (node == null) return false;
    if (node.isArray()) {
      for (JsonNode child : node) if (failed(child)) return true;
      return false;
    }
    return (node.hasNonNull("error") && !node.path("error").asText().isBlank())
        || (node.has("success") && !node.path("success").asBoolean())
        || (node.hasNonNull("exitCode") && node.path("exitCode").asInt() != 0)
        || node.path("timedOut").asBoolean()
        || node.path("isError").asBoolean()
        || node.path("status").asText().equals("failed");
  }

  /** 実行の停滞への対処において、successfulに該当するか判定する。 */
  private static boolean successful(JsonNode node) {
    return node != null
        && !failed(node)
        && (node.path("success").asBoolean()
            || (node.hasNonNull("exitCode") && node.path("exitCode").asInt(-1) == 0));
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private static JsonNode parse(String value) {
    try {
      return value == null ? null : JSON.readTree(value);
    } catch (Exception e) {
      return null;
    }
  }

  /** 実行の停滞への対処で用いる正規を返す。 */
  private static String canonical(JsonNode node) {
    if (node.isObject()) {
      Map<String, String> sorted = new TreeMap<>();
      node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
      return sorted.toString();
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      node.forEach(item -> items.add(canonical(item)));
      return items.toString();
    }
    return node.toString();
  }

  /** 実行の停滞への対処で用いる内容の照合値を返す。 */
  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
