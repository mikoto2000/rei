package dev.mikoto2000.rei.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.util.http.SlackHttpClient;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventBus;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Slackとの連携に必要なSlackSdk接続を扱う。
 *
 * <p>One backend owns one socket. All initialization and network work is off the startup thread.
 */
final class SlackSdkConnection implements SlackSocketLifecycle.Connection {
  /** 排他制御ファイル。 */
  private FileChannel lockFile;

  /** 排他制御。 */
  private FileLock lock;

  /** Slackとの連携に用いる窓口。 */
  private Slack slack;

  /** 通信の接続。 */
  private SocketModeClient socket;

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** 実行環境。 */
  private SlackRuntime runtime;

  /** 通信経路。 */
  private SdkSlackTransport transport;

  /** Slack上で共同作業の案内を行う窓口。 */
  private SlackAgentService collaboration;

  /** 必要な対象を利用できるよう、SlackSdk接続の利用を開始する。 */
  static SlackSdkConnection open(
      SlackProperties p,
      Path root,
      Clock clock,
      SessionRepository repository,
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      AgentEventBus bus,
      dev.mikoto2000.rei.conversation.ConversationTurnStore turns) {
    return open(p, root, clock, repository, projects, registry, runs, router, bus, turns, null);
  }

  /** 必要な対象を利用できるよう、SlackSdk接続の利用を開始する。 */
  static SlackSdkConnection open(
      SlackProperties p,
      Path root,
      Clock clock,
      SessionRepository repository,
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      AgentEventBus bus,
      dev.mikoto2000.rei.conversation.ConversationTurnStore turns,
      SlackAgentService collaboration) {
    var connection = new SlackSdkConnection();
    try {
      Files.createDirectories(root);
      connection.lockFile =
          FileChannel.open(
              root.resolve("slack-backend.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE);
      connection.lock = connection.lockFile.tryLock();
      if (connection.lock == null)
        throw new SlackSetupException(SlackSetupException.Code.ANOTHER_BACKEND);
      var config = new SlackConfig();
      config.setStatsEnabled(false);
      config.setHttpClientResponseHandlers(List.of());
      var http =
          new okhttp3.OkHttpClient.Builder()
              .connectTimeout(java.time.Duration.ofSeconds(5))
              .readTimeout(java.time.Duration.ofSeconds(10))
              .callTimeout(java.time.Duration.ofSeconds(15))
              .retryOnConnectionFailure(false)
              .followRedirects(false)
              .followSslRedirects(false)
              .build();
      connection.slack = Slack.getInstance(config, new SlackHttpClient(http));
      var methods = connection.slack.methods(System.getenv("SLACK_BOT_TOKEN"));
      var auth = methods.authTest(r -> r);
      if (!auth.isOk() || auth.getUserId() == null)
        throw new SlackSetupException(SlackSetupException.Code.AUTHENTICATION_FAILED);
      if (p.getAllowedWorkspaceIds() == null
          || !p.getAllowedWorkspaceIds().contains(auth.getTeamId()))
        throw new SlackSetupException(SlackSetupException.Code.WORKSPACE_NOT_ALLOWED);
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (p.getAllowedUserIds() == null || p.getAllowedUserIds().size() > 128)
        throw new SlackSetupException(SlackSetupException.Code.USER_VALIDATION_FAILED);
      var humans = new HashSet<String>();
      for (String user : p.getAllowedUserIds()) {
        if (Thread.currentThread().isInterrupted())
          throw new SlackSetupException(SlackSetupException.Code.CONNECTION_FAILED);
        var result = methods.usersInfo(r -> r.user(user));
        if (!result.isOk() || result.getUser() == null)
          throw new SlackSetupException(SlackSetupException.Code.USER_VALIDATION_FAILED);
        var identity = result.getUser();
        if (!identity.isBot()
            && !identity.isAppUser()
            && !identity.isDeleted()
            && !identity.isWorkflowBot()
            && !identity.isConnectorBot()) humans.add(user);
      }
      if (humans.isEmpty()) throw new SlackSetupException(SlackSetupException.Code.NO_HUMAN_USERS);
      if (p.getDmProjectId() != null
          && !p.getDmProjectId().isBlank()
          && projects.resolveById(p.getDmProjectId()).isEmpty())
        throw new SlackSetupException(SlackSetupException.Code.PROJECT_NOT_REGISTERED);
      for (String id : p.getChannelProjects().values())
        if (projects.resolveById(id).isEmpty())
          throw new SlackSetupException(SlackSetupException.Code.PROJECT_NOT_REGISTERED);
      var verified = new SlackProperties();
      verified.setEnabled(true);
      verified.setAllowedWorkspaceIds(Set.of(auth.getTeamId()));
      verified.setAllowedUserIds(Set.copyOf(humans));
      verified.setAllowedChannelIds(
          p.getAllowedChannelIds() == null ? Set.of() : Set.copyOf(p.getAllowedChannelIds()));
      verified.setChannelProjects(Map.copyOf(p.getChannelProjects()));
      verified.setDmProjectId(p.getDmProjectId());
      var store = new SlackStore(root);
      store.recoverSending();
      // Separate lifecycle object shares the repository, without changing Shell notification
      // selection.
      connection.runtime =
          new SlackRuntime(
              verified,
              store,
              auth.getUserId(),
              projects,
              new SessionLifecycle(repository, clock),
              registry,
              runs,
              router,
              clock);
      connection.runtime.onCompletedAnswer(
          (session, run) ->
              turns
                  .findRun(session, run)
                  .filter(
                      turn ->
                          turn.status()
                              == dev.mikoto2000.rei.conversation.ConversationTurnStore.Status
                                  .COMPLETED)
                  .map(dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn::assistantMessage)
                  .orElse(null));
      connection.transport = new SdkSlackTransport(methods, auth.getTeamId());
      if (collaboration != null) {
        collaboration.verifyIdentities(
            auth.getUserId(),
            auth.getTeamId(),
            user -> {
              try {
                var result = methods.usersInfo(r -> r.user(user));
                var identity = result.getUser();
                return result.isOk()
                        && identity != null
                        && identity.isBot()
                        && !identity.isDeleted()
                        && identity.getProfile() != null
                    ? identity.getProfile().getBotId()
                    : null;
              } catch (Exception unavailable) {
                return null;
              }
            });
        collaboration.recover();
        collaboration.attach(auth.getUserId(), auth.getTeamId(), connection.transport, false);
        connection.collaboration = collaboration;
      }
      var receiver = new SlackEnvelopeReceiver(connection.runtime, auth.getTeamId(), collaboration);
      connection.subscription =
          bus.subscribeBoundary(
              event -> {
                try {
                  connection.runtime.onEvent(event);
                } catch (RuntimeException error) {
                  org.slf4j.LoggerFactory.getLogger(SlackSdkConnection.class)
                      .warn("Slack reply persistence unavailable (details omitted)");
                }
              });
      connection.socket =
          connection.slack.socketMode(
              System.getenv("SLACK_APP_TOKEN"), SocketModeClient.Backend.JavaWebSocket);
      connection.socket.addWebSocketMessageListener(
          raw -> {
            String ack = receiver.receive(raw);
            if (ack != null) {
              try {
                connection.socket.sendSocketModeResponse(
                    com.slack.api.socket_mode.response.AckResponse.builder()
                        .envelopeId(ack)
                        .build());
              } catch (RuntimeException failed) {
                org.slf4j.LoggerFactory.getLogger(SlackSdkConnection.class)
                    .warn("Slack ACK delivery unavailable; receipt retained");
              }
            }
          });
      connection.socket.setAutoReconnectEnabled(true);
      connection.socket.connect();
      if (collaboration != null) collaboration.enableRequests();
      return connection;
    } catch (Exception error) {
      connection.close();
      if (error instanceof SlackSetupException setup) throw setup;
      throw new SlackSetupException(
          error instanceof OverlappingFileLockException
              ? SlackSetupException.Code.ANOTHER_BACKEND
              : SlackSetupException.Code.CONNECTION_FAILED);
    }
  }

  /** Slackとの連携に関するpumpを処理する。 */
  @Override
  public void pump() {
    runtime.tick();
    if (collaboration != null) collaboration.pump();
    runtime.send(transport);
  }

  /** Slackとの連携で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    if (collaboration != null) {
      collaboration.detach();
      collaboration = null;
    }
    if (socket != null) {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
      socket = null;
    }
    if (subscription != null) {
      subscription.unsubscribe();
      subscription = null;
    }
    if (slack != null) {
      try {
        slack.close();
      } catch (Exception ignored) {
      }
      slack = null;
    }
    if (lock != null) {
      try {
        lock.release();
      } catch (Exception ignored) {
      }
      lock = null;
    }
    if (lockFile != null) {
      try {
        lockFile.close();
      } catch (Exception ignored) {
      }
      lockFile = null;
    }
  }
}
