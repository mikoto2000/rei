package dev.mikoto2000.rei.http;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 外部情報の取得に必要な取得対象範囲を扱う。
 *
 * <p>Capture explicitly before crossing executor boundaries.
 */
public final class FetchScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<FetchOperation> CURRENT = new ThreadLocal<>();

  /** 受付。 */
  private static final ThreadLocal<HostAdmission> ADMISSION = new ThreadLocal<>();

  /** 再確認。 */
  private static final ThreadLocal<Boolean> REFRESH = new ThreadLocal<>();

  /** 前回。 */
  private final FetchOperation previous;

  /** 前回受付。 */
  private final HostAdmission previousAdmission;

  /** 前回再確認。 */
  private final Boolean previousRefresh;

  /** 指定された値や利用先を使って、FetchScopeを初期化する。 */
  private FetchScope(FetchOperation operation, HostAdmission admission, boolean refresh) {
    previous = CURRENT.get();
    previousAdmission = ADMISSION.get();
    CURRENT.set(operation);
    previousRefresh = REFRESH.get();
    REFRESH.set(refresh);
    if (admission == null) ADMISSION.remove();
    else ADMISSION.set(admission);
  }

  /** 外部情報の取得で用いる現在を返す。 */
  public static FetchOperation current() {
    var operation = CURRENT.get();
    return operation == null ? FetchOperation.active() : operation;
  }

  /** 外部情報の取得で用いる利用開始を返す。 */
  public static FetchScope enter(FetchOperation operation) {
    return new FetchScope(operation, ADMISSION.get(), forceRefresh());
  }

  /** 外部情報の取得で用いる利用開始を返す。 */
  public static FetchScope enter(FetchOperation operation, HostAdmission admission) {
    return new FetchScope(operation, admission, forceRefresh());
  }

  /** 外部情報の取得で用いる利用開始を返す。 */
  public static FetchScope enter(
      FetchOperation operation, HostAdmission admission, boolean refresh) {
    return new FetchScope(operation, admission, refresh);
  }

  /** 外部情報の取得において、キャッシュの再確認指定に該当するか判定する。 */
  public static boolean forceRefresh() {
    return Boolean.TRUE.equals(REFRESH.get());
  }

  /** 指定されたキャッシュの再確認指定を反映した情報を作成する。 */
  public static FetchScope withForceRefresh(boolean refresh) {
    return new FetchScope(current(), ADMISSION.get(), refresh || forceRefresh());
  }

  /** 外部情報の取得で用いる受付を返す。 */
  public static HostAdmission admission() {
    return ADMISSION.get();
  }

  /** 外部情報の取得で用いる確保接続を返す。 */
  public static HostAdmission.Lease acquireConnection(java.net.URI uri, FetchOperation operation) {
    var admission = ADMISSION.get();
    return admission == null ? HostAdmission.NONE : admission.acquire(uri.getHost(), operation);
  }

  /** 外部情報の取得で用いる利用開始を返す。 */
  public static FetchScope enter(ToolContext context) {
    Object execution = context == null ? null : context.getContext().get(RunExecutionContext.KEY);
    return enter(
        execution instanceof RunExecutionContext run
            ? new FetchOperation(run::checkActive, Long.MAX_VALUE)
            : current());
  }

  /** 外部情報の取得で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
    if (previousAdmission == null) ADMISSION.remove();
    else ADMISSION.set(previousAdmission);
    if (previousRefresh == null) REFRESH.remove();
    else REFRESH.set(previousRefresh);
  }
}
