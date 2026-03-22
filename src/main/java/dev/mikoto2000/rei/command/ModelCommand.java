package dev.mikoto2000.rei.command;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "model", description = "使用するモデルを確認・指定します")
@RequiredArgsConstructor
class ModelCommand implements Runnable {
  private final ModelHolderService currentModelHolder;

  @Parameters(arity = "0..1")
  Optional<String> modelName;

  @Override
  public void run() {
    if (modelName.isEmpty()) {
      IO.println(currentModelHolder.get());
    } else {
      currentModelHolder.set(modelName.get());
    }
  }
}

