package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.bluesky.BlueskyApiClient;
import dev.mikoto2000.rei.bluesky.BlueskyProperties;
import org.junit.jupiter.api.Test;

class AutonomousTargetResolverTest {
  @Test
  void onlyPublicHandleResolutionIsUsedWithoutReadingCredentialsOrAuthenticating() {
    var settings = mock(BlueskyProperties.class);
    var api = mock(BlueskyApiClient.class);
    when(settings.getHandle()).thenReturn("rei.example");
    when(api.resolveHandle("rei.example")).thenReturn("did:plc:example");
    assertThat(new AutonomousTargetResolver(settings, api).resolve())
        .isEqualTo(new AutonomousDraft.Target("did:plc:example", "rei.example"));
    verify(settings).getHandle();
    verify(api).resolveHandle("rei.example");
    verifyNoMoreInteractions(settings, api);
  }

  @Test
  void missingHandleDoesNotMakeANetworkRequest() {
    var settings = mock(BlueskyProperties.class);
    var api = mock(BlueskyApiClient.class);
    when(settings.getHandle()).thenReturn("");
    assertThat(new AutonomousTargetResolver(settings, api).resolve()).isNull();
    verifyNoInteractions(api);
  }

  @Test
  void invalidResolvedIdentityIsRejected() {
    var settings = mock(BlueskyProperties.class);
    var api = mock(BlueskyApiClient.class);
    when(settings.getHandle()).thenReturn("rei.example");
    when(api.resolveHandle("rei.example")).thenReturn("not-a-did");
    assertThatThrownBy(() -> new AutonomousTargetResolver(settings, api).resolve())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
