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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setChatConnections(dev.mikoto2000.rei.modeloptions.ChatModelSelection connections) {
    java.util.Objects.requireNonNull(connections);
    modelCatalog = connections::models;
  }

  private java.util.function.Supplier<dev.mikoto2000.rei.modeloptions.ChatModelSelection.Models>
      modelCatalog;

  /** モデル一覧サービス。 */
  private final OpenAiCompatibleModelsService modelsService;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** OpenAI 互換 API で利用可能なモデル一覧を表示しますを行う。 */
  @Override
  public void run() {
    var snapshot = modelCatalog == null ? null : modelCatalog.get();
    String currentModel =
        snapshot == null ? modelHolderService.get() : snapshot.selection().model();
    var models = snapshot == null ? modelsService.listModels() : snapshot.models();
    for (String model : models) {
      if (model.equals(currentModel)) {
        IO.println("* " + model);
      } else {
        IO.println(model);
      }
    }
  }
}
