package dev.mikoto2000.rei.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * APIキーの設定値をまとめ、利用環境に応じた動作を指定できるようにする。
 *
 * <p>The key is supplied once by bootstrap, never exposed by toString.
 */
@ConfigurationProperties("rei")
public final class ApiKeyProperties {
  /** APIキー。 */
  private final String apiKey;

  /** 指定された値や利用先を使って、ApiKeyPropertiesを初期化する。 */
  public ApiKeyProperties(String apiKey) {
    this.apiKey = apiKey;
  }

  /** Web APIによる操作で参照するAPIキーを返す。 */
  public String getApiKey() {
    return apiKey;
  }
}
