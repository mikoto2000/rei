package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** OpenAI 互換 API で利用可能なモデル一覧を表示しますを行うための利用者コマンド。 */
@Component
@Command(name = "models", description = "OpenAI 互換 API で利用可能なモデル一覧を表示します")
@RequiredArgsConstructor
public class ModelsCommand implements Runnable {

  /** モデル一覧サービス。 */
  private final OpenAiCompatibleModelsService modelsService;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** OpenAI 互換 API で利用可能なモデル一覧を表示しますを行う。 */
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
