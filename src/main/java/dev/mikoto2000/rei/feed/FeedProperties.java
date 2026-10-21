package dev.mikoto2000.rei.feed;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * フィードの購読と収集で受け渡すフィード設定を表す。
 *
 * @param briefingMaxItems 日次案内最大項目一覧
 * @param authentication 認証
 */
@ConfigurationProperties(prefix = "rei.feed")
public record FeedProperties(int briefingMaxItems, List<Authentication> authentication) {

  /** 受け取った情報からFeedPropertiesを作成する。 */
  @ConstructorBinding
  public FeedProperties {
    if (briefingMaxItems <= 0) briefingMaxItems = 3;
    authentication = authentication == null ? List.of() : List.copyOf(authentication);
  }

  /** フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FeedProperties(int briefingMaxItems) {
    this(briefingMaxItems, List.of());
  }

  /** フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FeedProperties() {
    this(3);
  }

  /**
   * フィードの購読と収集で受け渡す認証を表す。
   *
   * <p>Only references belong here, never the token itself.
   *
   * @param url 参照先のURL
   * @param allowedOrigin 許可起点
   * @param tokenEnv トークン環境変数
   */
  public record Authentication(String url, String allowedOrigin, String tokenEnv) {
    /** フィードの購読と収集の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Authentication[configuration redacted]";
    }
  }
}
