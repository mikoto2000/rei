package dev.mikoto2000.rei.core.command;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "model", description = "使用する chat モデルを確認・指定します")
@RequiredArgsConstructor
public class ModelCommand implements Runnable {
  private final ModelHolderService currentModelHolder;

  @Parameters(arity = "0..1", paramLabel = "MODEL")
  Optional<String> modelName;

  @Override
  public void run() {
    if (modelName.isEmpty()) {
      IO.println(currentModelHolder.get());
      return;
    }

    currentModelHolder.set(modelName.get());
    IO.println("current model: " + currentModelHolder.get());
  }
}
