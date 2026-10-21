package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない会話使用中の状況を伝える例外。 */
public final class SessionBusyException extends RuntimeException {
  /** 所属プロジェクトの識別子／会話の識別子／実行の識別子。 */
  private final String projectId, sessionId, runId;

  /** 状態。 */
  private final String status;

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SessionBusyException(String projectId, String sessionId, String runId) {
    this(projectId, sessionId, runId, null);
  }

  /** 指定された値や利用先を使って、SessionBusyExceptionを初期化する。 */
  public SessionBusyException(String projectId, String sessionId, String runId, String status) {
    super("Session already has an admitted conversation Run");
    this.projectId = projectId;
    this.sessionId = sessionId;
    this.runId = runId;
    this.status = status;
  }

  /** 実行の受付と状態管理で用いる所属プロジェクトの識別子を返す。 */
  public String projectId() {
    return projectId;
  }

  /** 実行の受付と状態管理で用いる会話の識別子を返す。 */
  public String sessionId() {
    return sessionId;
  }

  /** 依頼された操作を進めるため、識別子を実行する。 */
  public String runId() {
    return runId;
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public String status() {
    return status;
  }
}
