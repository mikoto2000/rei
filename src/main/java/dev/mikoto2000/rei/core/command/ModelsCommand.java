package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "models", description = "OpenAI 互換 API で利用可能なモデル一覧を表示します")
@RequiredArgsConstructor
public class ModelsCommand implements Runnable {

  private final OpenAiCompatibleModelsService modelsService;
  private final ModelHolderService modelHolderService;

  @Override
  public void run() {
    String currentModel = modelHolderService.get();

    for (String model : modelsService.listModels()) {
      if (model.equals(currentModel)) {
        IO.println("* " + model);
      } else {
        IO.println(model);
      }
    }
  }
}
