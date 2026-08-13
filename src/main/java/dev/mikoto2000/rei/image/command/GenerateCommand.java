package dev.mikoto2000.rei.image.command;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.image.ImageGenerationRequest;
import dev.mikoto2000.rei.image.ImageGenerationResult;
import dev.mikoto2000.rei.image.ImageGenerationService;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.image.ImageSize;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "generate", description = "プロンプトから画像を生成します。", mixinStandardHelpOptions = true)
@RequiredArgsConstructor
public class GenerateCommand implements Callable<Integer> {

  private final ImageGenerationService service;
  private final ImageProperties properties;
  private final CommandCancellationService cancellationService;

  @Option(names = "--output", description = "保存先ファイルパス")
  Path outputPath;

  @Option(names = "--model", description = "画像生成モデル名")
  String model;

  @Option(names = "--size", description = "画像サイズ。例: 1024x1024")
  String size;

  @Parameters(arity = "1..*", description = "画像生成プロンプト")
  List<String> promptParts;

  @Override
  public Integer call() {
    try {
      cancellationService.begin(Thread.currentThread());
      ImageSize imageSize;
      imageSize = ImageSize.parse(size == null || size.isBlank() ? properties.getSize() : size);
      if (cancellationService.isCancellationRequested()) {
        return cancelled();
      }
      ImageGenerationResult result = service.generate(new ImageGenerationRequest(
          String.join(" ", promptParts), outputPath, model, imageSize));
      if (cancellationService.consumeCancellationRequested()
          || Thread.currentThread().isInterrupted()
          || isCancelledResult(result)) {
        return cancelled();
      }
      if (result.success()) {
        System.out.println("画像を保存しました: " + result.savedPath());
        return 0;
      }
      System.out.println("[error] 画像生成に失敗しました: " + result.message());
      return 1;
    } catch (IllegalArgumentException e) {
      System.out.println("[error] 画像生成に失敗しました: " + e.getMessage());
      return 1;
    } finally {
      cancellationService.clear();
    }
  }

  private boolean isCancelledResult(ImageGenerationResult result) {
    return !result.success() && "cancelled".equals(result.message());
  }

  private int cancelled() {
    System.out.println();
    System.out.println("[cancelled]");
    return 130;
  }
}
