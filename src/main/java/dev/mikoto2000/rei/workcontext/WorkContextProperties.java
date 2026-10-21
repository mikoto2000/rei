package dev.mikoto2000.rei.workcontext;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 作業文脈の引き継ぎで受け渡す作業文脈設定を表す。
 *
 * @param autoUpdate 自動更新
 * @param autoPresent 自動存在する
 * @param maxContextTokens 最大文脈トークン数
 * @param maxInputTokens 最大入力トークン数
 * @param timeoutSeconds タイムアウトの秒数
 * @param maxTurns 最大やり取り一覧
 */
@ConfigurationProperties("rei.work-context")
public record WorkContextProperties(
    @DefaultValue("false") boolean autoUpdate,
    @DefaultValue("true") boolean autoPresent,
    @DefaultValue("1200") int maxContextTokens,
    @DefaultValue("12000") int maxInputTokens,
    @DefaultValue("120") int timeoutSeconds,
    @DefaultValue("20") int maxTurns) {
  /** 受け取った情報からWorkContextPropertiesを作成する。 */
  public WorkContextProperties {
    if (maxContextTokens < 128
        || maxInputTokens < 1024
        || timeoutSeconds < 1
        || maxTurns < 1
        || maxTurns > 100) throw new IllegalArgumentException("Invalid Work Context budgets");
  }
}
