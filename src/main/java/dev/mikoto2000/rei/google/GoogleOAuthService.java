package dev.mikoto2000.rei.google;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.tasks.TasksScopes;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * GoogleO認証のユースケースを実現する。
 *
 * <p>Shares one credential, including SDK-triggered refreshes, across both Google APIs.
 */
@Service
@RequiredArgsConstructor
public class GoogleOAuthService {
  /** 利用者識別子。 */
  private static final String USER_ID = "user";

  /** この機能の動作設定。 */
  private final GoogleCalendarProperties properties;

  /** 認証情報。 */
  private Credential credential;

  /** イベント一覧。 */
  private dev.mikoto2000.rei.event.AgentEventFactory events;

  /** 通知の連携先。 */
  private dev.mikoto2000.rei.event.AgentEventBus bus;

  /** Googleサービスの認証で使用する実行イベント一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunEvents(
      dev.mikoto2000.rei.event.AgentEventFactory events,
      dev.mikoto2000.rei.event.AgentEventBus bus) {
    this.events = events;
    this.bus = bus;
  }

  /** Googleサービスの認証で用いる権限の確認を返す。 */
  public synchronized Credential authorize(NetHttpTransport transport, boolean force)
      throws Exception {
    if (credential != null && !force) {
      return credential;
    }
    AuthorizationCodeFlow flow = createFlow(transport);
    if (force) {
      credential = null;
      flow.getCredentialDataStore().delete(USER_ID);
    }
    LocalServerReceiver receiver =
        new LocalServerReceiver.Builder().setHost("127.0.0.1").setPort(8888).build();
    credential = new AuthorizationCodeInstalledApp(flow, receiver, this::browse).authorize(USER_ID);
    return credential;
  }

  /** Googleサービスの認証に関する認証を更新するトークンを処理する。 */
  public synchronized void refreshToken() throws Exception {
    refresh(authorize(GoogleNetHttpTransport.newTrustedTransport(), false));
  }

  /**
   * Googleサービスの認証において、再確認条件付きExpiringに該当するか判定する。
   *
   * <p>Never starts interactive authorization from a scheduler thread.
   */
  public synchronized boolean refreshIfExpiring(Duration advance) throws Exception {
    if (!isEnabled()) {
      return false;
    }
    if (credential == null) {
      if (properties.credentialsPath() == null
          || !Files.isRegularFile(Path.of(properties.credentialsPath()))
          || properties.tokensDirectory() == null
          || !Files.isDirectory(Path.of(properties.tokensDirectory()))) {
        return false;
      }
      Credential stored =
          createFlow(GoogleNetHttpTransport.newTrustedTransport()).loadCredential(USER_ID);
      if (stored == null
          || stored.getRefreshToken() == null
          || stored.getRefreshToken().isBlank()) {
        return false;
      }
      credential = stored;
    }
    if (credential == null) {
      return false;
    }
    String refreshToken = credential.getRefreshToken();
    if (refreshToken == null || refreshToken.isBlank()) {
      return false;
    }
    Long remaining = credential.getExpiresInSeconds();
    if (remaining != null && remaining > advance.toSeconds()) {
      return false;
    }
    refresh(credential);
    return true;
  }

  /** Googleサービスの認証において、有効状態かどうかを判定する。 */
  private boolean isEnabled() {
    return (properties.calendar() != null && properties.calendar().enabled())
        || (properties.task() != null && properties.task().enabled());
  }

  /** Googleサービスの認証に関する再確認を処理する。 */
  private void refresh(Credential target) throws IOException {
    if (!target.refreshToken()) {
      throw new IllegalStateException(
          "Google token refresh failed; run schedule auth or task auth");
    }
  }

  /** 新たな対象を利用できるよう、Flowを作成する。 */
  AuthorizationCodeFlow createFlow(NetHttpTransport transport) throws IOException {
    Path path = Path.of(properties.credentialsPath());
    if (!Files.exists(path)) {
      throw new IllegalStateException("Google OAuth credentials file was not found: " + path);
    }
    try (var in = Files.newInputStream(path)) {
      var json = GsonFactory.getDefaultInstance();
      return new GoogleAuthorizationCodeFlow.Builder(
              transport,
              json,
              GoogleClientSecrets.load(json, new InputStreamReader(in)),
              List.of(CalendarScopes.CALENDAR_EVENTS, TasksScopes.TASKS))
          .setDataStoreFactory(
              new FileDataStoreFactory(Path.of(properties.tokensDirectory()).toFile()))
          .setAccessType("offline")
          .build();
    }
  }

  /** Googleサービスの認証に関するbrowseを処理する。 */
  private void browse(String url) throws IOException {
    IO.println("Google OAuth を開始します。ブラウザが開かない場合は次の URL を開いてください:");
    IO.println(url);
    var context = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (context != null && events != null && bus != null)
      bus.publish(
          events
              .messageDelta(
                  context.runId(), "Google OAuth を開始します。ブラウザが開かない場合は次の URL を開いてください:\n" + url)
              .withOwnership(context));
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      Desktop.getDesktop().browse(URI.create(url));
    }
  }
}
