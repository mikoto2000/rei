package dev.mikoto2000.rei.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.store.FileDataStoreFactory;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;

class GoogleOAuthServiceTest {
  @TempDir Path directory;

  private GoogleOAuthService service(boolean calendar, boolean task) throws Exception {
    Path credentials = directory.resolve("credentials.json");
    Files.writeString(credentials, "{}");
    return spy(new GoogleOAuthService(new GoogleCalendarProperties("Rei", credentials.toString(),
        directory.toString(), new GoogleCalendarProperties.CalendarProperties(calendar, "primary", ""),
        new GoogleCalendarProperties.TaskProperties(task))));
  }

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

  @Test void refreshesAtBoundaryAndUsesSharedCredentialAfterwards() throws Exception {
    GoogleOAuthService service = service(false, true);
    Credential credential = stored(service, 300L);
    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertSame(credential, service.authorize(null, false));
    when(credential.getExpiresInSeconds()).thenReturn(3600L);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(credential, times(1)).refreshToken();
  }

  @Test void doesNotRefreshEarly() throws Exception {
    GoogleOAuthService service = service(true, false);
    Credential credential = stored(service, 301L);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(credential, never()).refreshToken();
  }

  @Test void refreshesExpiredAndUnknownExpiry() throws Exception {
    for (Long remaining : new Long[] {-1L, null}) {
      GoogleOAuthService service = service(true, true);
      Credential credential = stored(service, remaining);
      assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
      verify(credential).refreshToken();
    }
  }

  @Test void disabledIntegrationsDoNotLoadCredentials() throws Exception {
    GoogleOAuthService service = service(false, false);
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(service, never()).createFlow(any());
  }

  @Test void missingCredentialsDoNotStartAuthorization() throws Exception {
    GoogleOAuthService service = service(true, true);
    Files.delete(directory.resolve("credentials.json"));
    assertFalse(service.refreshIfExpiring(Duration.ofMinutes(5)));
    verify(service, never()).createFlow(any());
    verify(service, never()).authorize(any(), anyBoolean());
  }

  @Test void missingStoredTokenAndMissingRefreshTokenAreSkipped() throws Exception {
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

  @Test void failureCanBeRetried() throws Exception {
    GoogleOAuthService service = service(true, true);
    Credential credential = stored(service, 0L);
    when(credential.refreshToken()).thenThrow(new IOException("offline")).thenReturn(false).thenReturn(true);
    assertThrows(IOException.class, () -> service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertThrows(IllegalStateException.class, () -> service.refreshIfExpiring(Duration.ofMinutes(5)));
    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));
  }

  @Test void refreshedTokenAndExpiryArePersistedForRestart() throws Exception {
    GoogleOAuthService service = service(true, true);
    var response = new MockLowLevelHttpResponse().setContentType("application/json")
        .setContent("{\"access_token\":\"new-test-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
    var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build();
    var flow = new GoogleAuthorizationCodeFlow.Builder(transport, GsonFactory.getDefaultInstance(),
        "test-client", "test-secret", java.util.List.of("test-scope"))
        .setDataStoreFactory(new FileDataStoreFactory(directory.toFile())).setAccessType("offline").build();
    flow.createAndStoreCredential(new TokenResponse().setAccessToken("old-test-token")
        .setRefreshToken("test-refresh-token").setExpiresInSeconds(0L), "user");
    doReturn(flow).when(service).createFlow(any());

    assertTrue(service.refreshIfExpiring(Duration.ofMinutes(5)));

    Credential reloaded = flow.loadCredential("user");
    assertEquals("new-test-token", reloaded.getAccessToken());
    assertEquals("test-refresh-token", reloaded.getRefreshToken());
    assertTrue(reloaded.getExpiresInSeconds() > 3500);
  }
}
