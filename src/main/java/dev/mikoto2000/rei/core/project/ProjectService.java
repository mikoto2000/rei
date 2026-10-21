package dev.mikoto2000.rei.core.project;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** プロジェクトのユースケースを実現する。 */
@Service
public class ProjectService {
  /** 起動時ディレクトリ。 */
  private final Path startupDirectory;

  /** 登録台帳。 */
  private final ProjectRegistry registry;

  /** 対象を限定した。 */
  private final boolean scoped;

  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** 通知クライアント。 */
  private volatile ProjectClient notificationClient;

  /** 通知会話。 */
  private volatile AgentRunContext notificationConversation;

  /**
   * プロジェクトごとの作業環境で受け渡す通知選択内容を表す。
   *
   * @param project プロジェクト
   * @param conversationId 会話の識別子
   */
  public record NotificationSelection(ProjectContext project, String conversationId) {}

  /**
   * プロジェクトごとの作業環境に必要な通知関連付けを扱う。
   *
   * <p>Explicit Shell lifetime binding; scheduled workers must never rely on inheriting ThreadLocal
   * state.
   */
  public interface NotificationBinding extends AutoCloseable {
    /** プロジェクトごとの作業環境で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /** プロジェクトごとの作業環境で用いる通知一覧追加入力を返す。 */
  public NotificationBinding notificationsFollow(ProjectClient client) {
    var previous = notificationClient;
    notificationClient = client;
    return () -> {
      if (notificationClient == client) notificationClient = previous;
    };
  }

  /** プロジェクトごとの作業環境に関するremember会話を処理する。 */
  public void rememberConversation(AgentRunContext context) {
    notificationConversation = context;
  }

  /** プロジェクトごとの作業環境で用いる通知排他制御を返す。 */
  public Object notificationLock() {
    var scopedClient = ProjectClientScope.current();
    var selected =
        scopedClient != null && scopedClient.service == this ? scopedClient : notificationClient;
    return selected != null ? selected : sessions != null ? sessions : this;
  }

  /** プロジェクトごとの作業環境で用いる通知選択内容を返す。 */
  public NotificationSelection notificationSelection() {
    var scopedClient = ProjectClientScope.current();
    var selected =
        scopedClient != null && scopedClient.service == this ? scopedClient : notificationClient;
    if (selected != null)
      synchronized (selected) {
        return new NotificationSelection(
            registry.resolve(selected.selection.get()), selected.sessionId);
      }
    var recent = notificationConversation;
    if (recent != null)
      return new NotificationSelection(
          new ProjectContext(
              recent.projectId(),
              recent.projectRoot().getFileName().toString(),
              recent.projectRoot()),
          recent.conversationId());
    return new NotificationSelection(registry.resolve(startupDirectory), null);
  }

