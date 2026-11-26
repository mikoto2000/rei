package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** モデルコマンドの結果と状態の契約を検証するテスト。 */
class ModelCommandTest {
  @Test
  void readsSelectedProfileModelInsteadOfStaticHelperModel() {
    var holder = new ModelHolderService("helper-default");
    var service = org.mockito.Mockito.mock(dev.mikoto2000.rei.llm.ChatConnectionService.class);
    org.mockito.Mockito.when(service.selection()).thenReturn(
        new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection("local", "named-model"));
    var output = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(output));
    try {
      var command = new ModelCommand(holder);
      command.setChatConnections(service);
      command.modelName = Optional.empty();
      command.run();
    } finally { System.setOut(original); }
    assertEquals("named-model", output.toString().trim());
    assertEquals("helper-default", holder.get());
  }

  @Test
  void selectsThroughCommonConnectionServiceWithoutChangingHelperModel() {
    var holder = new ModelHolderService("helper-default");
    var service = org.mockito.Mockito.mock(dev.mikoto2000.rei.llm.ChatConnectionService.class);
    org.mockito.Mockito.when(service.selection()).thenReturn(
        new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection("local", "chosen"));
    var command = new ModelCommand(holder);
    command.setChatConnections(service);
    command.modelName = Optional.of("chosen");
    command.run();
    assertEquals("helper-default", holder.get());
    org.mockito.Mockito.verify(service).selectModel("chosen");
  }


  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void switchingModelUpdatesSelectionAndDisplaysNewModel() {
    ModelHolderService holder = new ModelHolderService("gpt-4o");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ModelCommand command = new ModelCommand(holder);
      command.modelName = Optional.of("gpt-5");
      command.run();
    } finally {
      System.setOut(originalOut);
    }

    assertEquals("gpt-5", holder.get());
    assertEquals("current model: gpt-5", out.toString().trim());
  }

  /** 同じモデルの指定でも、現在のモデル名が表示されることを確認する。 */
  @Test
  void selectingSameModelKeepsSelectionAndDisplaysModel() {
    ModelHolderService holder = new ModelHolderService("gpt-4o");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ModelCommand command = new ModelCommand(holder);
      command.modelName = Optional.of("gpt-4o");
      command.run();
    } finally {
      System.setOut(originalOut);
    }

    assertEquals("gpt-4o", holder.get());
    assertEquals("current model: gpt-4o", out.toString().trim());
  }
}
