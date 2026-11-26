package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** 使用する chat モデルを確認・指定しますを行うための利用者コマンド。 */
@Component
@Command(name = "model", description = "使用する chat モデルを確認・指定します")
@RequiredArgsConstructor
public class ModelCommand implements Runnable {
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setChatConnections(dev.mikoto2000.rei.llm.ChatConnectionService connections) {}

  /** 現在モデル保持先。 */
  private final ModelHolderService currentModelHolder;

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

    String nextModel = modelName.get();
    currentModelHolder.set(nextModel);
    IO.println("current model: " + currentModelHolder.get());
  }
}
