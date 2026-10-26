package dev.mikoto2000.rei.core.chat;

/**
 * 会話の実行に必要なエージェント実行対象範囲を扱う。
 *
 * <p>Lexical adapter for existing synchronous services; explicitly re-entered at async boundaries.
 */
public final class AgentRunScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final AgentRunContext previous;

  /** 指定された値や利用先を使って、AgentRunScopeを初期化する。 */
  private AgentRunScope(AgentRunContext context) {
    previous = CURRENT.get();
    if (context == null) CURRENT.remove();
    else CURRENT.set(context);
    dev.mikoto2000.rei.context.RunIdentity.setCurrentRunId(
        context == null ? null : context.runId());
  }

  /** 必要な対象を利用できるよう、エージェント実行対象範囲の利用を開始する。 */
  public static AgentRunScope open(AgentRunContext context) {
    return new AgentRunScope(context);
  }

  /** 会話の実行で用いる現在を返す。 */
  public static AgentRunContext current() {
    return CURRENT.get();
  }

  /** 会話の実行で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
    dev.mikoto2000.rei.context.RunIdentity.setCurrentRunId(
        previous == null ? null : previous.runId());
  }
}
