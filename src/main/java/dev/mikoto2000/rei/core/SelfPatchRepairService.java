package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Self変更差分修復のユースケースを実現する。
 *
 * <p>Apply only explicitly supplied saved proposals after a complete failed check; rerun the whole
 * cycle.
 */
public final class SelfPatchRepairService {
  /**
   * 共通の実行基盤で受け渡す修復を表す。
   *
   * @param id 識別子
   * @param proposalSha256 提案内容の同一性を照合するSHA-256ハッシュ
   */
  public record Repair(String id, String proposalSha256) {}

  /**
   * 共通の実行基盤で受け渡す要求を表す。
   *
   * @param testCommand 検証コマンド
   * @param timeoutSeconds タイムアウトの秒数
   * @param repairs 修復一覧
   */
  public record Request(String testCommand, Integer timeoutSeconds, List<Repair> repairs) {
    /** 受け取った情報からRequestを作成する。 */
    public Request {
      repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }
  }

  /**
   * 共通の実行基盤で受け渡す受付記録を表す。
   *
   * @param id 識別子
   * @param status 状態
   * @param proposedSha256 提案内容内容の同一性を照合するSHA-256ハッシュ
   * @param currentSha256 現在内容の同一性を照合するSHA-256ハッシュ
   */
  public record Receipt(String id, String status, String proposedSha256, String currentSha256) {}

  /**
   * 共通の実行基盤で受け渡す結果を表す。
   *
   * @param root 作業ルート
   * @param status 状態
   * @param rounds 実行段階数
   * @param repairs 修復一覧
   * @param warnings 注意事項
   */
  public record Result(
      String root,
      String status,
      List<SelfPatchReviewService.Result> rounds,
      List<Receipt> repairs,
      List<String> warnings) {
    /** 受け取った情報からResultを作成する。 */
    public Result {
      rounds = List.copyOf(rounds);
      repairs = List.copyOf(repairs);
      warnings = List.copyOf(warnings);
    }
  }

  /** 共通の実行基盤に必要なCycleを扱う。 */
  @FunctionalInterface
  public interface Cycle {
    /** 要求された条件を満たすことを確認する。 */
    SelfPatchReviewService.Result verify(
        Path root, SelfPatchReviewService.Request request, long deadline) throws IOException;
  }

  /** 共通の実行基盤に必要なFixerを扱う。 */
  @FunctionalInterface
  public interface Fixer {
    /** 指定された内容を、現在の対象へ反映する。 */
    Receipt apply(Path root, Repair repair, long deadline) throws IOException;
  }

  /** 一回の診断・修復のまとまり。 */
  private final Cycle cycle;

  /** 取得記録。 */
  private final SelfPatchReviewService.Capture capture;

  /** 指摘された問題を修正する窓口。 */
  private final Fixer fixer;

  /** 指定された値や利用先を使って、SelfPatchRepairServiceを初期化する。 */
  public SelfPatchRepairService(Cycle cycle, SelfPatchReviewService.Capture capture, Fixer fixer) {
    this.cycle = cycle;
    this.capture = capture;
    this.fixer = fixer;
  }

  /** 要求された条件を満たすことを確認する。 */
  public Result verify(Path directory, Request request) throws IOException {
    return verify(directory, request, System.nanoTime() + Duration.ofSeconds(180).toNanos());
  }

