package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/config")
public final class ConfigurationController {
  private final ExternalConfigFileService service;

  public record Text(String text) {}

  public record Initialize(boolean force) implements StrictApiRequest {}

  public ConfigurationController(ExternalConfigFileService service) {
    this.service = service;
  }

  @GetMapping("/path")
  public Text path() {
    return new Text(service.configFilePath().toString());
  }

  @PostMapping("/initialize")
  public Text initialize(@RequestBody Initialize request) {
    if (request == null) throw new IllegalArgumentException("Initialization options required");
    return new Text("設定テンプレートを作成しました: " + service.initializeConfigFile(request.force()));
  }
}
