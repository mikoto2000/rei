package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.llm.ChatConnectionService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatEndpointsControllerTest {
  @Test
  void metadataAndSelectionShareTheShellConnectionServiceWithoutInference() {
    var service = mock(ChatConnectionService.class);
    var entry = mock(ChatConnectionService.Endpoint.class);
    when(entry.active()).thenReturn(true);
    when(service.endpoints()).thenReturn(List.of(entry));
    when(service.currentEndpoint()).thenAnswer(invocation -> service.endpoints().getFirst());
    var controller = new ChatEndpointsController(service);
    assertThat(controller.list()).isEqualTo(List.of(entry));
    assertThat(controller.current()).isSameAs(entry);
    assertThat(controller.select(new ChatEndpointsController.Selection("local"))).isSameAs(entry);
    verify(service).select("local");
    verify(service, org.mockito.Mockito.times(3)).endpoints();
    verify(service, org.mockito.Mockito.times(2)).currentEndpoint();
    verifyNoMoreInteractions(service);
  }

  @Test
  void invalidNamesAndMissingRequestsCannotChangeSelection() {
    var service = mock(ChatConnectionService.class);
    var controller = new ChatEndpointsController(service);
    for (var name : new String[] {"", " ", "../x", "x\ny", "x".repeat(65)})
      assertThatThrownBy(() -> new ChatEndpointsController.Selection(name))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> controller.select(null)).isInstanceOf(IllegalArgumentException.class);
    verifyNoMoreInteractions(service);
  }
}