  /** 条件に合う対象を利用するため、通知既定を選択する。 */
  public void selectNotificationDefault(String projectId, String conversationId) {
    var scopedClient = ProjectClientScope.current();
    var selected =
        scopedClient != null && scopedClient.service == this ? scopedClient : notificationClient;
    if (selected != null)
      synchronized (selected) {
        if (selected.sessionId == null
            && registry.resolve(selected.selection.get()).id().equals(projectId)) {
          selected.sessionId = conversationId;
          selected.epoch++;
        }
      }
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectService() {
    this(ReiPaths.startupDirectory(), new ProjectRegistry(ReiPaths.projectsFilePath()));
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ProjectService(SessionRepository sessions) {
    this(ReiPaths.startupDirectory(), new ProjectRegistry(ReiPaths.projectsFilePath()), sessions);
  }

  /**
   * プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。
   *
   * <p>Compatibility constructor for callers that only use path management.
   */
  public ProjectService(Path startup, Path file) {
    this(startup, new ProjectRegistry(file), false);
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectService(Path startup, ProjectRegistry registry) {
    this(startup, registry, true);
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectService(Path startup, ProjectRegistry registry, SessionRepository sessions) {
    this(startup, registry, true, sessions);
  }

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  private ProjectService(Path startup, ProjectRegistry registry, boolean scoped) {
    this(startup, registry, scoped, null);
  }

  /** 指定された値や利用先を使って、ProjectServiceを初期化する。 */
  private ProjectService(
      Path startup, ProjectRegistry registry, boolean scoped, SessionRepository sessions) {
    this.startupDirectory = startup.toAbsolutePath().normalize();
    this.registry = registry;
    this.scoped = scoped;
    this.sessions = sessions;
  }

  /** プロジェクトごとの作業環境で用いる起動時ディレクトリを返す。 */
  public Path startupDirectory() {
    return startupDirectory;
  }

  /** 新たな対象を利用できるよう、クライアントを作成する。 */
  public ProjectClient newClient() {
    return new ProjectClient(this, startupDirectory);
  }

  /** プロジェクトごとの作業環境で用いる現在クライアントを返す。 */
  public ProjectClient currentClient() {
    return client();
  }

  /**
   * プロジェクトごとの作業環境で用いる選択内容所属を返す。
   *
   * <p>Snapshot a specifically captured client's selection without worker ThreadLocal inheritance.
   */
  public NotificationSelection selectionOf(ProjectClient selected) {
    if (selected == null || selected.service != this)
      throw new IllegalArgumentException("Foreign project client");
    synchronized (selected) {
      return new NotificationSelection(
          registry.resolve(selected.selection.get()), selected.sessionId);
    }
  }

  /** プロジェクトごとの作業環境で用いる現在会話の識別子を返す。 */
  public String currentSessionId() {
    synchronized (client()) {
      return client().sessionId;
    }
  }

  /** 条件に合う対象を利用するため、会話を選択する。 */
  public void selectSession(String sessionId) {
    synchronized (client()) {
      if (!Objects.equals(client().sessionId, sessionId)) client().epoch++;
      client().sessionId = sessionId;
    }
  }

  /** プロジェクトごとの作業環境で用いる選択中シェル会話を返す。 */
  public static String selectedShellSession() {
    var client = ProjectClientScope.current();
    if (client == null) return null;
    synchronized (client) {
      return client.sessionId;
    }
  }

  /** プロジェクトごとの作業環境で用いるクライアントを返す。 */
  private ProjectClient client() {
    var client = ProjectClientScope.current();
    if (client == null || client.service != this)
      throw new IllegalStateException("A project client scope is required");
    return client;
  }

  /**
   * プロジェクトごとの作業環境で用いる現在プロジェクトを返す。
   *
   * <p>Client selection for interactive controls; execution ownership is contextForOperation().
   */
  public Path currentProject() {
    var client = ProjectClientScope.current();
    return client == null ? startupDirectory : client().selection.get();
  }

  /** プロジェクトごとの作業環境で用いる現在文脈を返す。 */
  public ProjectContext currentContext() {
    return registry.resolve(currentProject());
  }

  /** プロジェクトごとの作業環境で用いる登録済みプロジェクト一覧を返す。 */
  public List<ProjectContext> registeredProjects() {
    return registry.list();
  }

  /** プロジェクトごとの作業環境で用いる完了条件プロジェクト一覧を返す。 */
  public List<ProjectContext> completionProjects() {
    return registry.completionSnapshot();
  }

  /** プロジェクトごとの作業環境で用いる完了条件プロジェクトパス文字列一覧を返す。 */
  public static List<String> completionProjectPathStrings() {
    var client = ProjectClientScope.current();
    if (client == null) return List.of(ReiPaths.startupDirectory().toString());
    var paths = new LinkedHashSet<String>();
    paths.add(client.service.startupDirectory.toString());
    client.service.completionProjects().forEach(project -> paths.add(project.root().toString()));
    return List.copyOf(paths);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Path> list() {
    var paths = new LinkedHashSet<Path>();
    paths.add(startupDirectory);
    registry.list().forEach(p -> paths.add(p.root()));
    return List.copyOf(paths);
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  public Path add(String directory) {
    return registry.resolve(resolveDirectory(directory)).root();
  }

  /** プロジェクトごとの作業環境で用いるcdを返す。 */
  public Path cd(String directory) {
    var client = client();
    var context = registry.resolve(resolveDirectory(directory));
    synchronized (client) {
      if (!client.selection.get().equals(context.root())) {
        client.epoch++;
        var latest =
            sessions == null
                ? List.<dev.mikoto2000.rei.application.session.SessionMetadata>of()
                : sessions.findPage(context.id(), null, 1);
        client.sessionId = latest.isEmpty() ? null : latest.getFirst().sessionId();
      }
      client.selection.set(context.root());
    }
    return context.root();
  }

  /** 指定された対象を管理から取り除く。 */
  public Path remove(String directory) {
    var client = client();
    Path path = resolveDirectory(directory);
    registry.remove(path);
    synchronized (client) {
      if (client.selection.get().equals(path)) {
        client.epoch++;
        client.selection.set(startupDirectory);
        client.sessionId = null;
      }
    }
    return path;
  }

  /**
   * プロジェクトごとの作業環境で用いる文脈対象操作を返す。
   *
   * <p>Running work keeps its captured ownership even when the client changes its selection.
   */
  public static ProjectContext contextForOperation() {
    var execution = dev.mikoto2000.rei.core.execution.ExecutionScope.current();
    if (execution != null)
      return new ProjectContext(
          execution.projectId(),
          execution.projectRoot().getFileName().toString(),
          execution.projectRoot());
    var run = AgentRunScope.current();
    if (run != null && run.projectId() != null)
      return new ProjectContext(
          run.projectId(), run.projectRoot().getFileName().toString(), run.projectRoot());
    if (run != null) return null;
    var client = ProjectClientScope.current();
    ProjectService service = client == null ? null : client.service;
    return service != null && service.scoped && Files.isDirectory(service.currentProject())
        ? service.currentContext()
        : null;
  }

  /** プロジェクトごとの作業環境で用いる登録済みプロジェクトパス文字列一覧を返す。 */
  public static List<String> registeredProjectPathStrings() {
    var client = ProjectClientScope.current();
    var service = client == null ? null : client.service;
    return service == null
        ? List.of(ReiPaths.startupDirectory().toString())
        : service.list().stream().map(Path::toString).toList();
  }

  /** プロジェクトごとの作業環境で用いる現在プロジェクトまたは起動時ディレクトリを返す。 */
  public static Path currentProjectOrStartupDirectory() {
    var client = ProjectClientScope.current();
    var service = client == null ? null : client.service;
    return service == null ? ReiPaths.startupDirectory() : service.currentProject();
  }

  /** 保存された情報を利用できるよう、プロジェクトパス文字列一覧を読み込む。 */
  static List<String> loadProjectPathStrings(Path startup, Path file) {
    var paths = new LinkedHashSet<String>();
    paths.add(startup.toAbsolutePath().normalize().toString());
    if (Files.isRegularFile(file)) {
      try {
        if (Files.readString(file).stripLeading().startsWith("{"))
          new ProjectRegistry(file).list().forEach(p -> paths.add(p.root().toString()));
        else
          Files.readAllLines(file).stream()
              .filter(s -> !s.isBlank())
              .forEach(s -> paths.add(Path.of(s.strip()).toAbsolutePath().normalize().toString()));
      } catch (java.io.IOException e) {
        throw new IllegalStateException("Cannot read project list", e);
      }
    }
    return List.copyOf(paths);
  }

  /** 指定や設定に応じた対象を利用するため、ディレクトリを確定する。 */
  private Path resolveDirectory(String directory) {
    return resolveDirectory(currentProject(), directory);
  }

  /**
   * 指定や設定に応じた対象を利用するため、Existingディレクトリを確定する。
   *
   * <p>Read-only validation, shared with registry selection, before application startup.
   */
  public static Path resolveExistingDirectory(Path base, String directory) {
    return ProjectRegistry.canonical(resolveDirectory(base, directory));
  }

  /** 指定や設定に応じた対象を利用するため、ディレクトリを確定する。 */
  private static Path resolveDirectory(Path base, String directory) {
    if (directory == null || directory.isBlank())
      throw new IllegalArgumentException("directory must not be blank");
    Path path = Path.of(directory);
    return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
  }
}
