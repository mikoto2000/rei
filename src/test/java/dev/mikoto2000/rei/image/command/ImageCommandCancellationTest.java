package dev.mikoto2000.rei.image.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** 画像コマンドキャンセルの結果と状態の契約を検証するテスト。 */
class ImageCommandCancellationTest {

  /** 画像の生成の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void generateCommandPrintsCancelledWhenCancellationIsRequested() throws Exception {
    CommandCancellationService cancellationService = new CommandCancellationService();
    CountDownLatch serviceCalled = new CountDownLatch(1);
    ImageGenerationService service = Mockito.mock(ImageGenerationService.class);
    when(service.generate(any(ImageGenerationRequest.class)))
        .thenAnswer(
            invocation -> {
              serviceCalled.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                return ImageGenerationResult.failure("not cancelled");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ImageGenerationResult.failure("cancelled");
              }
            });
    CommandLine commandLine = newCommand(service, cancellationService);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    var executor = Executors.newSingleThreadExecutor();

    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      var future = executor.submit(() -> commandLine.execute("generate", "a", "cat"));
      assertThat(serviceCalled.await(1, TimeUnit.SECONDS)).isTrue();

      cancellationService.cancel();

      assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(130);
    } finally {
      executor.shutdownNow();
      System.setOut(originalOut);
    }

    assertThat(out.toString(StandardCharsets.UTF_8)).contains("[cancelled]");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(
      ImageGenerationService service, CommandCancellationService cancellationService) {
    ImageProperties properties = new ImageProperties();
    return new CommandLine(
        new ImageCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls.equals(GenerateCommand.class)) {
              return cls.cast(new GenerateCommand(service, properties, cancellationService));
            }
            return CommandLine.defaultFactory().create(cls);
          }
        });
  }
}
