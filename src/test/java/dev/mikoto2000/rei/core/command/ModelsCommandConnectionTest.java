package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import dev.mikoto2000.rei.modeloptions.ChatModelSelection;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelsCommandConnectionTest {
  @Test
  void modelsComeFromOneSelectedConnectionSnapshotAndLeaveHelpersUnchanged() {
    var ambient = mock(OpenAiCompatibleModelsService.class);
    when(ambient.listModels()).thenReturn(List.of("helper-model"));
    var holder = new ModelHolderService("helper-model");
    var selected = mock(ChatModelSelection.class);
    when(selected.models())
        .thenReturn(
            new ChatModelSelection.Models(
                new ChatModelSelection.Selection("local", "named-model"),
                List.of("named-model", "other-model")));
    var command = new ModelsCommand(ambient, holder);
    command.setChatConnections(selected);
    var output = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(output));
    try {
      command.run();
    } finally {
      System.setOut(original);
    }
    assertThat(output.toString().lines().toList()).containsExactly("* named-model", "other-model");
    assertThat(holder.get()).isEqualTo("helper-model");
    verify(selected).models();
    verifyNoMoreInteractions(selected);
    verifyNoInteractions(ambient);
  }
}
