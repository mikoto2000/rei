package dev.mikoto2000.rei.core.command;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelUnloadService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "model", description = "使用する chat モデルを確認・指定します")
@RequiredArgsConstructor
public class ModelCommand implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(ModelCommand.class);

  private final ModelHolderService currentModelHolder;
  private final OpenAiCompatibleModelUnloadService modelUnloadService;

  @Parameters(arity = "0..1", paramLabel = "MODEL")
  Optional<String> modelName;

  @Override
  public void run() {
    if (modelName.isEmpty()) {
      IO.println(currentModelHolder.get());
      return;
    }

    String currentModel = currentModelHolder.get();
    String nextModel = modelName.get();
    if (!nextModel.equals(currentModel)) {
      try {
        modelUnloadService.unload(currentModel);
      } catch (RuntimeException e) {
        log.warn("Failed to unload current model: {}", currentModel, e);
      }
    }

    currentModelHolder.set(nextModel);
    IO.println("current model: " + currentModelHolder.get());
  }
}
