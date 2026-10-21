package dev.mikoto2000.rei.core.execution;

/**
 * 処理の実行制御に必要な実行対象範囲を扱う。
 *
 * <p>Ownership adapter for background commands. It neither creates nor impersonates an AgentRun.
 */
public final class ExecutionScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<ActiveExecution> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final ActiveExecution previous;

  /** 指定された値や利用先を使って、ExecutionScopeを初期化する。 */
  private ExecutionScope(ActiveExecution execution) {
    previous = CURRENT.get();
    CURRENT.set(execution);
  }

  /** 必要な対象を利用できるよう、実行対象範囲の利用を開始する。 */
  public static ExecutionScope open(ActiveExecution execution) {
    return new ExecutionScope(execution);
  }

  /** 処理の実行制御で用いる現在を返す。 */
  public static ActiveExecution current() {
    return CURRENT.get();
  }

  /** 処理の実行制御で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
