package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * プロジェクトを外部の接続先へ依頼する窓口。
 *
 * <p>Selection owned by one client/session. Retain this object across that client's requests.
 */
public final class ProjectClient {
  /** サービス。 */
  final ProjectService service;

  /** 選択内容。 */
  final AtomicReference<Path> selection;

  /** 会話の識別子。 */
  String sessionId;

  /** 基準時刻。 */
  long epoch;

  /** プロジェクトごとの作業環境で用いる選択内容基準時刻を返す。 */
  public synchronized long selectionEpoch() {
    return epoch;
  }

  /** 指定された値や利用先を使って、ProjectClientを初期化する。 */
  ProjectClient(ProjectService service, Path startup) {
    this.service = service;
    this.selection = new AtomicReference<>(startup);
  }

  /** 必要な対象を利用できるよう、プロジェクトクライアントの利用を開始する。 */
  public ProjectClientScope open() {
    return ProjectClientScope.open(this);
  }
}
