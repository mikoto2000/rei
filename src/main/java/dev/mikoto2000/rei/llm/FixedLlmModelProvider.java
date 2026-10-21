package dev.mikoto2000.rei.llm;

/** 固定言語モデルモデルを用途や設定に応じて提供する窓口。 */
public class FixedLlmModelProvider extends LlmModelProvider {
  /** 既定の状態でFixedLlmModelProviderを初期化する。 */
  public FixedLlmModelProvider() {
    super(null, new LlmProperties());
  }

  /** 用途に応じたモデル利用で用いるモデルを返す。 */
  @Override
  public String model(String feature, String defaultModel) {
    return defaultModel;
  }
}