  /** 要求された条件を満たすことを確認する。 */
  public Result verify(Path directory, Request request, long requestedDeadline) throws IOException {
    RunCancellation.propagate(null);
    validate(request);
    Path root = directory.toRealPath();
    long deadline =
        Math.min(requestedDeadline, System.nanoTime() + Duration.ofSeconds(180).toNanos());
    var rounds = new ArrayList<SelfPatchReviewService.Result>();
    var receipts = new ArrayList<Receipt>();
    var warnings = new ArrayList<String>();
    warnings.add(
        "Explicit saved fixes and static patch/test checks only; no semantic correctness or test coverage guarantee");
    var test = new SelfPatchReviewService.Request(request.testCommand(), request.timeoutSeconds());
    try {
      for (int index = 0; ; index++) {
        SelfPatchReviewService.remaining(deadline, 1);
        var result = cycle.verify(root, test, deadline);
        RunCancellation.propagate(null);
        rounds.add(result);
        if (!eligible(result)) return output(root, result.status(), rounds, receipts, warnings);
        if (index >= request.repairs().size())
          return output(root, "REPAIR_LIMIT_REACHED", rounds, receipts, warnings);
        var before = capture.capture(root, deadline);
        RunCancellation.propagate(null);
        if (!before.complete() || !before.version().equals(result.currentVersion()))
          return output(root, "PATCH_CHANGED", rounds, receipts, warnings);
        SelfPatchReviewService.remaining(deadline, 1);
        var repair = request.repairs().get(index);
        Receipt receipt;
        try {
          receipt = fixer.apply(root, repair, deadline);
          RunCancellation.propagate(null);
        } catch (IOException | RuntimeException error) {
          RunCancellation.propagate(error);
          receipts.add(new Receipt(repair.id(), "UNKNOWN", null, null));
          warnings.add(
              "Repair outcome requires saved Change Set inspection; no automatic resend or next repair");
          return output(root, "REPAIR_FAILED", rounds, receipts, warnings);
        }
        if (receipt == null) {
          receipts.add(new Receipt(repair.id(), "UNKNOWN", null, null));
          return output(root, "REPAIR_REJECTED", rounds, receipts, warnings);
        }
        receipts.add(receipt);
        if (!repair.id().equals(receipt.id())
            || !"APPLIED".equals(receipt.status())
            || receipt.proposedSha256() == null
            || receipt.proposedSha256().isBlank()
            || !receipt.proposedSha256().equals(receipt.currentSha256()))
          return output(root, "REPAIR_REJECTED", rounds, receipts, warnings);
        SelfPatchReviewService.remaining(deadline, 1);
        var after = capture.capture(root, deadline);
        RunCancellation.propagate(null);
        if (!after.complete()) return output(root, "BLOCKED", rounds, receipts, warnings);
        if (before.version().equals(after.version()))
          return output(root, "REPAIR_NO_PROGRESS", rounds, receipts, warnings);
      }
    } catch (IOException error) {
      RunCancellation.propagate(error);
      warnings.add("Shared repair cycle budget or patch inspection unavailable");
      return output(root, "BLOCKED", rounds, receipts, warnings);
    }
  }

  /** 共通の実行基盤において、対象の条件に該当するか判定する。 */
  private static boolean eligible(SelfPatchReviewService.Result result) {
    if ("FIX_REQUIRED".equals(result.status()))
      return result.review() != null
          && result.review().complete()
          && !result.review().findings().isEmpty();
    var test =
        switch (result.status()) {
          case "INITIAL_TEST_FAILED" -> result.initialTest();
          case "FINAL_TEST_FAILED" -> result.finalTest();
          default -> null;
        };
    return test != null
        && test.exitCode() != null
        && test.exitCode() != 0
        && !test.timedOut()
        && !test.logsTruncated();
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(Request request) {
    if (request == null
        || request.testCommand() == null
        || request.testCommand().isBlank()
        || request.testCommand().length() > 4096
        || request.timeoutSeconds() != null
            && (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 60)
        || request.repairs().isEmpty()
        || request.repairs().size() > 3)
      throw new IllegalArgumentException("Explicit test and 1..3 saved repairs required");
    var seen = new HashSet<String>();
    for (var repair : request.repairs())
      if (repair == null
          || repair.id() == null
          || repair.id().isBlank()
          || repair.id().length() > 128
          || repair.proposalSha256() == null
          || repair.proposalSha256().isBlank()
          || repair.proposalSha256().length() > 128
          || !seen.add(repair.id()))
        throw new IllegalArgumentException(
            "Unique bounded saved repair IDs and proposal hashes required");
  }

  /** 共通の実行基盤で用いる出力を返す。 */
  private static Result output(
      Path root,
      String status,
      List<SelfPatchReviewService.Result> rounds,
      List<Receipt> receipts,
      List<String> warnings) {
    return new Result(root.toString(), status, rounds, receipts, warnings);
  }
}
