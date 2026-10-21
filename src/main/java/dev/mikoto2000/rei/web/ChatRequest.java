package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すチャット要求を表す。
 *
 * @param message メッセージ
 * @param projectId 所属プロジェクトの識別子
 * @param sessionId 会話の識別子
 * @param mode 動作モード
 */
public record ChatRequest(
    String message,
    String projectId,
    String sessionId,
    dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
  /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatRequest(String message, String projectId, String sessionId) {
    this(message, projectId, sessionId, null);
  }
}
