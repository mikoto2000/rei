package dev.mikoto2000.rei.core.project;

import java.util.Objects;

/**
 * プロジェクトごとの作業環境に必要なプロジェクトクライアント対象範囲を扱う。
 *
 * <p>Request adapter; bind explicitly on each worker and always close with try-with-resources.
 */
public final class ProjectClientScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<ProjectClient> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final ProjectClient previous;

  /** 指定された値や利用先を使って、ProjectClientScopeを初期化する。 */
  private ProjectClientScope(ProjectClient client) {
    previous = CURRENT.get();
    CURRENT.set(Objects.requireNonNull(client));
  }

  /** 必要な対象を利用できるよう、プロジェクトクライアント対象範囲の利用を開始する。 */
  public static ProjectClientScope open(ProjectClient client) {
    return new ProjectClientScope(client);
  }

  /** プロジェクトごとの作業環境で用いる現在を返す。 */
  static ProjectClient current() {
    return CURRENT.get();
  }

  /**
   * プロジェクトごとの作業環境で用いる識別情報を返す。
   *
   * <p>Identity only, for binding interactive display aliases to one client.
   */
  public static Object identity() {
    return CURRENT.get();
  }

  /** プロジェクトごとの作業環境で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
