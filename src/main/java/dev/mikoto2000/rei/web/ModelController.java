package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.service.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

/** Named model controls using the same holder and unload behavior as the existing Shell. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/models")
public class ModelController {
  private final OpenAiCompatibleModelsService models;
  private final ModelHolderService holder;
  private final OpenAiCompatibleModelUnloadService unload;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ModelController.class);

  public ModelController(
      OpenAiCompatibleModelsService models,
      ModelHolderService holder,
      OpenAiCompatibleModelUnloadService unload) {
    this.models = models;
    this.holder = holder;
    this.unload = unload;
  }

  public record Models(List<String> models, String currentModel) {}

  public record Selection(String model) implements StrictApiRequest {
    public Selection {
      if (model == null
          || model.isBlank()
          || model.length() > 1024
          || model.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Valid model name required");
    }
  }

  @GetMapping
  public Models list() {
    return new Models(models.listModels(), holder.get());
  }

  @GetMapping("/current")
  public Selection current() {
    return new Selection(holder.get());
  }

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
