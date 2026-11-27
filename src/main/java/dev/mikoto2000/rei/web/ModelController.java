package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 * <p>Model controls use the same process-wide connection selection as Shell.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/models")
public final class ModelController {
  /** モデル一覧。 */
  private final Supplier<Models> modelCatalog;

  /** 保持先。 */
  private final Supplier<Selection> selectedModel;

  private final Consumer<Selection> selectModel;

  /** 指定された値や利用先を使って、ModelControllerを初期化する。 */
  public ModelController(OpenAiCompatibleModelsService models, ModelHolderService holder) {
    this.modelCatalog = () -> new Models(models.listModels(), holder.get());
    this.selectedModel = () -> new Selection(holder.get());
    this.selectModel =
        request -> {
          if (request.endpoint() != null)
            throw new IllegalStateException("Endpoint-bound model selection is unavailable");
          holder.set(request.model());
        };
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ModelController(dev.mikoto2000.rei.modeloptions.ChatModelSelection selection) {
    java.util.Objects.requireNonNull(selection);
    this.modelCatalog =
        () -> {
          var snapshot = selection.models();
          return new Models(
              snapshot.models(), snapshot.selection().model(), snapshot.selection().endpoint());
        };
    this.selectedModel =
        () -> {
          var current = selection.selection();
          return new Selection(current.model(), current.endpoint());
        };
    this.selectModel =
        request -> {
          if (request.endpoint() == null) selection.selectModel(request.model());
          else selection.selectModel(request.endpoint(), request.model());
        };
  }

  /**
   * Web APIによる操作で受け渡すモデル一覧を表す。
   *
   * @param models モデル一覧
   * @param currentModel 現在モデル
   * @param endpoint 一覧を取得した接続先
   */
  public record Models(List<String> models, String currentModel, String endpoint) {
    public Models(List<String> models, String currentModel) {
      this(models, currentModel, null);
    }

    public Models {
      models = models == null ? null : Collections.unmodifiableList(new ArrayList<>(models));
    }
  }

  /**
   * Web APIによる操作で受け渡す選択内容を表す。
   *
   * @param model モデル
   * @param endpoint 変更対象の接続先。省略時は既存の選択方式を使う
   */
  public record Selection(String model, String endpoint) implements StrictApiRequest {
    public Selection(String model) {
      this(model, null);
    }

    /** 受け取った情報からSelectionを作成する。 */
    public Selection {
      if (model == null
          || model.isBlank()
          || model.length() > 1024
          || model.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Valid model name required");
      if (endpoint != null && !endpoint.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
        throw new IllegalArgumentException("Valid registered endpoint name required");
    }
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Models list() {
    return modelCatalog.get();
  }

  /** Web APIによる操作で用いる現在を返す。 */
  @GetMapping("/current")
  public Selection current() {
    return selectedModel.get();
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  @PutMapping("/current")
  public synchronized Selection select(@RequestBody Selection request) {
    if (request == null) throw new IllegalArgumentException("Model required");
    try {
      selectModel.accept(request);
    } catch (IllegalStateException changed) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "Chat selection changed or unavailable");
    }
    return current();
  }
}
