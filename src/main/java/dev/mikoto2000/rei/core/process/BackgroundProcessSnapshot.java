package dev.mikoto2000.rei.core.process;

import java.time.Instant;
import java.util.List;

/**
 * 外部プロセスの管理で受け渡すバックグラウンドプロセス状態の記録を表す。
 *
 * @param processId プロセス識別子
 * @param pid プロセス識別子
 * @param status 状態
 * @param exitCode 終了コード
 * @param startedAt 開始日時
 * @param endedAt 終了日時
 * @param elapsedSeconds 経過時間秒数
 * @param stdout 標準出力
 * @param stderr 標準エラー出力
 * @param found 見つかった
 * @param message メッセージ
 * @param diagnosis 診断
 */
public record BackgroundProcessSnapshot(
    String processId,
    long pid,
    BackgroundProcessStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant endedAt,
    double elapsedSeconds,
    List<String> stdout,
    List<String> stderr,
    boolean found,
    String message,
    BuildTestFailureDiagnosis diagnosis) {
  /** 外部プロセスの管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BackgroundProcessSnapshot(
      String processId,
      long pid,
      BackgroundProcessStatus status,
      Integer exitCode,
      Instant startedAt,
      Instant endedAt,
      double elapsedSeconds,
      List<String> stdout,
      List<String> stderr,
      boolean found,
      String message) {
    this(
        processId,
        pid,
        status,
        exitCode,
        startedAt,
        endedAt,
        elapsedSeconds,
        stdout,
        stderr,
        found,
        message,
        null);
  }

  /** 受け取った情報からBackgroundProcessSnapshotを作成する。 */
  public BackgroundProcessSnapshot {
    diagnosis = BuildTestFailureDiagnosis.process(status, exitCode, found, stdout, stderr, message);
  }
}
