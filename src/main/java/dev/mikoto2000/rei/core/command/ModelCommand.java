package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelUnloadService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** 使用する chat モデルを確認・指定しますを行うための利用者コマンド。 */
@Component
@Command(name = "model", description = "使用する chat モデルを確認・指定します")
@RequiredArgsConstructor
public class ModelCommand implements Runnable {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(ModelCommand.class);

  /** 現在モデル保持先。 */
  private final ModelHolderService currentModelHolder;

  /** モデルUnloadサービス。 */
  private final OpenAiCompatibleModelUnloadService modelUnloadService;

  /** モデル名前。 */
  @Parameters(arity = "0..1", paramLabel = "MODEL")
  Optional<String> modelName;

  /** 使用する chat モデルを確認・指定しますを行う。 */
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
        IO.println(
            "[warning] 旧モデルの解放に失敗しました。モデル切替は続行します。 原因: "
                + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      }
    }

    currentModelHolder.set(nextModel);
    IO.println("current model: " + currentModelHolder.get());
  }
}
