package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 機能の構成をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/config")
public final class ConfigurationController {
  /** サービス。 */
  private final ExternalConfigFileService service;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す初期化を表す。
   *
   * @param force 強制
   */
  public record Initialize(boolean force) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、ConfigurationControllerを初期化する。 */
  public ConfigurationController(ExternalConfigFileService service) {
    this.service = service;
  }

  /** Web APIによる操作で用いるパスを返す。 */
  @GetMapping("/path")
  public Text path() {
    return new Text(service.configFilePath().toString());
  }

  /** Web APIによる操作で用いる初期化を返す。 */
  @PostMapping("/initialize")
  public Text initialize(@RequestBody Initialize request) {
    if (request == null) throw new IllegalArgumentException("Initialization options required");
    return new Text("設定テンプレートを作成しました: " + service.initializeConfigFile(request.force()));
  }
}
