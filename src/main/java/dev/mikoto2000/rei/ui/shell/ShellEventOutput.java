package dev.mikoto2000.rei.ui.shell;

/**
 * ターミナルでの対話に必要なシェルイベント出力を扱う。
 *
 * <p>Output boundary used by the Shell event renderer.
 */
public interface ShellEventOutput {
  /** 利用者に結果を伝えるため、シェルイベント出力を出力する。 */
  void print(String text);

  /** ターミナルでの対話に関する改行付きの出力を処理する。 */
  void println(String text);

  /** ターミナルでの対話に関する保存の確定を処理する。 */
  void flush();

  /** ターミナルでの対話に関する承認操作一覧を処理する。 */
  default void approvalActions(String project, String session, String id) {}

  /** ターミナルでの対話に関する承認必須を処理する。 */
  default void approvalRequired(String message) {
    println(dev.mikoto2000.rei.core.completion.ApprovalNotice.text(message));
  }
}
