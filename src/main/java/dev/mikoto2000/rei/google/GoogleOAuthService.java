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
@org.springframework.boot.context.properties.EnableConfigurationProperties(GmailProperties.class)
public class GoogleOAuthService implements dev.mikoto2000.rei.secretary.SecretaryProvider {
  private GmailProperties gmail = new GmailProperties(false, false, false);
  private volatile boolean secretaryAuthFailed;

  @org.springframework.beans.factory.annotation.Value("${rei.secretary.enabled:false}")
  private boolean secretaryEnabled;

  private static final java.util.concurrent.Semaphore SECRETARY_NETWORK =
      new java.util.concurrent.Semaphore(2);
  private static final com.fasterxml.jackson.databind.ObjectMapper SECRETARY_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper(
          com.fasterxml.jackson.core.JsonFactory.builder()
              .streamReadConstraints(
                  com.fasterxml.jackson.core.StreamReadConstraints.builder()
                      .maxNestingDepth(32)
                      .maxStringLength(262144)
                      .build())
              .build());

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setGmailProperties(GmailProperties gmail) {
    this.gmail = gmail;
  }

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
    boolean fresh = force || flow.loadCredential(USER_ID) == null;
    LocalServerReceiver receiver =
        new LocalServerReceiver.Builder().setHost("127.0.0.1").setPort(8888).build();
    credential = new AuthorizationCodeInstalledApp(flow, receiver, this::browse).authorize(USER_ID);
    if (fresh) writeSecretaryScopes();
    secretaryAuthFailed = false;
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
        || (properties.task() != null && properties.task().enabled())
        || gmail.enabled();
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
              secretaryScopes())
          .setDataStoreFactory(
              new FileDataStoreFactory(Path.of(properties.tokensDirectory()).toFile()))
          .setAccessType("offline")
          .setRequestInitializer(
              request -> {
                request.setLoggingEnabled(false);
                request.setCurlLoggingEnabled(false);
                request.setConnectTimeout(10000);
                request.setReadTimeout(15000);
                request.setNumberOfRetries(0);
              })
          .build();
    }
  }

  /**
   * Request only scopes for explicitly enabled APIs. Existing credentials need explicit re-consent.
   */
  public List<String> secretaryScopes() {
    var scopes = new java.util.ArrayList<String>();
    if (properties.calendar() != null && properties.calendar().enabled()) {
      scopes.add(CalendarScopes.CALENDAR_EVENTS);
      if (secretaryEnabled) scopes.add("https://www.googleapis.com/auth/calendar.events.freebusy");
    }
    if (properties.task() != null && properties.task().enabled()) scopes.add(TasksScopes.TASKS);
    if (gmail.enabled()) {
      scopes.add("https://www.googleapis.com/auth/gmail.readonly");
      if (gmail.draftsEnabled()) scopes.add("https://www.googleapis.com/auth/gmail.compose");
      else if (gmail.sendEnabled()) scopes.add("https://www.googleapis.com/auth/gmail.send");
    }
    return List.copyOf(scopes);
  }

  private static String secretaryHash(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private Path secretaryScopePath() {
    return Path.of(properties.tokensDirectory()).resolve("rei-requested-scopes.sha256");
  }

  private boolean secretaryScopesMatch() {
    try {
      return Files.readString(secretaryScopePath())
          .strip()
          .equals(secretaryHash(String.join("\n", secretaryScopes())));
    } catch (java.io.IOException | RuntimeException missing) {
      return false;
    }
  }

  private void writeSecretaryScopes() throws IOException {
    Files.writeString(
        secretaryScopePath(),
        secretaryHash(String.join("\n", secretaryScopes())),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Noninteractive load/refresh only. Polling must never open the browser. */
  protected synchronized Credential secretaryCredential() {
    if (secretaryAuthFailed || !secretaryScopesMatch())
      throw new Failure("AUTH_REQUIRED", 0, false);
    try {
      if (credential == null)
        credential =
            createFlow(GoogleNetHttpTransport.newTrustedTransport()).loadCredential(USER_ID);
      if (credential == null || credential.getRefreshToken() == null)
        throw new Failure("AUTH_REQUIRED", 0, false);
      Long expires = credential.getExpiresInSeconds();
      if (expires == null || expires <= 60) refresh(credential);
      return credential;
    } catch (Failure denied) {
      throw denied;
    } catch (java.io.IOException
        | java.security.GeneralSecurityException
        | IllegalStateException unavailable) {
      secretaryAuthFailed = true;
      throw new Failure("AUTH_REQUIRED", 0, false);
    }
  }

  /** Bind an approval to the credential, independent of access-token refreshes. */
  public String secretaryIdentity() {
    return secretaryHash(secretaryCredential().getRefreshToken());
  }

  /** Metadata only; CONFIGURED is deliberately not a claim of live server authorization. */
  public Auth secretaryGoogleStatus(boolean enabled) {
    if (!enabled) return new Auth("DISABLED", "", List.of(), "integration_disabled");
    if (properties.credentialsPath() == null
        || properties.tokensDirectory() == null
        || !Files.isRegularFile(Path.of(properties.credentialsPath()))
        || !Files.isRegularFile(Path.of(properties.tokensDirectory()).resolve("StoredCredential")))
      return new Auth("UNCONFIGURED", "", secretaryScopes(), "credentials_missing");
    return new Auth(
        secretaryAuthFailed || !secretaryScopesMatch() ? "REAUTH_REQUIRED" : "CONFIGURED",
        "",
        secretaryScopes(),
        "not_live_verified");
  }

  /** Shared bounded Google network boundary; exceptions never retain OAuth response bodies. */
  public <T> T secretaryNetwork(java.util.concurrent.Callable<T> operation, boolean write) {
    if (!SECRETARY_NETWORK.tryAcquire()) throw new Failure("LOCAL_BUSY", 60, false);
    try {
      return operation.call();
    } catch (Failure failure) {
      throw failure;
    } catch (com.google.api.client.http.HttpResponseException failure) {
      int code = failure.getStatusCode();
      if (code == 401) {
        secretaryAuthFailed = true;
        throw new Failure("AUTH_REQUIRED", 0, false);
      }
      if (code == 403) throw new Failure("FORBIDDEN", 0, false);
      int retry = 60;
      String header = failure.getHeaders() == null ? null : failure.getHeaders().getRetryAfter();
      if (header != null)
        try {
          retry = Math.max(1, Math.min(86400, Integer.parseInt(header)));
        } catch (NumberFormatException ignored) {
          /* conservative default */
        }
      throw new Failure(
          code == 429
              ? "RATE_LIMITED"
              : code == 404
                  ? "NOT_FOUND"
                  : code == 410
                      ? "GONE"
                      : code == 409
                          ? "CONFLICT"
                          : code == 412 ? "PRECONDITION_FAILED" : "HTTP_" + code,
          retry,
          write && (code >= 500 || code == 408));
    } catch (Exception failure) {
      throw new Failure("NETWORK", 60, write);
    } finally {
      SECRETARY_NETWORK.release();
    }
  }

  protected com.google.api.client.http.HttpTransport secretaryHttp()
      throws java.io.IOException, java.security.GeneralSecurityException {
    return GoogleNetHttpTransport.newTrustedTransport();
  }

  /** Noninteractive initializer shared by the existing Calendar SDK. */
  public com.google.api.client.http.HttpRequestInitializer secretaryInitializer() {
    return request -> {
      secretaryCredential().initialize(request);
      request.setConnectTimeout(10000);
      request.setReadTimeout(15000);
      request.setWriteTimeout(15000);
      request.setNumberOfRetries(0);
      request.setFollowRedirects(false);
      request.setUnsuccessfulResponseHandler(null);
      request.setLoggingEnabled(false);
      request.setCurlLoggingEnabled(false);
    };
  }

  /**
   * Fixed Gmail origin, no redirects or implicit retries, bounded response, and disabled HTTP logs.
   */
  protected com.fasterxml.jackson.databind.JsonNode secretaryRequest(
      String method, String path, java.util.Map<String, String> query, Object body, boolean write) {
    if (!path.startsWith("/gmail/v1/users/me/")
        || path.contains("..")
        || path.contains("?")
        || path.contains("#")) throw new IllegalArgumentException("Invalid Gmail path");
    return secretaryNetwork(
        () -> {
          var credentials = secretaryCredential();
          var url =
              new com.google.api.client.http.GenericUrl("https://gmail.googleapis.com" + path);
          query.forEach(url::set);
          var content =
              body == null
                  ? null
                  : com.google.api.client.http.ByteArrayContent.fromString(
                      "application/json; charset=UTF-8", SECRETARY_JSON.writeValueAsString(body));
          var request =
              secretaryHttp().createRequestFactory(credentials).buildRequest(method, url, content);
          request.setConnectTimeout(10000);
          request.setReadTimeout(15000);
          request.setWriteTimeout(15000);
          request.setNumberOfRetries(0);
          request.setFollowRedirects(false);
          request.setUnsuccessfulResponseHandler(null);
          request.setLoggingEnabled(false);
          request.setCurlLoggingEnabled(false);
          var response = request.execute();
          try {
            byte[] bytes =
                response.getContent() == null
                    ? new byte[0]
                    : response.getContent().readNBytes(1048577);
            if (bytes.length > 1048576) throw new Failure("RESPONSE_TOO_LARGE", 0, write);
            return bytes.length == 0
                ? SECRETARY_JSON.createObjectNode()
                : SECRETARY_JSON.readTree(bytes);
          } finally {
            response.disconnect();
          }
        },
        write);
  }

  @Override
  public java.util.Set<Service> services() {
    return java.util.Set.of(Service.GMAIL);
  }

  @Override
  public Auth status(Service service) {
    return secretaryGoogleStatus(gmail.enabled());
  }

  @Override
  public String identity(dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection) {
    return secretaryIdentity();
  }

  @Override
  public Page poll(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection, String cursor) {
    requireGmail(connection);
    return new GmailSecretaryProtocol(this::secretaryRequest, java.time.Clock.systemUTC())
        .poll(connection, cursor);
  }

  @Override
  public Page read(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      String operation,
      java.util.Map<String, String> args) {
    requireGmail(connection);
    return new GmailSecretaryProtocol(this::secretaryRequest, java.time.Clock.systemUTC())
        .read(connection, operation, args);
  }

  @Override
  public Outcome write(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      String operation,
      java.util.Map<String, String> args,
      String key) {
    requireGmail(connection);
    if (operation.equals("MAIL_DRAFT") && !gmail.draftsEnabled()
        || operation.equals("MAIL_SEND") && !gmail.sendEnabled())
      throw new Failure("WRITE_DISABLED", 0, false);
    return new GmailSecretaryProtocol(this::secretaryRequest, java.time.Clock.systemUTC())
        .write(connection, operation, args, key);
  }

  @Override
  public Outcome verify(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      String operation,
      java.util.Map<String, String> args,
      String key,
      Outcome previous) {
    requireGmail(connection);
    return new GmailSecretaryProtocol(this::secretaryRequest, java.time.Clock.systemUTC())
        .verify(connection, operation, args, key);
  }

  private void requireGmail(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection) {
    if (!gmail.enabled()
        || connection.config().service() != Service.GMAIL
        || !connection.config().resource().equals("me"))
      throw new Failure("GMAIL_DISABLED", 0, false);
  }

  @Override
  public Auth authorizeConnection(Service service, boolean force) {
    if (service == Service.GMAIL && !gmail.enabled()) throw new Failure("GMAIL_DISABLED", 0, false);
    try {
      authorize(GoogleNetHttpTransport.newTrustedTransport(), force || !secretaryScopesMatch());
      return secretaryGoogleStatus(true);
    } catch (Exception failure) {
      throw new Failure("AUTH_REQUIRED", 0, false);
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
