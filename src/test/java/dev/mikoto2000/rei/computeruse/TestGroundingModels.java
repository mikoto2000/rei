package dev.mikoto2000.rei.computeruse;

/**
 * 検証条件を再現するための検証Groundingモデル一覧。
 *
 * <p>Geometry/protocol unit tests use a trusted verifier; rejection is tested separately.
 */
final class TestGroundingModels {
  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  static ShowUiComputerVisionModel create(
      ComputerVisionModel planner,
      org.springframework.ai.chat.model.ChatModel model,
      java.util.function.Supplier<org.springframework.ai.openai.OpenAiChatOptions.Builder> options,
      java.util.function.BooleanSupplier cancelled) {
    return create(planner, model, options, cancelled, GroundingProtocol.SHOWUI);
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  static ShowUiComputerVisionModel create(
      ComputerVisionModel planner,
      org.springframework.ai.chat.model.ChatModel model,
      java.util.function.Supplier<org.springframework.ai.openai.OpenAiChatOptions.Builder> options,
      java.util.function.BooleanSupplier cancelled,
      GroundingProtocol protocol) {
    return new ShowUiComputerVisionModel(
        planner, model, options, cancelled, protocol, (o, i, t, p) -> {});
  }
}
