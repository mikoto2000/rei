package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Evidence;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Operation;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.OperationStatus;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 作業の中断と再開に必要な再開用の記録Reconcilerを扱う。
 *
 * <p>Read-only comparison. Process inspection never attaches, restarts or terminates saved
 * processes.
 */
@Component
public class CheckpointReconciler {
  /** プロセス一覧。 */
  private final BackgroundProcessManager processes;

  /** レビュー記録台帳。 */
  private dev.mikoto2000.rei.review.ReviewCoverageLedger reviewLedger;

  /** 作業の中断と再開で使用するレビュー記録台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setReviewLedger(dev.mikoto2000.rei.review.ReviewCoverageLedger ledger) {
    reviewLedger = ledger;
  }

  /** イベント保存先。 */
  private dev.mikoto2000.rei.event.ProjectAgentEventStore eventStore;

  /** 作業の中断と再開で使用するイベント保存先を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setEventStore(dev.mikoto2000.rei.event.ProjectAgentEventStore store) {
    eventStore = store;
  }

  /** 元の結果一覧。 */
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultStore rawResults;

  /** 作業の中断と再開で使用する元の結果一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setRawResults(dev.mikoto2000.rei.core.contextbudget.RawToolResultStore store) {
    rawResults = store;
  }

  /** やり取り一覧。 */
  private dev.mikoto2000.rei.conversation.ConversationTurnStore turns;

  /** 作業の中断と再開で使用するやり取り一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setTurns(dev.mikoto2000.rei.conversation.ConversationTurnStore store) {
    turns = store;
  }

  /** 指定された値や利用先を使って、CheckpointReconcilerを初期化する。 */
  public CheckpointReconciler(BackgroundProcessManager processes) {
    this.processes = processes;
  }

  /**
   * 作業の中断と再開で受け渡す結果を表す。
   *
   * @param decision 判断結果
   * @param usable 利用可能
   * @param changed 変更済み
   * @param recheck 再確認
   * @param unknownOperations 不明操作一覧
   * @param blockers 作業を妨げる課題
   * @param nextAction 次操作
   */
  public record Result(
      String decision,
      List<String> usable,
      List<String> changed,
      List<String> recheck,
      List<Operation> unknownOperations,
      List<String> blockers,
      String nextAction) {
    /** 作業の中断と再開において、自動再開安全性に該当するか判定する。 */
    public boolean automaticResumeSafe() {
      return decision.equals("CONTINUE")
          && unknownOperations.isEmpty()
          && blockers.isEmpty()
          && recheck.stream()
              .noneMatch(item -> item.startsWith("Process identity/reconnection unknown:"));
    }
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public Result check(PersistentCheckpoint saved, boolean leased) {
    active();
    var usable = new ArrayList<String>();
    var changed = new ArrayList<String>();
    var recheck = new ArrayList<String>();
    var blockers = new ArrayList<String>();
    Path root = Path.of(saved.projectRoot());
    if (!Files.isDirectory(root)) blockers.add("Project directory missing: " + root);
    if (leased) blockers.add("Task is owned by a live execution process");
    if ("ABANDONED".equals(saved.status())) blockers.add("Task abandoned; history retained");
    if ("RUNNING".equals(saved.status()) && !leased)
      recheck.add("Execution owner lost: interrupted, awaiting reconciliation");
    var currentGit = saved.git().containsKey("format") ? git(root) : legacyGit(root);
    if (!saved.git().equals(currentGit)) changed.add("Git branch/HEAD/work tree changed");
    else usable.add("Git snapshot matches (unavailable Git is not proof of clean work tree)");
    for (var file : saved.files().entrySet()) {
      active();
      String current = fingerprint(Path.of(file.getKey()));
      if (!file.getValue().equals(current)) changed.add("File changed/missing: " + file.getKey());
      else if (current.startsWith("sha256:")) usable.add("File matches: " + file.getKey());
      else recheck.add("File fingerprint unavailable: " + file.getKey());
    }
    for (var process : saved.processes()) {
      active();
      boolean same = false;
      if (processes != null && PersistentCheckpointRepository.OWNER.equals(process.owner())) {
        var managed = processes.status(process.processId(), 1);
        same =
            managed.found()
                && managed.pid() == process.pid()
                && Objects.equals(start(process.pid()), process.startedAt());
        if (same)
          usable.add(
              "Managed process: "
                  + process.processId()
                  + " status="
                  + managed.status()
                  + "; wait/query existing result, do not spawn again");
      }
      if (!same)
        recheck.add(
            "Process identity/reconnection unknown: "
                + process.processId()
                + "; do not restart or terminate");
    }
    if (reviewLedger != null)
      for (var evidence : saved.evidence())
        if (evidence.origin().equals("REVIEW_JOB_REFERENCE")) {
          try {
            var job =
                reviewLedger.get(
                    evidence.summary(),
                    new dev.mikoto2000.rei.review.ReviewCoverageLedger.Scope(
                        saved.projectId(), saved.sessionId(), saved.runId()));
            for (var target : job.targets()) {
              var path = root.resolve(target.path()).normalize();
              if (!path.startsWith(root) || !fingerprint(path).equals("sha256:" + target.version()))
                changed.add("Review source changed: " + target.path());
            }
            usable.add(
                "Review Job: "
                    + job.id()
                    + " status="
                    + job.status()
                    + "; read coverage before continuing");
          } catch (RuntimeException invalid) {
            blockers.add("Review Job unavailable or ownership mismatch: " + evidence.summary());
          }
        }
    var unknown =
        CheckpointOperationRecovery.interrupted(saved.operations()).stream()
            .filter(
                o -> o.status() == OperationStatus.STARTED || o.status() == OperationStatus.UNKNOWN)
            .toList();
    for (var operation : CheckpointOperationRecovery.interrupted(saved.operations()))
      if (operation.status() == OperationStatus.RETRYABLE)
        recheck.add(
            "Interrupted read requires reacquisition with current source version checks: "
                + operation.operationKey()
                + "; no successful or delivered result is established");
    if (saved.evidence().stream().anyMatch(e -> e.origin().equals("ASSISTANT_CLAIM")))
      recheck.add(
          "Assistant completion claims are unverified; validate against tool evidence and acceptance criteria");
    saved.evidence().stream()
        .filter(e -> e.origin().equals("TOOL_CONFIRMED"))
        .limit(20)
        .forEach(e -> usable.add("Confirmed tool result: " + e.toolCallId() + " " + e.summary()));
    if (turns != null) {
      var newer =
          turns.read(saved.sessionId()).stream()
              .filter(t -> t.createdAt() != null && t.createdAt().isAfter(saved.createdAt()))
              .map(t -> t.runId())
              .limit(10)
              .toList();
      if (!newer.isEmpty())
        recheck.add(
            "Newer Session turns may change instructions/acceptance criteria; inspect history: "
                + newer);
    }
    if (eventStore != null) {
      var ids = new HashSet<String>();
      saved.evidence().stream().map(Evidence::eventId).filter(Objects::nonNull).forEach(ids::add);
      eventStore
          .referenceStatus(saved.projectId(), ids)
          .forEach(
              (id, status) -> {
                if (!status.equals("available"))
                  recheck.add("Result reference " + id + ": " + status);
              });
    }
    if (!unknown.isEmpty())
      recheck.add(
          "Unknown operations require item-specific confirmation before repeating side effects");
    if (rawResults != null)
      for (var evidence : saved.evidence())
        if (evidence.origin().equals("RAW_RESULT_REFERENCE")
            && !rawResults.exists(saved.sessionId(), evidence.summary()))
          recheck.add("Raw result missing: " + evidence.summary());
    String next =
        changed.isEmpty() && recheck.isEmpty() ? saved.nextAction() : "差分と結果不明の操作を再確認し、未検証の工程から再計画";
    return new Result(
        !blockers.isEmpty() ? "BLOCKED" : !unknown.isEmpty() ? "CONFIRMATION_REQUIRED" : "CONTINUE",
        List.copyOf(usable),
        List.copyOf(changed),
        List.copyOf(recheck),
        unknown,
        List.copyOf(blockers),
        next);
  }

  /** 作業の中断と再開に関する活動中を処理する。 */
  static void active() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
  }

