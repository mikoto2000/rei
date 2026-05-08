package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelUnloadService;

class ModelCommandTest {

  @Test
  void switchingModelSendsUnloadForCurrentModel() {
    ModelHolderService holder = Mockito.mock(ModelHolderService.class);
    OpenAiCompatibleModelUnloadService unloadService = Mockito.mock(OpenAiCompatibleModelUnloadService.class);
    when(holder.get()).thenReturn("gpt-4o", "gpt-5");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ModelCommand command = new ModelCommand(holder, unloadService);
      command.modelName = Optional.of("gpt-5");
      command.run();
    } finally {
      System.setOut(originalOut);
    }

    verify(unloadService).unload("gpt-4o");
    verify(holder).set("gpt-5");
    assertEquals("current model: gpt-5", out.toString().trim());
  }

  @Test
  void sameModelDoesNotSendUnload() {
    ModelHolderService holder = Mockito.mock(ModelHolderService.class);
    OpenAiCompatibleModelUnloadService unloadService = Mockito.mock(OpenAiCompatibleModelUnloadService.class);
    when(holder.get()).thenReturn("gpt-4o", "gpt-4o");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ModelCommand command = new ModelCommand(holder, unloadService);
      command.modelName = Optional.of("gpt-4o");
      command.run();
    } finally {
      System.setOut(originalOut);
    }

    verify(unloadService, never()).unload("gpt-4o");
    verify(holder).set("gpt-4o");
    assertEquals("current model: gpt-4o", out.toString().trim());
  }
}
