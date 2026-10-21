package dev.mikoto2000.rei.llm;

/**
 * 用途に応じたモデル利用に必要な開始AI互換接続先を扱う。
 *
 * <p>Keeps Rei's existing server-root configuration compatible with the official SDK.
 */
public final class OpenAiCompatibleEndpoint {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private OpenAiCompatibleEndpoint() {}

  /** 用途に応じたモデル利用で用いる接続先の基準URLを返す。 */
  public static String baseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
    String normalized = baseUrl.strip().replaceAll("/+$", "");
    return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
  }
}
