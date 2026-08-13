package dev.mikoto2000.rei.image.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import picocli.CommandLine;

class ImageCommandTest {

  @Test
  void generateDelegatesToServiceAndPrintsSavedPath() {
    ImageGenerationService service = Mockito.mock(ImageGenerationService.class);
    Path output = Path.of("out.png").toAbsolutePath();
    when(service.generate(Mockito.any())).thenReturn(ImageGenerationResult.success(output));
    CommandLine commandLine = newCommand(service);

    ExecutionResult execution = execute(commandLine, "generate", "--output", "out.png", "--model", "image-model",
        "--size", "640x480", "a", "cat");

    assertThat(execution.exitCode()).isZero();
    assertThat(execution.output()).contains("画像を保存しました: " + output);
    ArgumentCaptor<ImageGenerationRequest> captor = ArgumentCaptor.forClass(ImageGenerationRequest.class);
    verify(service).generate(captor.capture());
    assertThat(captor.getValue().prompt()).isEqualTo("a cat");
    assertThat(captor.getValue().outputPath()).isEqualTo(Path.of("out.png"));
    assertThat(captor.getValue().model()).isEqualTo("image-model");
    assertThat(captor.getValue().size().width()).isEqualTo(640);
    assertThat(captor.getValue().size().height()).isEqualTo(480);
  }

  @Test
  void generatePrintsErrorWhenServiceFails() {
    ImageGenerationService service = Mockito.mock(ImageGenerationService.class);
    when(service.generate(Mockito.any())).thenReturn(ImageGenerationResult.failure("api failed"));
    CommandLine commandLine = newCommand(service);

    ExecutionResult execution = execute(commandLine, "generate", "a cat");

    assertThat(execution.exitCode()).isEqualTo(1);
    assertThat(execution.output()).contains("[error] 画像生成に失敗しました: api failed");
  }

  @Test
  void generateRejectsInvalidSizeBeforeCallingService() {
    ImageGenerationService service = Mockito.mock(ImageGenerationService.class);
    CommandLine commandLine = newCommand(service);

    ExecutionResult execution = execute(commandLine, "generate", "--size", "bad", "a cat");

    assertThat(execution.exitCode()).isEqualTo(1);
    assertThat(execution.output()).contains("[error] 画像生成に失敗しました");
    Mockito.verifyNoInteractions(service);
  }

  private CommandLine newCommand(ImageGenerationService service) {
    ImageProperties properties = new ImageProperties();
    CommandLine commandLine = new CommandLine(new ImageCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls.equals(GenerateCommand.class)) {
          return cls.cast(new GenerateCommand(service, properties));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
    return commandLine;
  }

  private ExecutionResult execute(CommandLine commandLine, String... args) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
      return new ExecutionResult(commandLine.execute(args), out.toString(java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
    }
  }

  private record ExecutionResult(int exitCode, String output) {
  }
}
