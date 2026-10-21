package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelUnloadService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * モデルをWeb APIの操作として受け付ける。
 *
 * <p>Named model controls using the same holder and unload behavior as the existing Shell.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/models")
public class ModelController {
  /** モデル一覧。 */
  private final OpenAiCompatibleModelsService models;

  /** 保持先。 */
  private final ModelHolderService holder;

  /** 使用を終えたモデルの解放を依頼する窓口。 */
  private final OpenAiCompatibleModelUnloadService unload;

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ModelController.class);

  /** 指定された値や利用先を使って、ModelControllerを初期化する。 */
  public ModelController(
      OpenAiCompatibleModelsService models,
      ModelHolderService holder,
      OpenAiCompatibleModelUnloadService unload) {
    this.models = models;
    this.holder = holder;
    this.unload = unload;
  }

  /**
   * Web APIによる操作で受け渡すモデル一覧を表す。
   *
   * @param models モデル一覧
   * @param currentModel 現在モデル
   */
  public record Models(List<String> models, String currentModel) {}

  /**
   * Web APIによる操作で受け渡す選択内容を表す。
   *
   * @param model モデル
   */
  public record Selection(String model) implements StrictApiRequest {
    /** 受け取った情報からSelectionを作成する。 */
    public Selection {
      if (model == null
          || model.isBlank()
          || model.length() > 1024
          || model.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Valid model name required");
    }
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Models list() {
    return new Models(models.listModels(), holder.get());
  }

  /** Web APIによる操作で用いる現在を返す。 */
  @GetMapping("/current")
  public Selection current() {
    return new Selection(holder.get());
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  @PutMapping("/current")
  public synchronized Selection select(@RequestBody Selection request) {
    if (request == null) throw new IllegalArgumentException("Model required");
    String previous = holder.get();
    if (!request.model().equals(previous)) {
      try {
        unload.unload(previous);
      } catch (RuntimeException failure) {
        log.warn("Previous model unload failed while selecting a new model");
      }
    }
    holder.set(request.model());
    return current();
  }
}
