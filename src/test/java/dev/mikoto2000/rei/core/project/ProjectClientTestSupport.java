package dev.mikoto2000.rei.core.project;

/**
 * 検証条件を再現するためのプロジェクトクライアント検証補助。
 *
 * <p>Gives existing single-client tests an explicit session with automatic cleanup.
 */
public abstract class ProjectClientTestSupport {
  /** 検証に使う権限の範囲。 */
  private final java.util.Deque<ProjectClientScope> scopes = new java.util.ArrayDeque<>();

  /** {@code connect} の条件を再現するための補助情報を返す。 */
  protected ProjectService connect(ProjectService service) {
    scopes.push(service.newClient().open());
    return service;
  }

  /** {@code closeProjectClients} の条件を再現するため、検証用の操作や状態を扱う。 */
  @org.junit.jupiter.api.AfterEach
  void closeProjectClients() {
    while (!scopes.isEmpty()) scopes.pop().close();
  }
}
