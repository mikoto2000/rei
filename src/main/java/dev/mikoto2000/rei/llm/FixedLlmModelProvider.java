package dev.mikoto2000.rei.llm;

public class FixedLlmModelProvider extends LlmModelProvider {
  public FixedLlmModelProvider() {
    super(null, new LlmProperties());
  }

  @Override
  public String model(String feature, String defaultModel) {
    return defaultModel;
  }
}
