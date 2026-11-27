package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.modeloptions.ChatModelSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelControllerCommonSelectionTest {
  @Test
  void publicResponsesCarryTheEndpointIdentityFromTheSameSnapshot() {
    var selection = mock(ChatModelSelection.class);
    when(selection.selection()).thenReturn(new ChatModelSelection.Selection("second", "chosen"));
    when(selection.models())
        .thenReturn(
            new ChatModelSelection.Models(
                new ChatModelSelection.Selection("first", "first-model"), List.of("first-model")));
    var controller = new ModelController(selection);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.valueToTree(controller.current()).path("endpoint").asText())
        .isEqualTo("second");
    assertThat(mapper.valueToTree(controller.list()).path("endpoint").asText()).isEqualTo("first");
  }

  @Test
  void modelListAndCurrentModelUseTheSharedConnectionSelection() {
    var selection = mock(ChatModelSelection.class);
    when(selection.selection())
        .thenReturn(new ChatModelSelection.Selection("named", "named-model"));
    when(selection.models())
        .thenReturn(
            new ChatModelSelection.Models(
                new ChatModelSelection.Selection("named", "named-model"),
                List.of("named-model", "other-model")));
    var controller = new ModelController(selection);
    assertThat(controller.list().models()).containsExactly("named-model", "other-model");
    assertThat(controller.list().currentModel()).isEqualTo("named-model");
    assertThat(controller.current().model()).isEqualTo("named-model");
  }

  @Test
  void modelChangeUsesTheSharedSelectorWithoutAnAmbientHolder() {
    var selection = mock(ChatModelSelection.class);
    when(selection.selection()).thenReturn(new ChatModelSelection.Selection("named", "chosen"));
    var controller = new ModelController(selection);
    assertThat(controller.select(new ModelController.Selection("chosen")).model())
        .isEqualTo("chosen");
    verify(selection).selectModel("chosen");
  }

  @Test
  void catalogIsNotRelabeledUsingASelectionChangedAfterTheRequestStarted() {
    var selection = mock(ChatModelSelection.class);
    when(selection.selection())
        .thenReturn(new ChatModelSelection.Selection("second", "second-model"));
    when(selection.models())
        .thenReturn(
            new ChatModelSelection.Models(
                new ChatModelSelection.Selection("first", "first-model"), List.of("first-model")));
    var response = new ModelController(selection).list();
    assertThat(response.models()).containsExactly("first-model");
    assertThat(response.currentModel()).isEqualTo("first-model");
  }
}