  /** 要求された処理を開始する。 */
  static String start(long pid) {
    return ProcessHandle.of(pid)
        .flatMap(p -> p.info().startInstant())
        .map(Object::toString)
        .orElse(null);
  }

  /** 作業の中断と再開で用いる要求内容の照合値を返す。 */
  public static String fingerprint(Path file) {
    active();
    try {
      if (!Files.exists(file)) return "missing";
      if (!Files.isRegularFile(file)) return "not-regular";
      if (Files.size(file) > 16 * 1024 * 1024)
        return "metadata:" + Files.size(file) + ":" + Files.getLastModifiedTime(file);
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          active();
          digest.update(buffer, 0, n);
        }
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
      return "unavailable";
    }
  }

  /** 作業の中断と再開で用いるGitの情報を返す。 */
  public static Map<String, String> git(Path root) {
    String status =
        readGit(
            root,
            "status",
            "--porcelain=v2",
            "--branch",
            "--no-ahead-behind",
            "--untracked-files=normal");
    if (status.equals("unavailable") || status.equals("oversized:recheck"))
      return Map.of("format", "porcelain-v2", "branch", status, "head", status, "workTree", status);
    String branch = "unavailable", head = "unavailable";
    var changes = new ArrayList<String>();
    for (String line : status.lines().toList()) {
      if (line.startsWith("# branch.oid ")) head = line.substring(13);
      else if (line.startsWith("# branch.head ")) branch = line.substring(14);
      else if (!line.startsWith("# ")) changes.add(line);
    }
    return Map.of(
        "format",
        "porcelain-v2",
        "branch",
        branch,
        "head",
        head,
        "workTree",
        String.join("\n", changes));
  }

  /** Older persisted snapshots use porcelain v1; compare them without a format-only change. */
  private static Map<String, String> legacyGit(Path root) {
    active();
    var result = new LinkedHashMap<String, String>();
    result.put("branch", readGit(root, "rev-parse", "--abbrev-ref", "HEAD"));
    result.put("head", readGit(root, "rev-parse", "HEAD"));
    result.put("workTree", readGit(root, "status", "--porcelain=v1", "--untracked-files=normal"));
    return Map.copyOf(result);
  }

  /** 必要な内容を参照できるよう、Gitの情報を読み取る。 */
  private static String readGit(Path root, String... args) {
    active();
    Process process = null;
    try {
      var command =
          new ArrayList<>(
              List.of(
                  "git",
                  "--no-optional-locks",
                  "-c",
                  "core.fsmonitor=false",
                  "-C",
                  root.toString()));
      command.addAll(List.of(args));
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var running = process;
      var output =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return running.getInputStream().readNBytes(65537);
                } catch (java.io.IOException e) {
                  return new byte[0];
                }
              });
      if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return "unavailable";
      byte[] bytes = output.get(1, TimeUnit.SECONDS);
      if (bytes.length > 65536) return "oversized:recheck";
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException();
    } catch (java.io.IOException | ExecutionException | TimeoutException e) {
      return "unavailable";
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }
}
