package dev.mikoto2000.rei.slack;

/**
 * Slackとの連携を継続できないSlack準備の状況を伝える例外。
 *
 * <p>Only fixed diagnostics may cross the connection initialization boundary.
 */
final class SlackSetupException extends RuntimeException {
  /** Slackとの連携におけるコードの選択肢。 */
  enum Code {
    /** ANOTHERバックエンド。 */
    ANOTHER_BACKEND,
    /** 作業領域否定許可。 */
    WORKSPACE_NOT_ALLOWED,
    /** 認証失敗。 */
    AUTHENTICATION_FAILED,
    /** 利用者検証失敗。 */
    USER_VALIDATION_FAILED,
    /** NO利用者利用者一覧。 */
    NO_HUMAN_USERS,
    /** プロジェクト否定登録済み。 */
    PROJECT_NOT_REGISTERED,
    /** 接続失敗。 */
    CONNECTION_FAILED
  }

  /** コード。 */
  final Code code;

  /** 指定された値や利用先を使って、SlackSetupExceptionを初期化する。 */
  SlackSetupException(Code code) {
    super(code.name());
    this.code = code;
  }
}
