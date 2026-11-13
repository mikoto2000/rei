package dev.mikoto2000.rei.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.store.FileDataStoreFactory;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Failure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GoogleO認証サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class GoogleOAuthServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private GoogleOAuthService service(boolean calendar, boolean task) throws Exception {
    Path credentials = directory.resolve("credentials.json");
    Files.writeString(credentials, "{}");
    return spy(
        new GoogleOAuthService(
            new GoogleCalendarProperties(
                "Rei",
                credentials.toString(),
                directory.toString(),
                new GoogleCalendarProperties.CalendarProperties(calendar, "primary", ""),
                new GoogleCalendarProperties.TaskProperties(task))));
  }

  /** {@code stored} の条件を再現するための補助情報を返す。 */
  private Credential stored(GoogleOAuthService service, Long remaining) throws Exception {
    AuthorizationCodeFlow flow = mock(AuthorizationCodeFlow.class);
    Credential credential = mock(Credential.class);
    doReturn(flow).when(service).createFlow(any());
    when(flow.loadCredential("user")).thenReturn(credential);
    when(credential.getRefreshToken()).thenReturn("test-refresh-token");
    when(credential.getExpiresInSeconds()).thenReturn(remaining);
    when(credential.refreshToken()).thenReturn(true);
    return credential;
  }

  /** Googleサービスの認証で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void refreshesAtBoundaryAndUsesSharedCredentialAfterwards() throws Exception {
    GoogleOAuthService service = service(false, true);
    Credential credential = stored(service, 300L);
    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertSame(credential, service.authorize(null, false));
    when(credential.getExpiresInSeconds()).thenReturn(3600L);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(credential, times(1)).refreshToken();
  }

  /** Googleサービスの認証で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void doesNotRefreshEarly() throws Exception {
    GoogleOAuthService service = service(true, false);
    Credential credential = stored(service, 301L);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(credential, never()).refreshToken();
  }

  /** Googleサービスの認証で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void refreshesExpiredAndUnknownExpiry() throws Exception {
    for (Long remaining : new Long[] {-1L, null}) {
      GoogleOAuthService service = service(true, true);
      Credential credential = stored(service, remaining);
      assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
      verify(credential).refreshToken();
    }
  }

  /** Googleサービスの認証で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void disabledIntegrationsDoNotLoadCredentials() throws Exception {
    GoogleOAuthService service = service(false, false);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(service, never()).createFlow(any());
  }

  /** Googleサービスの認証で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void missingCredentialsDoNotStartAuthorization() throws Exception {
    GoogleOAuthService service = service(true, true);
    Files.delete(directory.resolve("credentials.json"));
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(service, never()).createFlow(any());
    verify(service, never()).authorize(any(), anyBoolean());
  }

  /** Googleサービスの認証で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingStoredTokenAndMissingRefreshTokenAreSkipped() throws Exception {
    GoogleOAuthService service = service(true, true);
    Credential credential = stored(service, 0L);
    when(credential.getRefreshToken()).thenReturn(null);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(credential, never()).refreshToken();
    AuthorizationCodeFlow flow = mock(AuthorizationCodeFlow.class);
    doReturn(flow).when(service).createFlow(any());
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(service, never()).authorize(any(), anyBoolean());
  }

  /** Googleサービスの認証で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureCanBeRetried() throws Exception {
    GoogleOAuthService service = service(true, true);
    Credential credential = stored(service, 0L);
    when(credential.refreshToken())
        .thenThrow(new IOException("offline"))
        .thenReturn(false)
        .thenReturn(true);
    assertThrows(IOException.class, () -> service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertThrows(
        IllegalStateException.class, () -> service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
  }

  @Test
  void secretaryRecoversFromTemporaryRefreshFailures() throws Exception {
    GoogleOAuthService service = service(true, true);
    configureSecretaryScopes(service);
    Credential credential = stored(service, 0L);
    var temporaryFailure = tokenError(503, "temporarily_unavailable");
    when(credential.refreshToken())
        .thenThrow(new IOException("offline"))
        .thenThrow(temporaryFailure)
        .thenReturn(true);
    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals("NETWORK", assertThrows(Failure.class, service::secretaryCredential).code());
      assertEquals("CONFIGURED", service.secretaryGoogleStatus(true).state());
    }
    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
    when(credential.getExpiresInSeconds()).thenReturn(3600L);
    assertSame(credential, service.secretaryCredential());
  }

  @Test
  void secretaryRequiresReauthorizationForRevokedGrant() throws Exception {
    GoogleOAuthService service = service(true, true);
    configureSecretaryScopes(service);
    Credential credential = stored(service, 0L);
    var revokedGrant = tokenError(400, "invalid_grant");
    when(credential.refreshToken()).thenThrow(revokedGrant);
    assertEquals("AUTH_REQUIRED", assertThrows(Failure.class, service::secretaryCredential).code());
    assertEquals("REAUTH_REQUIRED", service.secretaryGoogleStatus(true).state());
    assertEquals("AUTH_REQUIRED", assertThrows(Failure.class, service::secretaryCredential).code());
    verify(credential).refreshToken();
  }

  private void configureSecretaryScopes(GoogleOAuthService service) throws Exception {
    var scopes = String.join("\n", service.secretaryScopes());
    var hash = MessageDigest.getInstance("SHA-256").digest(scopes.getBytes(StandardCharsets.UTF_8));
    Files.writeString(
        directory.resolve("rei-requested-scopes.sha256"), HexFormat.of().formatHex(hash));
    Files.writeString(directory.resolve("StoredCredential"), "fixture");
  }

  private static TokenResponseException tokenError(int status, String code) {
    var error = mock(TokenResponseException.class);
    when(error.getStatusCode()).thenReturn(status);
    when(error.getDetails()).thenReturn(new TokenErrorResponse().setError(code));
    return error;
  }

  /** Googleサービスの認証で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void refreshedTokenAndExpiryArePersistedForRestart() throws Exception {
    GoogleOAuthService service = service(true, true);
    var response =
        new MockLowLevelHttpResponse()
            .setContentType("application/json")
            .setContent(
                "{\"access_token\":\"new-test-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
    var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build();
    var flow =
        new GoogleAuthorizationCodeFlow.Builder(
                transport,
                GsonFactory.getDefaultInstance(),
                "test-client",
                "test-secret",
                java.util.List.of("test-scope"))
            .setDataStoreFactory(new FileDataStoreFactory(directory.toFile()))
            .setAccessType("offline")
            .build();
    flow.createAndStoreCredential(
        new TokenResponse()
            .setAccessToken("old-test-token")
            .setRefreshToken("test-refresh-token")
            .setExpiresInSeconds(0L),
        "user");
    doReturn(flow).when(service).createFlow(any());

    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));

    Credential reloaded = flow.loadCredential("user");
    assertEquals("new-test-token", reloaded.getAccessToken());
    assertEquals("test-refresh-token", reloaded.getRefreshToken());
    assertTrue(reloaded.getExpiresInSeconds() > 3500);
  }
}
